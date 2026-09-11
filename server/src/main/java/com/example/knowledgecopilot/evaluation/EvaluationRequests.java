package com.example.knowledgecopilot.evaluation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public final class EvaluationRequests {
    private EvaluationRequests() {
    }

    public record DatasetRequest(
            @NotNull @Positive Long knowledgeBaseId,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 1000) String description) {
    }

    public record Evidence(@NotNull @Positive Long documentId, @NotBlank String text) {
    }

    public record CaseRequest(
            @NotBlank @Size(max = 4000) String question,
            String expectedAnswer,
            @NotNull List<@NotNull @Positive Long> relevantDocumentIds,
            @NotNull @Valid List<Evidence> evidence,
            boolean expectedRefusal,
            @NotNull List<@NotBlank String> tags) {
    }

    public record ImportEvidence(@NotBlank String documentKey, @NotBlank String text) {
    }

    public record ImportCase(
            @NotBlank @Size(max = 4000) String question,
            String expectedAnswer,
            @NotNull List<@NotBlank String> relevantDocumentKeys,
            @NotNull @Valid List<ImportEvidence> evidence,
            boolean expectedRefusal,
            @NotNull List<@NotBlank String> tags) {
    }

    public record ImportRequest(
            @NotNull @Positive Long knowledgeBaseId,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 1000) String description,
            @NotEmpty Map<String, @NotNull @Positive Long> documentMapping,
            @NotEmpty @Size(max = 1000) @Valid List<ImportCase> cases) {
    }
}
