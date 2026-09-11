package com.example.knowledgecopilot;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.knowledgecopilot.common.JsonSupport;
import com.example.knowledgecopilot.infrastructure.CopilotProperties;
import com.example.knowledgecopilot.infrastructure.ModelConfiguration;
import com.example.knowledgecopilot.infrastructure.VectorIndex;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.util.List;

@Testcontainers(disabledWithoutDocker = true)
class InfrastructureTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.44");

    @Container
    static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.18.8")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    @Test
    void mysqlMigrationsAreRepeatable() throws Exception {
        var flyway =
                Flyway.configure()
                        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                        .load();
        flyway.migrate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var connection =
                        DriverManager.getConnection(
                                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                var statement = connection.createStatement();
                var result =
                        statement.executeQuery(
                                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema"
                                        + " = DATABASE()")) {
            result.next();
            assertThat(result.getInt(1)).isEqualTo(8);
        }
    }

    @Test
    void vectorWritesFiltersAndDeletesAgainstRealElasticsearch() throws Exception {
        var properties = new CopilotProperties();
        properties.getElasticsearch().setUrl("http://" + ELASTICSEARCH.getHttpHostAddress());
        properties.getEmbedding().setEnabled(true);
        properties.getEmbedding().setApiKey("fixed-vector-test");
        properties.getEmbedding().setModelName("fixed-vectors");
        properties.getEmbedding().setDimension(3);
        var models = new ModelConfiguration(properties);
        var mapper = new ObjectMapper();
        try (var resource =
                new IndexResource(
                        new VectorIndex(properties, models, new JsonSupport(mapper), mapper))) {
            var index = resource.index();
            var vector = Embedding.from(new float[] {1, 0, 0});
            var segment =
                    TextSegment.from(
                            "测试",
                            new Metadata()
                                    .put("knowledgeBaseId", 1L)
                                    .put("documentId", 2L)
                                    .put("attemptNo", 1)
                                    .put("chunkId", 3L));
            index.add(List.of("2-1-0"), List.of(vector), List.of(segment));
            index.add(List.of("2-1-0"), List.of(vector), List.of(segment));
            index.verifyVisible(2L, 1, 1);
            assertThat(
                            index.store()
                                    .search(
                                            EmbeddingSearchRequest.builder()
                                                    .queryEmbedding(vector)
                                                    .filter(
                                                            metadataKey("knowledgeBaseId")
                                                                    .isEqualTo(1L))
                                                    .maxResults(5)
                                                    .build())
                                    .matches())
                    .hasSize(1);
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
            index.removeDocument(2L);
            index.removeDocument(2L);
            index.verifyVisible(2L, 1, 0);
        }
    }

    private record IndexResource(VectorIndex index) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            index.close();
        }
    }
}
