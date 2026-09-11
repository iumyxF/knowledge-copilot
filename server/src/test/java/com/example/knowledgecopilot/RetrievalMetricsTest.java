package com.example.knowledgecopilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.knowledgecopilot.evaluation.RetrievalMetrics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

class RetrievalMetricsTest {
    @Test
    void deduplicatesDocumentsAndUsesRelevantDocumentDenominator() {
        var ranked = RetrievalMetrics.rankDocuments(List.of(2L, 2L, 1L, 3L));
        assertThat(ranked).containsExactly(2L, 1L, 3L);
        var metrics = RetrievalMetrics.calculate(Set.of(1L, 3L), ranked);
        assertThat(metrics.recallAt5()).isEqualTo(1);
        assertThat(metrics.mrrAt10()).isEqualTo(0.5);
        assertThat(metrics.hitRateAt10()).isEqualTo(1);
        assertThat(RetrievalMetrics.calculate(Set.of(1L, 99L), ranked).recallAt10()).isEqualTo(0.5);
    }

    @Test
    void emptyResultsAndRankElevenAreMisses() {
        assertThat(RetrievalMetrics.calculate(Set.of(1L), List.of()).mrrAt10()).isZero();
        var result =
                RetrievalMetrics.calculate(
                        Set.of(11L), LongStream.rangeClosed(1, 11).boxed().toList());
        assertThat(result.recallAt10()).isZero();
        assertThat(result.hitRateAt10()).isZero();
    }

    @Test
    void averagesPerCaseAndExcludesNoAnswerCases() {
        var average =
                RetrievalMetrics.average(
                        List.of(
                                RetrievalMetrics.calculate(Set.of(1L), List.of(1L)),
                                RetrievalMetrics.calculate(Set.of(2L), List.of(1L))));
        assertThat(average.recallAt5()).isEqualTo(0.5);
        assertThatThrownBy(() -> RetrievalMetrics.calculate(Set.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
