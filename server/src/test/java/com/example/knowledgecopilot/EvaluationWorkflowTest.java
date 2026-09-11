package com.example.knowledgecopilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.knowledgecopilot.common.BusinessException;
import com.example.knowledgecopilot.evaluation.EvaluationCase;
import com.example.knowledgecopilot.evaluation.EvaluationCaseResult;
import com.example.knowledgecopilot.evaluation.EvaluationDatasetMapper;
import com.example.knowledgecopilot.evaluation.EvaluationRequests.CaseRequest;
import com.example.knowledgecopilot.evaluation.EvaluationRequests.DatasetRequest;
import com.example.knowledgecopilot.evaluation.EvaluationRequests.Evidence;
import com.example.knowledgecopilot.evaluation.EvaluationRequests.ImportCase;
import com.example.knowledgecopilot.evaluation.EvaluationRequests.ImportRequest;
import com.example.knowledgecopilot.evaluation.EvaluationRunService;
import com.example.knowledgecopilot.evaluation.EvaluationService;
import com.example.knowledgecopilot.infrastructure.ModelConfiguration;
import com.example.knowledgecopilot.knowledge.base.KnowledgeBaseService;
import com.example.knowledgecopilot.knowledge.document.KnowledgeDocument;
import com.example.knowledgecopilot.knowledge.document.KnowledgeDocumentMapper;
import com.example.knowledgecopilot.rag.RetrievalService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest
@ActiveProfiles("test")
class EvaluationWorkflowTest {
    @Autowired EvaluationService datasets;
    @Autowired EvaluationRunService runs;
    @Autowired KnowledgeBaseService bases;
    @Autowired KnowledgeDocumentMapper documents;
    @Autowired EvaluationDatasetMapper datasetMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ModelConfiguration models;
    @MockitoBean RetrievalService retrieval;

    @MockitoBean(name = "evaluationExecutor")
    ThreadPoolTaskExecutor executor;

    Long baseId;
    Long documentId;
    Long datasetId;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM evaluation_case_result");
        jdbc.update("DELETE FROM evaluation_run");
        jdbc.update("DELETE FROM evaluation_case");
        jdbc.update("DELETE FROM evaluation_dataset");
        jdbc.update("DELETE FROM document_chunk");
        jdbc.update("DELETE FROM knowledge_document");
        jdbc.update("DELETE FROM knowledge_base");
        baseId = bases.create("评测测试", null).getId();
        documentId = seed(baseId);
        datasetId = datasets.create(new DatasetRequest(baseId, "baseline", null)).getId();
        datasets.createCase(datasetId, positive("原问题"));
        datasets.createCase(
                datasetId, new CaseRequest("无答案", null, List.of(), List.of(), true, List.of()));
        when(models.fingerprint()).thenReturn("fixed");
        when(retrieval.retrieve(eq(baseId), anyString(), anyInt()))
                .thenReturn(
                        List.of(
                                new RetrievalService.Hit(
                                        documentId, "测试", 20L, 1, "证据", 2, null, null)));
    }

    private Long seed(Long baseId) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setKnowledgeBaseId(baseId);
        document.setOriginalName("sample.txt");
        document.setStoragePath("unused");
        document.setFileType("txt");
        document.setFileSize(1L);
        document.setSha256("abc");
        document.setStatus("AVAILABLE");
        document.setAttemptNo(1);
        document.setChunkCount(1);
        document.setPipelineConfigJson("{}");
        documents.insert(document);
        return document.getId();
    }

    private CaseRequest positive(String question) {
        return new CaseRequest(
                question,
                "参考答案",
                List.of(documentId),
                List.of(new Evidence(documentId, "证据")),
                false,
                List.of());
    }

    @Test
    void runUsesSnapshotsAndManualCasesDoNotDiluteMetrics() {
        var run = runs.start(datasetId);
        var original = datasets.allCases(datasetId).getFirst();
        datasets.updateCase(original.getId(), positive("改后的问题"));
        runs.execute(run.getId());
        var completed = runs.require(run.getId());
        assertThat(completed.getStatus()).isEqualTo("COMPLETED");
        assertThat(completed.getMetricsJson()).contains("\"recallAt5\":1.0");
        verify(retrieval).retrieve(eq(baseId), eq("原问题"), anyInt());
        assertThat(runs.results(run.getId(), 1, 20).records())
                .extracting(EvaluationCaseResult::getStatus)
                .containsExactly("COMPLETED", "MANUAL_REVIEW");
        assertThat(completed.getCaseSnapshotJson()).contains("原问题").doesNotContain("改后的问题");
    }

    @Test
    void corpusChangesInvalidateWholeRun() {
        var run = runs.start(datasetId);
        when(retrieval.retrieve(eq(baseId), anyString(), anyInt()))
                .thenAnswer(
                        call -> {
                            bases.incrementRevision(baseId);
                            return List.of();
                        });
        runs.execute(run.getId());
        assertThat(runs.require(run.getId()).getErrorCode()).isEqualTo("CORPUS_CHANGED");
        assertThat(runs.require(run.getId()).getMetricsJson()).isNull();
    }

    @Test
    void errorsAreNotMissesOrValidBaseline() {
        when(retrieval.retrieve(eq(baseId), anyString(), anyInt()))
                .thenThrow(new BusinessException(503, "ES_FAILED", "失败"));
        var run = runs.start(datasetId);
        runs.execute(run.getId());
        assertThat(runs.require(run.getId()).getStatus()).isEqualTo("FAILED");
        assertThat(runs.require(run.getId()).getMetricsJson()).isNull();
    }

    @Test
    void queueFailureAndDeletionOfRunningDatasetAreExplicit() {
        var run = runs.start(datasetId);
        assertThatThrownBy(() -> datasets.delete(datasetId)).isInstanceOf(BusinessException.class);
        doThrow(new TaskRejectedException("full")).when(executor).execute(any(Runnable.class));
        var rejected = runs.start(datasetId);
        assertThat(rejected.getErrorCode()).isEqualTo("TASK_QUEUE_FULL");
        assertThat(rejected.getId()).isNotEqualTo(run.getId());
    }

    @Test
    void importValidatesAllMappingsAndRollsBack() {
        long before = datasetMapper.selectCount(null);
        var request =
                new ImportRequest(
                        baseId,
                        "bad",
                        null,
                        Map.of("other", documentId),
                        List.of(
                                new ImportCase(
                                        "问题",
                                        null,
                                        List.of("missing"),
                                        List.of(),
                                        false,
                                        List.of())));
        assertThatThrownBy(() -> datasets.importDataset(request))
                .isInstanceOf(BusinessException.class);
        assertThat(datasetMapper.selectCount(null)).isEqualTo(before);
        Long otherBase = bases.create("另一个知识库", null).getId();
        Long otherDocument = seed(otherBase);
        assertThatThrownBy(
                        () ->
                                datasets.createCase(
                                        datasetId,
                                        new CaseRequest(
                                                "问题",
                                                null,
                                                List.of(otherDocument),
                                                List.of(),
                                                false,
                                                List.of())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void bundledTwentyCasesCanBeImportedWithRealIdMapping() throws Exception {
        var template =
                mapper.readValue(
                        Files.readString(Path.of("samples/evaluation/baseline.json")),
                        ImportRequest.class);
        Map<String, Long> mapping = new LinkedHashMap<>();
        template.documentMapping().keySet().forEach(key -> mapping.put(key, seed(baseId)));
        var imported =
                datasets.importDataset(
                        new ImportRequest(
                                baseId,
                                template.name(),
                                template.description(),
                                mapping,
                                template.cases()));
        assertThat(datasets.allCases(imported.getId())).hasSize(20);
        assertThat(
                        datasets.allCases(imported.getId()).stream()
                                .filter(EvaluationCase::getExpectedRefusal))
                .hasSize(4);
    }
}
