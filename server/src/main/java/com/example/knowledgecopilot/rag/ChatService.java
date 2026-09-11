package com.example.knowledgecopilot.rag;

import com.example.knowledgecopilot.common.BusinessException;
import com.example.knowledgecopilot.infrastructure.CopilotProperties;
import com.example.knowledgecopilot.infrastructure.ModelConfiguration;

import dev.langchain4j.model.TokenCountEstimator;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatService {
    public static final String REFUSAL = "当前知识库没有足够的信息回答该问题。";
    private final RetrievalService retrieval;
    private final AnswerGenerator generator;
    private final ModelConfiguration models;
    private final CopilotProperties properties;
    private final TokenCountEstimator estimator;

    public record Citation(
            Long documentId,
            String documentName,
            Long chunkId,
            String content,
            Integer page,
            String section) {
    }

    public record Answer(String answer, boolean refused, List<Citation> citations) {
    }

    public Answer chat(Long baseId, String question) {
        models.requireChat();
        List<RetrievalService.Hit> candidates =
                retrieval.retrieve(baseId, question, properties.getTopK());
        List<RetrievalService.Hit> selected = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        for (var hit : candidates) {
            String section = "\n文档：" + hit.documentName() + "\n" + hit.content() + "\n";
            if (estimator.estimateTokenCountInText(context + section)
                    <= properties.getContextTokens()) {
                selected.add(hit);
                context.append(section);
            }
        }
        if (selected.isEmpty()) {
            return new Answer(REFUSAL, true, List.of());
        }
        retrieval.verifyAvailable(selected);
        String answer = generator.generate(question, context.toString());
        if (answer == null || answer.isBlank()) {
            throw new BusinessException(503, "EMPTY_MODEL_ANSWER", "模型返回空答案");
        }
        retrieval.verifyAvailable(selected);
        List<Citation> citations =
                selected.stream()
                        .map(
                                hit ->
                                        new Citation(
                                                hit.documentId(),
                                                hit.documentName(),
                                                hit.chunkId(),
                                                hit.content(),
                                                hit.page(),
                                                hit.section()))
                        .toList();
        return new Answer(answer, REFUSAL.equals(answer.strip()), citations);
    }
}
