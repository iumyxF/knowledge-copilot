package com.example.knowledgecopilot.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "copilot")
public class CopilotProperties {
    private String storageRoot = "./data/documents";

    @Min(1)
    private long maxFileBytes = 20 * 1024 * 1024;

    @Min(2)
    private int chunkTokens = 500;

    @Min(0)
    private int overlapTokens = 50;

    @Min(1)
    private int contextTokens = 3000;

    @Min(1)
    @Max(100)
    private int topK = 5;

    private double minScore = 0;

    @Min(10)
    @Max(1000)
    private int candidateLimit = 100;

    @Min(1)
    private int ingestionWorkers = 2;

    @Min(1)
    private int ingestionQueue = 20;

    @Min(1)
    private int evaluationWorkers = 1;

    @Min(1)
    private int evaluationQueue = 5;

    @Min(1)
    private int embeddingBatchSize = 16;

    @Valid
    private Model chat = new Model();
    @Valid
    private Model embedding = new Model();
    private Elasticsearch elasticsearch = new Elasticsearch();

    @Data
    public static class Model {
        private boolean enabled;
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String modelName = "";
        private int dimension;

        @Min(1)
        private int timeoutSeconds = 60;

        @Min(0)
        @Max(3)
        private int maxRetries = 1;
    }

    @Data
    public static class Elasticsearch {
        private String url = "http://localhost:9200";
        private String username = "";
        private String password = "";
        private String index = "knowledge-copilot-v1";
    }
}
