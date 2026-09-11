package com.example.knowledgecopilot.rag;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import com.example.knowledgecopilot.common.BusinessException;
import com.example.knowledgecopilot.infrastructure.CopilotProperties;
import com.example.knowledgecopilot.infrastructure.ModelConfiguration;
import com.example.knowledgecopilot.infrastructure.VectorIndex;
import com.example.knowledgecopilot.knowledge.chunk.DocumentChunk;
import com.example.knowledgecopilot.knowledge.chunk.DocumentChunkMapper;
import com.example.knowledgecopilot.knowledge.document.DocumentService;
import com.example.knowledgecopilot.knowledge.document.KnowledgeDocument;

import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.filter.Filter;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class RetrievalService {
    private final DocumentService documents;
    private final DocumentChunkMapper chunks;
    private final VectorIndex index;
    private final ModelConfiguration models;
    private final CopilotProperties properties;

    public record Hit(
            Long documentId,
            String documentName,
            Long chunkId,
            int attemptNo,
            String content,
            int tokenCount,
            Integer page,
            String section) {
    }

    public List<Hit> retrieve(Long baseId, String question, int limit) {
        models.requireEmbedding();
        List<KnowledgeDocument> available = documents.available(baseId);
        if (available.isEmpty()) {
            return List.of();
        }
        index.ensureIndex();
        Filter filter = filter(baseId, available);
        var retriever =
                EmbeddingStoreContentRetriever.builder()
                        .embeddingStore(index.store())
                        .embeddingModel(models.embedding())
                        .filter(filter)
                        .maxResults(limit)
                        .minScore(properties.getMinScore())
                        .build();
        try {
            List<Hit> hits = new ArrayList<>();
            Map<Long, KnowledgeDocument> valid = new HashMap<>();
            available.forEach(document -> valid.put(document.getId(), document));
            for (var result : retriever.retrieve(Query.from(question))) {
                var metadata = result.textSegment().metadata();
                DocumentChunk chunk = chunks.selectById(metadata.getLong("chunkId"));
                if (chunk == null || !baseId.equals(chunk.getKnowledgeBaseId())) {
                    throw BusinessException.conflict("检索结果对应切片已变化");
                }
                var document = valid.get(chunk.getDocumentId());
                if (document == null
                        || !document.getAttemptNo().equals(chunk.getAttemptNo())
                        || !Objects.equals(metadata.getInteger("attemptNo"), chunk.getAttemptNo())
                        || !chunk.getContent().equals(result.textSegment().text())) {
                    throw BusinessException.conflict("检索索引与业务数据不一致");
                }
                hits.add(
                        new Hit(
                                document.getId(),
                                document.getOriginalName(),
                                chunk.getId(),
                                chunk.getAttemptNo(),
                                chunk.getContent(),
                                chunk.getTokenCount(),
                                chunk.getPage(),
                                chunk.getSection()));
            }
            return hits;
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BusinessException(503, "RETRIEVAL_FAILED", "检索失败，请检查模型与 Elasticsearch");
        }
    }

    public static Filter filter(Long baseId, List<KnowledgeDocument> available) {
        if (available.isEmpty()) {
            throw new IllegalArgumentException("有效文档集合不能为空");
        }
        Filter batch = null;
        for (var document : available) {
            Filter current =
                    metadataKey("documentId")
                            .isEqualTo(document.getId())
                            .and(metadataKey("attemptNo").isEqualTo(document.getAttemptNo()));
            batch = batch == null ? current : batch.or(current);
        }
        return metadataKey("knowledgeBaseId").isEqualTo(baseId).and(batch);
    }

    public void verifyAvailable(List<Hit> hits) {
        for (Hit hit : hits) {
            var document = documents.require(hit.documentId());
            if (!"AVAILABLE".equals(document.getStatus())
                    || document.getAttemptNo() != hit.attemptNo()) {
                throw BusinessException.conflict("引用文档已变化，请重新提问");
            }
        }
    }
}
