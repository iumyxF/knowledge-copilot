package com.example.knowledgecopilot.infrastructure;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import com.example.knowledgecopilot.common.BusinessException;
import com.example.knowledgecopilot.common.JsonSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;

import jakarta.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class VectorIndex {
    private final CopilotProperties properties;
    private final ModelConfiguration models;
    private final JsonSupport json;
    private final ObjectMapper objectMapper;
    private RestClient client;
    private ElasticsearchEmbeddingStore store;

    private String indexName() {
        String name = properties.getElasticsearch().getIndex();
        if (!name.matches("[a-z0-9][a-z0-9_-]{0,199}")) {
            throw BusinessException.invalid("ES 索引名称无效");
        }
        return name;
    }

    private synchronized RestClient client() {
        if (client == null) {
            var config = properties.getElasticsearch();
            var builder =
                    RestClient.builder(HttpHost.create(config.getUrl()))
                            .setRequestConfigCallback(
                                    request ->
                                            request.setConnectTimeout(5000)
                                                    .setSocketTimeout(60000));
            if (!config.getUsername().isBlank()) {
                BasicCredentialsProvider credentials = new BasicCredentialsProvider();
                credentials.setCredentials(
                        AuthScope.ANY,
                        new UsernamePasswordCredentials(
                                config.getUsername(), config.getPassword()));
                builder.setHttpClientConfigCallback(
                        http -> http.setDefaultCredentialsProvider(credentials));
            }
            client = builder.build();
        }
        return client;
    }

    private synchronized ElasticsearchEmbeddingStore delegate() {
        if (store == null) {
            store =
                    ElasticsearchEmbeddingStore.builder()
                            .restClient(client())
                            .indexName(indexName())
                            .build();
        }
        return store;
    }

    public ElasticsearchEmbeddingStore store() {
        ensureIndex();
        return delegate();
    }

    /**
     * 应用仅管理专用索引，指纹不一致时拒绝使用已有向量空间。
     */
    public synchronized void ensureIndex() {
        models.requireEmbedding();
        try {
            JsonNode mapping;
            try {
                mapping = request("GET", "/" + indexName() + "/_mapping", null);
            } catch (ResponseException ex) {
                if (ex.getResponse().getStatusLine().getStatusCode() != 404) {
                    throw ex;
                }
                Map<String, Object> fields =
                        Map.of(
                                "vector",
                                Map.of(
                                        "type",
                                        "dense_vector",
                                        "dims",
                                        properties.getEmbedding().getDimension(),
                                        "index",
                                        true,
                                        "similarity",
                                        "cosine"),
                                "text", Map.of("type", "text"),
                                "metadata",
                                Map.of(
                                        "properties",
                                        Map.of(
                                                "knowledgeBaseId",
                                                Map.of("type", "long"),
                                                "documentId",
                                                Map.of("type", "long"),
                                                "attemptNo",
                                                Map.of("type", "integer"),
                                                "chunkId",
                                                Map.of("type", "long"))));
                request(
                        "PUT",
                        "/" + indexName(),
                        Map.of(
                                "mappings",
                                Map.of(
                                        "_meta",
                                        Map.of("copilotFingerprint", models.fingerprint()),
                                        "properties",
                                        fields)));
                mapping = request("GET", "/" + indexName() + "/_mapping", null);
            }
            JsonNode mappings = mapping.path(indexName()).path("mappings");
            if (!models.fingerprint()
                    .equals(mappings.path("_meta").path("copilotFingerprint").asText())
                    || mappings.path("properties").path("vector").path("dims").asInt()
                    != properties.getEmbedding().getDimension()) {
                throw new BusinessException(
                        409, "EMBEDDING_INDEX_MISMATCH", "模型或向量维度与索引不一致，请使用新索引重新上传");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unavailable();
        }
    }

    public void add(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
        ensureIndex();
        if (embeddings.size() != segments.size()
                || embeddings.stream()
                .anyMatch(e -> e.dimension() != properties.getEmbedding().getDimension())) {
            throw new BusinessException(409, "EMBEDDING_DIMENSION_MISMATCH", "模型返回数量或向量维度不符合配置");
        }
        try {
            delegate().addAll(ids, embeddings, segments);
        } catch (RuntimeException ex) {
            throw unavailable();
        }
    }

    public void verifyVisible(Long documentId, int attempt, int expectedCount) {
        try {
            request("POST", "/" + indexName() + "/_refresh", null);
            JsonNode result =
                    request(
                            "POST",
                            "/" + indexName() + "/_count",
                            Map.of(
                                    "query",
                                    Map.of(
                                            "bool",
                                            Map.of(
                                                    "filter",
                                                    List.of(
                                                            Map.of(
                                                                    "term",
                                                                    Map.of(
                                                                            "metadata.documentId",
                                                                            documentId)),
                                                            Map.of(
                                                                    "term",
                                                                    Map.of(
                                                                            "metadata.attemptNo",
                                                                            attempt)))))));
            if (result.path("count").asInt() != expectedCount) {
                throw new BusinessException(503, "INDEX_INCOMPLETE", "索引数量校验失败");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw unavailable();
        }
    }

    public void removeDocument(Long documentId) {
        try {
            try {
                request("HEAD", "/" + indexName(), null);
            } catch (ResponseException ex) {
                if (ex.getResponse().getStatusLine().getStatusCode() == 404) {
                    return;
                }
                throw ex;
            }
            delegate().removeAll(metadataKey("documentId").isEqualTo(documentId));
            request("POST", "/" + indexName() + "/_refresh", null);
            JsonNode remaining =
                    request(
                            "POST",
                            "/" + indexName() + "/_count",
                            Map.of(
                                    "query",
                                    Map.of("term", Map.of("metadata.documentId", documentId))));
            if (remaining.path("count").asLong() != 0) {
                throw unavailable();
            }
        } catch (Exception ex) {
            throw unavailable();
        }
    }

    private JsonNode request(String method, String path, Object body) throws Exception {
        Request request = new Request(method, path);
        if (body != null) {
            request.setJsonEntity(json.write(body));
        }
        Response response = client().performRequest(request);
        if (response.getEntity() == null) {
            return objectMapper.createObjectNode();
        }
        try (var input = response.getEntity().getContent()) {
            return objectMapper.readTree(input);
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(503, "ELASTICSEARCH_FAILED", "Elasticsearch 操作失败，请检查服务和索引状态");
    }

    @PreDestroy
    public void close() throws Exception {
        if (client != null) {
            client.close();
        }
    }
}
