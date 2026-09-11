package com.example.knowledgecopilot.knowledge.document;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.knowledgecopilot.common.BusinessException;
import com.example.knowledgecopilot.common.PageResult;
import com.example.knowledgecopilot.infrastructure.ModelConfiguration;
import com.example.knowledgecopilot.infrastructure.VectorIndex;
import com.example.knowledgecopilot.knowledge.base.KnowledgeBaseService;
import com.example.knowledgecopilot.knowledge.chunk.DocumentChunk;
import com.example.knowledgecopilot.knowledge.chunk.DocumentChunkMapper;
import com.example.knowledgecopilot.knowledge.ingestion.IngestionService;
import com.example.knowledgecopilot.knowledge.ingestion.LocalFileStorage;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DocumentService {
    private final KnowledgeDocumentMapper mapper;
    private final DocumentChunkMapper chunks;
    private final KnowledgeBaseService bases;
    private final LocalFileStorage files;
    private final IngestionService ingestion;
    private final VectorIndex index;
    private final ModelConfiguration models;
    private final TransactionTemplate transactions;

    public KnowledgeDocument require(Long id) {
        KnowledgeDocument document = mapper.selectById(id);
        if (document == null) {
            throw BusinessException.notFound();
        }
        return document;
    }

    public KnowledgeDocument upload(Long baseId, MultipartFile file) {
        bases.require(baseId);
        models.requireEmbedding();
        LocalFileStorage.StoredFile stored = files.save(file);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setKnowledgeBaseId(baseId);
        document.setOriginalName(stored.name());
        document.setStoragePath(stored.path());
        document.setFileType(stored.type());
        document.setFileSize(stored.size());
        document.setSha256(stored.hash());
        document.setStatus("UPLOADED");
        document.setAttemptNo(1);
        document.setChunkCount(0);
        try {
            transactions.executeWithoutResult(
                    status -> {
                        bases.lock(baseId);
                        mapper.insert(document);
                    });
        } catch (RuntimeException ex) {
            files.delete(stored.path());
            throw ex;
        }
        ingestion.submit(document.getId(), document.getAttemptNo());
        return require(document.getId());
    }

    public KnowledgeDocument retry(Long id) {
        models.requireEmbedding();
        KnowledgeDocument document = require(id);
        int next = document.getAttemptNo() + 1;
        int updated =
                mapper.update(
                        null,
                        new LambdaUpdateWrapper<KnowledgeDocument>()
                                .eq(KnowledgeDocument::getId, id)
                                .eq(KnowledgeDocument::getAttemptNo, document.getAttemptNo())
                                .in(KnowledgeDocument::getStatus, DocumentStatus.RETRYABLE)
                                .set(KnowledgeDocument::getStatus, "UPLOADED")
                                .set(KnowledgeDocument::getAttemptNo, next)
                                .set(KnowledgeDocument::getChunkCount, 0)
                                .set(KnowledgeDocument::getErrorCode, null)
                                .set(KnowledgeDocument::getErrorMessage, null)
                                .set(KnowledgeDocument::getUpdatedAt, LocalDateTime.now()));
        if (updated != 1) {
            throw BusinessException.conflict("仅失败文档可重试，或任务已被其他请求领取");
        }
        ingestion.submit(id, next);
        return require(id);
    }

    public PageResult<KnowledgeDocument> list(
            Long baseId, String state, long pageNo, long pageSize) {
        bases.require(baseId);
        if (state != null) {
            try {
                DocumentStatus.valueOf(state);
            } catch (IllegalArgumentException ex) {
                throw BusinessException.invalid("无效文档状态");
            }
        }
        return PageResult.of(
                mapper.selectPage(
                        PageResult.page(pageNo, pageSize),
                        new LambdaQueryWrapper<KnowledgeDocument>()
                                .eq(KnowledgeDocument::getKnowledgeBaseId, baseId)
                                .eq(state != null, KnowledgeDocument::getStatus, state)
                                .orderByDesc(KnowledgeDocument::getId)));
    }

    public List<KnowledgeDocument> available(Long baseId) {
        bases.require(baseId);
        return mapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getKnowledgeBaseId, baseId)
                        .eq(KnowledgeDocument::getStatus, "AVAILABLE")
                        .orderByAsc(KnowledgeDocument::getId));
    }

    public void delete(Long id) {
        KnowledgeDocument document = require(id);
        if ("DELETED".equals(document.getStatus())) {
            return;
        }
        transactions.executeWithoutResult(
                status -> {
                    bases.lock(document.getKnowledgeBaseId());
                    int updated =
                            mapper.update(
                                    null,
                                    new LambdaUpdateWrapper<KnowledgeDocument>()
                                            .eq(KnowledgeDocument::getId, id)
                                            .eq(KnowledgeDocument::getStatus, document.getStatus())
                                            .eq(
                                                    KnowledgeDocument::getAttemptNo,
                                                    document.getAttemptNo())
                                            .notIn(
                                                    KnowledgeDocument::getStatus,
                                                    DocumentStatus.PROCESSING)
                                            .ne(KnowledgeDocument::getStatus, "DELETING")
                                            .ne(KnowledgeDocument::getStatus, "DELETED")
                                            .set(KnowledgeDocument::getStatus, "DELETING")
                                            .set(
                                                    KnowledgeDocument::getUpdatedAt,
                                                    LocalDateTime.now()));
                    if (updated != 1) {
                        throw BusinessException.conflict("文档处理中或删除中，请稍后重试");
                    }
                    bases.incrementRevision(document.getKnowledgeBaseId());
                });
        try {
            index.removeDocument(id);
            files.delete(document.getStoragePath());
            transactions.executeWithoutResult(
                    status -> {
                        chunks.delete(
                                new LambdaQueryWrapper<DocumentChunk>()
                                        .eq(DocumentChunk::getDocumentId, id));
                        mapper.update(
                                null,
                                new LambdaUpdateWrapper<KnowledgeDocument>()
                                        .eq(KnowledgeDocument::getId, id)
                                        .set(KnowledgeDocument::getStatus, "DELETED")
                                        .set(KnowledgeDocument::getChunkCount, 0)
                                        .set(KnowledgeDocument::getErrorCode, null)
                                        .set(KnowledgeDocument::getErrorMessage, null)
                                        .set(KnowledgeDocument::getUpdatedAt, LocalDateTime.now()));
                    });
        } catch (RuntimeException ex) {
            mapper.update(
                    null,
                    new LambdaUpdateWrapper<KnowledgeDocument>()
                            .eq(KnowledgeDocument::getId, id)
                            .set(KnowledgeDocument::getStatus, "DELETE_FAILED")
                            .set(KnowledgeDocument::getErrorCode, "DELETE_FAILED")
                            .set(KnowledgeDocument::getErrorMessage, "外部数据清理失败，请再次删除"));
            throw new BusinessException(503, "DELETE_FAILED", "文档已停止检索，清理失败，可再次 DELETE");
        }
    }
}
