package com.example.knowledgecopilot;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.knowledgecopilot.common.BusinessException;
import com.example.knowledgecopilot.common.JsonSupport;
import com.example.knowledgecopilot.infrastructure.CopilotProperties;
import com.example.knowledgecopilot.infrastructure.ModelConfiguration;
import com.example.knowledgecopilot.infrastructure.VectorIndex;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;

import org.apache.http.HttpHost;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.UUID;

@EnabledIfSystemProperty(named = "test.elasticsearch.url", matches = ".+")
class LocalElasticsearchTest {
    @Test
    void vectorLifecycleUsesRealServerWithoutExternalModels() throws Exception {
        String url = System.getProperty("test.elasticsearch.url");
        String indexName = "copilot-test-" + UUID.randomUUID();
        var properties = new CopilotProperties();
        properties.getElasticsearch().setUrl(url);
        properties.getElasticsearch().setIndex(indexName);
        properties.getEmbedding().setEnabled(true);
        properties.getEmbedding().setApiKey("fixed-vector-test");
        properties.getEmbedding().setModelName("fixed-vectors");
        properties.getEmbedding().setDimension(3);
        var models = new ModelConfiguration(properties);
        var mapper = new ObjectMapper();
        VectorIndex index = new VectorIndex(properties, models, new JsonSupport(mapper), mapper);
        try {
            var vector = Embedding.from(new float[] {1, 0, 0});
            var first =
                    TextSegment.from(
                            "第一批次",
                            new Metadata()
                                    .put("knowledgeBaseId", 1L)
                                    .put("documentId", 2L)
                                    .put("attemptNo", 1)
                                    .put("chunkId", 3L));
            var second =
                    TextSegment.from(
                            "失败批次",
                            new Metadata()
                                    .put("knowledgeBaseId", 1L)
                                    .put("documentId", 2L)
                                    .put("attemptNo", 2)
                                    .put("chunkId", 4L));
            index.add(List.of("2-1-0"), List.of(vector), List.of(first));
            index.add(List.of("2-1-0"), List.of(vector), List.of(first));
            index.add(List.of("2-2-0"), List.of(vector), List.of(second));
            index.verifyVisible(2L, 1, 1);
            var filter =
                    metadataKey("knowledgeBaseId")
                            .isEqualTo(1L)
                            .and(metadataKey("documentId").isEqualTo(2L))
                            .and(metadataKey("attemptNo").isEqualTo(1));
            var hits =
                    index.store()
                            .search(
                                    EmbeddingSearchRequest.builder()
                                            .queryEmbedding(vector)
                                            .filter(filter)
                                            .maxResults(5)
                                            .build())
                            .matches();
            assertThat(hits).hasSize(1);
            assertThat(hits.getFirst().embedded().text()).isEqualTo("第一批次");
            assertThat(
                            index.store()
                                    .search(
                                            EmbeddingSearchRequest.builder()
                                                    .queryEmbedding(vector)
                                                    .filter(
                                                            metadataKey("knowledgeBaseId")
                                                                    .isEqualTo(9L))
                                                    .maxResults(5)
                                                    .build())
                                    .matches())
                    .isEmpty();
            properties.getEmbedding().setModelName("other-model-same-dimension");
            assertThatThrownBy(index::ensureIndex)
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo("EMBEDDING_INDEX_MISMATCH");
            properties.getEmbedding().setModelName("fixed-vectors");
            index.removeDocument(2L);
            index.removeDocument(2L);
            index.verifyVisible(2L, 1, 0);
        } finally {
            index.close();
            try (var client = RestClient.builder(HttpHost.create(url)).build()) {
                // 只清理本测试随机创建的索引，不接触已有应用索引。
                client.performRequest(new Request("DELETE", "/" + indexName));
            }
        }
    }
}
