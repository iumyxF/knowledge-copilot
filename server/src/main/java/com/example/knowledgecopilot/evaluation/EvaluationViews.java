package com.example.knowledgecopilot.evaluation;

import com.example.knowledgecopilot.common.JsonSupport;
import com.fasterxml.jackson.databind.JsonNode;

public final class EvaluationViews {
    private EvaluationViews() {
    }

    public record DatasetView(
            Long id, Long knowledgeBaseId, String name, String description, long revision) {
        public static DatasetView of(EvaluationDataset dataset) {
            return new DatasetView(
                    dataset.getId(),
                    dataset.getKnowledgeBaseId(),
                    dataset.getName(),
                    dataset.getDescription(),
                    dataset.getRevision());
        }
    }

    public record CaseView(
            Long id,
            Long datasetId,
            String question,
            String expectedAnswer,
            JsonNode relevantDocumentIds,
            JsonNode evidence,
            boolean expectedRefusal,
            JsonNode tags) {
        public static CaseView of(EvaluationCase item, JsonSupport json) {
            return new CaseView(
                    item.getId(),
                    item.getDatasetId(),
                    item.getQuestion(),
                    item.getExpectedAnswer(),
                    json.read(item.getRelevantDocumentIdsJson(), JsonNode.class),
                    json.read(item.getEvidenceJson(), JsonNode.class),
                    item.getExpectedRefusal(),
                    json.read(item.getTagsJson(), JsonNode.class));
        }
    }

    public record RunView(
            Long id,
            Long datasetId,
            String status,
            int totalCases,
            int completedCases,
            long datasetRevision,
            long corpusRevision,
            JsonNode config,
            JsonNode metrics,
            JsonNode corpusManifest,
            String errorCode,
            String errorMessage) {
        public static RunView of(EvaluationRun run, JsonSupport json) {
            return new RunView(
                    run.getId(),
                    run.getDatasetId(),
                    run.getStatus(),
                    run.getTotalCases(),
                    run.getCompletedCases(),
                    run.getDatasetRevision(),
                    run.getCorpusRevision(),
                    tree(run.getConfigSnapshotJson(), json),
                    tree(run.getMetricsJson(), json),
                    tree(run.getCorpusManifestJson(), json),
                    run.getErrorCode(),
                    run.getErrorMessage());
        }
    }

    public record ResultView(
            Long id,
            Long runId,
            Long caseId,
            String status,
            JsonNode caseSnapshot,
            JsonNode retrievedChunks,
            JsonNode rankedDocuments,
            JsonNode metrics,
            long latencyMs,
            String errorCode,
            String errorMessage) {
        public static ResultView of(EvaluationCaseResult result, JsonSupport json) {
            return new ResultView(
                    result.getId(),
                    result.getRunId(),
                    result.getCaseId(),
                    result.getStatus(),
                    tree(result.getCaseSnapshotJson(), json),
                    tree(result.getRetrievedChunksJson(), json),
                    tree(result.getRankedDocumentsJson(), json),
                    tree(result.getMetricsJson(), json),
                    result.getLatencyMs(),
                    result.getErrorCode(),
                    result.getErrorMessage());
        }
    }

    private static JsonNode tree(String value, JsonSupport json) {
        return value == null ? null : json.read(value, JsonNode.class);
    }
}
