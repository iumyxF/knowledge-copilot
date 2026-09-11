package com.example.knowledgecopilot.knowledge.ingestion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.knowledgecopilot.common.BusinessException;
import com.example.knowledgecopilot.common.JsonSupport;
import com.example.knowledgecopilot.infrastructure.CopilotProperties;
import com.example.knowledgecopilot.infrastructure.ModelConfiguration;
import com.example.knowledgecopilot.infrastructure.VectorIndex;
import com.example.knowledgecopilot.knowledge.base.KnowledgeBaseService;
import com.example.knowledgecopilot.knowledge.chunk.DocumentChunk;
import com.example.knowledgecopilot.knowledge.chunk.DocumentChunkMapper;
import com.example.knowledgecopilot.knowledge.document.DocumentStatus;
import com.example.knowledgecopilot.knowledge.document.KnowledgeDocument;
import com.example.knowledgecopilot.knowledge.document.KnowledgeDocumentMapper;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class IngestionService {
    private final KnowledgeDocumentMapper documents;
    private final DocumentChunkMapper chunks;
    private final KnowledgeBaseService bases;
    private final LocalFileStorage files;
    private final DocumentTextProcessor processor;
    private final ModelConfiguration models;
    private final VectorIndex index;
    private final CopilotProperties properties;
    private final JsonSupport json;
    private final TokenCountEstimator estimator;
    private final TransactionTemplate transactions;
    private final ThreadPoolTaskExecutor executor;

    public IngestionService(
            KnowledgeDocumentMapper documents,
            DocumentChunkMapper chunks,
            KnowledgeBaseService bases,
            LocalFileStorage files,
            DocumentTextProcessor processor,
            ModelConfiguration models,
            VectorIndex index,
            CopilotProperties properties,
            JsonSupport json,
            TokenCountEstimator estimator,
            TransactionTemplate transactions,
            @Qualifier("ingestionExecutor") ThreadPoolTaskExecutor executor) {
        this.documents = documents;
        this.chunks = chunks;
        this.bases = bases;
        this.files = files;
        this.processor = processor;
        this.models = models;
        this.index = index;
        this.properties = properties;
        this.json = json;
        this.estimator = estimator;
        this.transactions = transactions;
        this.executor = executor;
    }

    public void submit(Long id, int attempt) {
        try {
            executor.execute(() -> process(id, attempt));
        } catch (org.springframework.core.task.TaskRejectedException ex) {
            fail(id, attempt, "PROCESS_FAILED", "TASK_QUEUE_FULL", "入库队列已满，请稍后重试");
        }
    }

    public void process(Long id, int attempt) {
        if (documents.update(
                null,
                state(id, attempt)
                        .eq(KnowledgeDocument::getStatus, "UPLOADED")
                        .set(KnowledgeDocument::getStatus, "PARSING"))
                != 1) {
            return;
        }
        KnowledgeDocument document = documents.selectById(id);
        String failureState = "PARSE_FAILED";
        try {
            String content =
                    processor.parse(files.path(document.getStoragePath()), document.getFileType());
            documents.update(null, state(id, attempt).set(KnowledgeDocument::getStatus, "PARSED"));
            failureState = "INDEX_FAILED";
            List<TextSegment> split = processor.split(content);
            if (split.isEmpty()) {
                throw new BusinessException(422, "NO_CHUNKS", "未生成有效切片");
            }
            models.requireEmbedding();
            index.ensureIndex();
            index.removeDocument(id);
            chunks.delete(
                    new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocumentId, id));
            documents.update(
                    null,
                    state(id, attempt)
                            .set(KnowledgeDocument::getStatus, "INDEXING")
                            .set(
                                    KnowledgeDocument::getPipelineConfigJson,
                                    json.write(
                                            Map.of(
                                                    "chunkTokens",
                                                    properties.getChunkTokens(),
                                                    "overlapTokens",
                                                    properties.getOverlapTokens(),
                                                    "tokenEstimator",
                                                    "gpt-4o-mini",
                                                    "embeddingFingerprint",
                                                    models.fingerprint()))));
            List<DocumentChunk> records = new ArrayList<>();
            for (int position = 0; position < split.size(); position++) {
                DocumentChunk chunk = new DocumentChunk();
                chunk.setKnowledgeBaseId(document.getKnowledgeBaseId());
                chunk.setDocumentId(id);
                chunk.setAttemptNo(attempt);
                chunk.setChunkIndex(position);
                chunk.setContent(split.get(position).text());
                chunk.setTokenCount(estimator.estimateTokenCountInText(chunk.getContent()));
                chunk.setEmbeddingId(id + "-" + attempt + "-" + position);
                chunks.insert(chunk);
                records.add(chunk);
            }
            int batchSize = properties.getEmbeddingBatchSize();
            for (int start = 0; start < records.size(); start += batchSize) {
                List<DocumentChunk> batch =
                        records.subList(start, Math.min(start + batchSize, records.size()));
                List<TextSegment> segments =
                        batch.stream()
                                .map(
                                        chunk ->
                                                TextSegment.from(
                                                        chunk.getContent(),
                                                        new Metadata()
                                                                .put(
                                                                        "knowledgeBaseId",
                                                                        chunk.getKnowledgeBaseId())
                                                                .put("documentId", id)
                                                                .put("attemptNo", attempt)
                                                                .put("chunkId", chunk.getId())
                                                                .put(
                                                                        "chunkIndex",
                                                                        chunk.getChunkIndex())
                                                                .put(
                                                                        "tokenCount",
                                                                        chunk.getTokenCount())))
                                .toList();
                var embeddings = models.embedding().embedAll(segments).content();
                index.add(
                        batch.stream().map(DocumentChunk::getEmbeddingId).toList(),
                        embeddings,
                        segments);
            }
            index.verifyVisible(id, attempt, records.size());
            transactions.executeWithoutResult(
                    status -> {
                        bases.lock(document.getKnowledgeBaseId());
                        if (documents.update(
                                null,
                                state(id, attempt)
                                        .eq(KnowledgeDocument::getStatus, "INDEXING")
                                        .set(KnowledgeDocument::getStatus, "AVAILABLE")
                                        .set(
                                                KnowledgeDocument::getChunkCount,
                                                records.size()))
                                != 1) {
                            throw BusinessException.conflict("入库状态已变化");
                        }
                        bases.incrementRevision(document.getKnowledgeBaseId());
                    });
        } catch (RuntimeException ex) {
            String code =
                    ex instanceof BusinessException business
                            ? business.getCode()
                            : "PROCESSING_ERROR";
            String message =
                    ex instanceof BusinessException ? ex.getMessage() : "处理失败，请检查模型和外部依赖后重试";
            fail(id, attempt, failureState, code, message);
        }
    }

    private LambdaUpdateWrapper<KnowledgeDocument> state(Long id, int attempt) {
        return new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, id)
                .eq(KnowledgeDocument::getAttemptNo, attempt)
                .set(KnowledgeDocument::getUpdatedAt, LocalDateTime.now());
    }

    private void fail(Long id, int attempt, String status, String code, String message) {
        documents.update(
                null,
                state(id, attempt)
                        .in(KnowledgeDocument::getStatus, DocumentStatus.PROCESSING)
                        .set(KnowledgeDocument::getStatus, status)
                        .set(KnowledgeDocument::getErrorCode, code)
                        .set(KnowledgeDocument::getErrorMessage, message));
    }
}
