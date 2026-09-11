package com.example.knowledgecopilot.evaluation;

import java.util.List;
import java.util.Set;

public final class RetrievalMetrics {
    private RetrievalMetrics() {
    }

    public record Metrics(double recallAt5, double recallAt10, double mrrAt10, double hitRateAt10) {
    }

    public static List<Long> rankDocuments(List<Long> candidateDocumentIds) {
        return candidateDocumentIds.stream().distinct().limit(10).toList();
    }

    public static Metrics calculate(Set<Long> relevant, List<Long> ranked) {
        if (relevant.isEmpty()) {
            throw new IllegalArgumentException("无答案用例不计算正向检索指标");
        }
        List<Long> documents = rankDocuments(ranked);
        double recall5 = recall(relevant, documents, 5);
        double recall10 = recall(relevant, documents, 10);
        double reciprocalRank = 0;
        for (int i = 0; i < documents.size(); i++) {
            if (relevant.contains(documents.get(i))) {
                reciprocalRank = 1.0 / (i + 1);
                break;
            }
        }
        return new Metrics(recall5, recall10, reciprocalRank, reciprocalRank > 0 ? 1 : 0);
    }

    private static double recall(Set<Long> relevant, List<Long> ranked, int k) {
        return (double) ranked.stream().limit(k).filter(relevant::contains).count()
                / relevant.size();
    }

    public static Metrics average(List<Metrics> metrics) {
        if (metrics.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个正向用例");
        }
        return new Metrics(
                metrics.stream().mapToDouble(Metrics::recallAt5).average().orElseThrow(),
                metrics.stream().mapToDouble(Metrics::recallAt10).average().orElseThrow(),
                metrics.stream().mapToDouble(Metrics::mrrAt10).average().orElseThrow(),
                metrics.stream().mapToDouble(Metrics::hitRateAt10).average().orElseThrow());
    }
}
