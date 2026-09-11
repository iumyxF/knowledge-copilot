package com.example.knowledgecopilot.infrastructure;

import com.example.knowledgecopilot.common.BusinessException;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

@Component
@RequiredArgsConstructor
public class ModelConfiguration {
    private final CopilotProperties properties;
    private ChatModel chat;
    private EmbeddingModel embedding;

    public void requireEmbedding() {
        require(properties.getEmbedding(), "Embedding");
        if (properties.getEmbedding().getDimension() < 1) {
            throw new BusinessException(503, "MODEL_NOT_CONFIGURED", "请配置 Embedding 向量维度");
        }
    }

    public void requireChat() {
        require(properties.getChat(), "Chat");
    }

    private void require(CopilotProperties.Model model, String name) {
        if (!model.isEnabled()
                || model.getBaseUrl().isBlank()
                || model.getModelName().isBlank()
                || model.getApiKey().isBlank()) {
            throw new BusinessException(503, "MODEL_NOT_CONFIGURED", name + " 模型未配置");
        }
    }

    public synchronized EmbeddingModel embedding() {
        requireEmbedding();
        if (embedding == null) {
            var config = properties.getEmbedding();
            embedding =
                    OpenAiEmbeddingModel.builder()
                            .baseUrl(config.getBaseUrl())
                            .apiKey(config.getApiKey())
                            .modelName(config.getModelName())
                            .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                            .maxRetries(config.getMaxRetries())
                            .build();
        }
        return embedding;
    }

    public synchronized ChatModel chat() {
        requireChat();
        if (chat == null) {
            var config = properties.getChat();
            chat =
                    OpenAiChatModel.builder()
                            .baseUrl(config.getBaseUrl())
                            .apiKey(config.getApiKey())
                            .modelName(config.getModelName())
                            .temperature(0.0)
                            .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                            .maxRetries(config.getMaxRetries())
                            .build();
        }
        return chat;
    }

    public String fingerprint() {
        var model = properties.getEmbedding();
        String identity =
                model.getBaseUrl() + "\n" + model.getModelName() + "\n" + model.getDimension();
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public BusinessException failure(RuntimeException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException) {
                return new BusinessException(504, "MODEL_TIMEOUT", "模型服务调用超时");
            }
        }
        return new BusinessException(503, "MODEL_CALL_FAILED", "模型调用失败，请检查配置和服务状态");
    }
}
