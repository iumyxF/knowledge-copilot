package com.example.knowledgecopilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.knowledgecopilot.evaluation.EvaluationRequests;
import com.example.knowledgecopilot.evaluation.EvaluationRunService;
import com.example.knowledgecopilot.evaluation.EvaluationService;
import com.example.knowledgecopilot.knowledge.base.KnowledgeBaseService;
import com.example.knowledgecopilot.knowledge.document.DocumentService;
import com.example.knowledgecopilot.rag.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.apache.http.HttpHost;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** 真实数据库和 ES + 本地确定性模型协议替身，不是模型质量评测。 */
@SpringBootTest
@EnabledIfSystemProperty(named = "test.e2e.mysql.url", matches = ".+")
class EndToEndTest {
    private static final String INDEX = "copilot-e2e-" + UUID.randomUUID();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger CHAT_CALLS = new AtomicInteger();
    private static HttpServer modelServer;
    @Autowired KnowledgeBaseService bases;
    @Autowired DocumentService documents;
    @Autowired ChatService chat;
    @Autowired EvaluationService datasets;
    @Autowired EvaluationRunService runs;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        modelServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        modelServer.createContext(
                "/v1/embeddings",
                exchange -> {
                    var request = JSON.readTree(exchange.getRequestBody());
                    int count = request.path("input").isArray() ? request.path("input").size() : 1;
                    List<Map<String, Object>> data = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        data.add(
                                Map.of(
                                        "object",
                                        "embedding",
                                        "index",
                                        i,
                                        "embedding",
                                        List.of(1.0, 0.0, 0.0)));
                    }
                    byte[] response =
                            JSON.writeValueAsBytes(
                                    Map.of(
                                            "object",
                                            "list",
                                            "data",
                                            data,
                                            "model",
                                            "fixture",
                                            "usage",
                                            Map.of("prompt_tokens", count, "total_tokens", count)));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        modelServer.createContext(
                "/v1/chat/completions",
                exchange -> {
                    var request = JSON.readTree(exchange.getRequestBody());
                    assertThat(request.path("messages").toString()).contains("资料开始");
                    CHAT_CALLS.incrementAndGet();
                    byte[] response =
                            JSON.writeValueAsBytes(
                                    Map.of(
                                            "id",
                                            "fixture-response",
                                            "object",
                                            "chat.completion",
                                            "created",
                                            1,
                                            "model",
                                            "fixture",
                                            "choices",
                                            List.of(
                                                    Map.of(
                                                            "index",
                                                            0,
                                                            "message",
                                                            Map.of(
                                                                    "role",
                                                                    "assistant",
                                                                    "content",
                                                                    "这是受控模型替身返回的测试答案。"),
                                                            "finish_reason",
                                                            "stop")),
                                            "usage",
                                            Map.of(
                                                    "prompt_tokens",
                                                    10,
                                                    "completion_tokens",
                                                    10,
                                                    "total_tokens",
                                                    20)));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        modelServer.start();
        String modelUrl = "http://127.0.0.1:" + modelServer.getAddress().getPort() + "/v1";
        registry.add("spring.datasource.url", () -> System.getProperty("test.e2e.mysql.url"));
        registry.add(
                "spring.datasource.username",
                () -> System.getProperty("test.e2e.mysql.username", "root"));
        registry.add(
                "spring.datasource.password",
                () -> System.getProperty("test.e2e.mysql.password", ""));
        registry.add("copilot.storage-root", () -> "./target/e2e-files");
        registry.add(
                "copilot.elasticsearch.url", () -> System.getProperty("test.elasticsearch.url"));
        registry.add("copilot.elasticsearch.index", () -> INDEX);
        registry.add("copilot.chat.enabled", () -> true);
        registry.add("copilot.chat.base-url", () -> modelUrl);
        registry.add("copilot.chat.api-key", () -> "fixture");
        registry.add("copilot.chat.model-name", () -> "fixture");
        registry.add("copilot.embedding.enabled", () -> true);
        registry.add("copilot.embedding.base-url", () -> modelUrl);
        registry.add("copilot.embedding.api-key", () -> "fixture");
        registry.add("copilot.embedding.model-name", () -> "fixture");
        registry.add("copilot.embedding.dimension", () -> 3);
    }

    @Test
    void uploadChatEvaluateAndDeleteCompleteWithRealInfrastructure() throws Exception {
        Long baseId = bases.create("e2e-" + UUID.randomUUID(), "协议替身验证").getId();
        var source = JSON.readTree(Files.readString(Path.of("samples/source.json")));
        Map<String, Long> mapping = new LinkedHashMap<>();
        for (var sample : source) {
            String name = sample.path("file").asText();
            var document =
                    documents.upload(
                            baseId,
                            new MockMultipartFile(
                                    "file",
                                    name,
                                    "application/octet-stream",
                                    Files.readAllBytes(Path.of("samples/documents", name))));
            mapping.put(sample.path("key").asText(), document.getId());
        }
        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(
                        () ->
                                mapping.values()
                                        .forEach(
                                                id ->
                                                        assertThat(
                                                                        documents
                                                                                .require(id)
                                                                                .getStatus())
                                                                .isEqualTo("AVAILABLE")));
        var answer = chat.chat(baseId, "设备购买后多久可以退货？");
        assertThat(answer.answer()).contains("受控模型替身");
        assertThat(answer.citations())
                .isNotEmpty()
                .allSatisfy(
                        citation -> assertThat(mapping.values()).contains(citation.documentId()));

        Long emptyBase = bases.create("e2e-empty-" + UUID.randomUUID(), null).getId();
        int calls = CHAT_CALLS.get();
        assertThat(chat.chat(emptyBase, "退货规则？").refused()).isTrue();
        assertThat(CHAT_CALLS.get()).isEqualTo(calls);

        var template =
                JSON.readValue(
                        Files.readString(Path.of("samples/evaluation/baseline.json")),
                        EvaluationRequests.ImportRequest.class);
        var dataset =
                datasets.importDataset(
                        new EvaluationRequests.ImportRequest(
                                baseId, "e2e-baseline", "固定向量不能作为真实基线", mapping, template.cases()));
        var run = runs.start(dataset.getId());
        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(
                        () ->
                                assertThat(runs.require(run.getId()).getStatus())
                                        .isEqualTo("COMPLETED"));
        assertThat(runs.require(run.getId()).getCompletedCases()).isEqualTo(20);
        assertThat(runs.results(run.getId(), 1, 100).records()).hasSize(20);
        assertThat(CHAT_CALLS.get()).isEqualTo(calls);

        datasets.delete(dataset.getId());
        for (Long id : mapping.values()) {
            documents.delete(id);
        }
        assertThat(chat.chat(baseId, "设备购买后多久可以退货？").refused()).isTrue();
        bases.delete(baseId);
        bases.delete(emptyBase);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (modelServer != null) {
            modelServer.stop(0);
        }
        try (var client =
                RestClient.builder(HttpHost.create(System.getProperty("test.elasticsearch.url")))
                        .build()) {
            client.performRequest(new Request("DELETE", "/" + INDEX));
        }
    }
}
