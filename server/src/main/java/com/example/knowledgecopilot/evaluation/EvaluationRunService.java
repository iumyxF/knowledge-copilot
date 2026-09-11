package com.example.knowledgecopilot.evaluation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.knowledgecopilot.common.BusinessException;
import com.example.knowledgecopilot.common.JsonSupport;
import com.example.knowledgecopilot.common.PageResult;
import com.example.knowledgecopilot.infrastructure.CopilotProperties;
import com.example.knowledgecopilot.infrastructure.ModelConfiguration;
import com.example.knowledgecopilot.knowledge.base.KnowledgeBaseService;
import com.example.knowledgecopilot.knowledge.document.DocumentService;
import com.example.knowledgecopilot.rag.RetrievalService;
import com.fasterxml.jackson.core.type.TypeReference;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class EvaluationRunService {
    private static final TypeReference<List<EvaluationCase>> CASE_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Set<Long>> ID_SET = new TypeReference<>() {
    };
    private final EvaluationService datasets;
    private final EvaluationRunMapper runs;
    private final EvaluationCaseResultMapper results;
    private final KnowledgeBaseService bases;
    private final DocumentService documents;
    private final RetrievalService retrieval;
    private final CopilotProperties properties;
    private final ModelConfiguration models;
    private final JsonSupport json;
    private final TransactionTemplate transactions;
    private final ThreadPoolTaskExecutor executor;

    public EvaluationRunService(
            EvaluationService datasets,
            EvaluationRunMapper runs,
            EvaluationCaseResultMapper results,
            KnowledgeBaseService bases,
            DocumentService documents,
            RetrievalService retrieval,
            CopilotProperties properties,
            ModelConfiguration models,
            JsonSupport json,
            TransactionTemplate transactions,
            @Qualifier("evaluationExecutor") ThreadPoolTaskExecutor executor) {
        this.datasets = datasets;
        this.runs = runs;
        this.results = results;
        this.bases = bases;
        this.documents = documents;
        this.retrieval = retrieval;
        this.properties = properties;
        this.models = models;
        this.json = json;
        this.transactions = transactions;
        this.executor = executor;
    }

    public EvaluationRun require(Long id) {
        var run = runs.selectById(id);
        if (run == null) {
            throw BusinessException.notFound();
        }
        return run;
    }

    public EvaluationRun start(Long datasetId) {
        models.requireEmbedding();
        EvaluationRun run =
                transactions.execute(
                        transaction -> {
                            var dataset = datasets.lock(datasetId);
                            List<EvaluationCase> items = datasets.allCases(datasetId);
                            if (items.stream().noneMatch(item -> !item.getExpectedRefusal())) {
                                throw BusinessException.invalid("至少需要一个正向评测用例");
                            }
                            for (EvaluationCase item : items) {
                                for (Long documentId :
                                        json.read(item.getRelevantDocumentIdsJson(), ID_SET)) {
                                    if (!"AVAILABLE".equals(datasets.requireDocument(
                                                    dataset.getKnowledgeBaseId(),
                                                    documentId)
                                            .getStatus())) {
                                        throw BusinessException.conflict("相关文档尚未 AVAILABLE");
                                    }
                                }
                            }
                            var base = bases.require(dataset.getKnowledgeBaseId());
                            var available = documents.available(base.getId());
                            EvaluationRun created = new EvaluationRun();
                            created.setDatasetId(datasetId);
                            created.setStatus("QUEUED");
                            created.setCaseSnapshotJson(json.write(items));
                            created.setCorpusManifestJson(
                                    json.write(
                                            available.stream()
                                                    .map(
                                                            document ->
                                                                    Map.of(
                                                                            "documentId",
                                                                            document.getId(),
                                                                            "sha256",
                                                                            document.getSha256(),
                                                                            "attemptNo",
                                                                            document.getAttemptNo(),
                                                                            "pipelineConfig",
                                                                            document
                                                                                    .getPipelineConfigJson()))
                                                    .toList()));
                            created.setConfigSnapshotJson(
                                    json.write(
                                            Map.of(
                                                    "knowledgeBaseId",
                                                    base.getId(),
                                                    "embeddingFingerprint",
                                                    models.fingerprint(),
                                                    "embeddingModel",
                                                    properties.getEmbedding().getModelName(),
                                                    "index",
                                                    properties.getElasticsearch().getIndex(),
                                                    "minScore",
                                                    properties.getMinScore(),
                                                    "candidateChunkLimit",
                                                    properties.getCandidateLimit(),
                                                    "tokenEstimator",
                                                    "gpt-4o-mini",
                                                    "metricVersion",
                                                    "document-rank-v1")));
                            created.setDatasetRevision(dataset.getRevision());
                            created.setCorpusRevision(base.getCorpusRevision());
                            created.setTotalCases(items.size());
                            created.setCompletedCases(0);
                            runs.insert(created);
                            return created;
                        });
        try {
            executor.execute(() -> execute(run.getId()));
        } catch (org.springframework.core.task.TaskRejectedException ex) {
            fail(run.getId(), "TASK_QUEUE_FULL", "评测队列已满，请重新发起运行");
        }
        return require(run.getId());
    }

    public void execute(Long id) {
        if (runs.update(
                null,
                update(id)
                        .eq(EvaluationRun::getStatus, "QUEUED")
                        .set(EvaluationRun::getStatus, "RUNNING")
                        .set(EvaluationRun::getStartedAt, LocalDateTime.now()))
                != 1) {
            return;
        }
        try {
            EvaluationRun run = require(id);
            var config =
                    json.read(
                            run.getConfigSnapshotJson(),
                            com.fasterxml.jackson.databind.JsonNode.class);
            Long baseId = config.path("knowledgeBaseId").asLong();
            if (bases.require(baseId).getCorpusRevision().longValue() != run.getCorpusRevision()) {
                throw new BusinessException(409, "CORPUS_CHANGED", "语料在排队期间已变化");
            }
            List<RetrievalMetrics.Metrics> metrics = new ArrayList<>();
            boolean hasErrors = false;
            int completed = 0;
            for (EvaluationCase item : json.read(run.getCaseSnapshotJson(), CASE_LIST)) {
                long start = System.nanoTime();
                EvaluationCaseResult result = new EvaluationCaseResult();
                result.setRunId(id);
                result.setCaseId(item.getId());
                result.setCaseSnapshotJson(json.write(item));
                try {
                    var hits =
                            retrieval.retrieve(
                                    baseId,
                                    item.getQuestion(),
                                    config.path("candidateChunkLimit").asInt());
                    var ranked =
                            RetrievalMetrics.rankDocuments(
                                    hits.stream().map(RetrievalService.Hit::documentId).toList());
                    result.setRetrievedChunksJson(json.write(hits));
                    result.setRankedDocumentsJson(json.write(ranked));
                    if (item.getExpectedRefusal()) {
                        result.setStatus("MANUAL_REVIEW");
                    } else {
                        var metric =
                                RetrievalMetrics.calculate(
                                        json.read(item.getRelevantDocumentIdsJson(), ID_SET),
                                        ranked);
                        metrics.add(metric);
                        result.setMetricsJson(json.write(metric));
                        result.setStatus("COMPLETED");
                    }
                } catch (RuntimeException ex) {
                    hasErrors = true;
                    result.setStatus("ERROR");
                    result.setErrorCode(
                            ex instanceof BusinessException business
                                    ? business.getCode()
                                    : "CASE_FAILED");
                    result.setErrorMessage("用例执行失败，请检查依赖或语料状态后重跑");
                }
                result.setLatencyMs((System.nanoTime() - start) / 1_000_000);
                results.insert(result);
                completed++;
                runs.update(null, update(id).set(EvaluationRun::getCompletedCases, completed));
            }
            boolean failed = hasErrors;
            transactions.executeWithoutResult(
                    transaction -> {
                        var base = bases.lock(baseId);
                        if (base.getCorpusRevision().longValue() != run.getCorpusRevision()) {
                            fail(id, "CORPUS_CHANGED", "运行期间语料变化，本次不能作为有效基线");
                        } else if (failed) {
                            fail(id, "CASE_FAILED", "存在执行错误，请查看用例结果；不生成有效总分");
                        } else {
                            runs.update(
                                    null,
                                    update(id)
                                            .set(EvaluationRun::getStatus, "COMPLETED")
                                            .set(
                                                    EvaluationRun::getMetricsJson,
                                                    json.write(RetrievalMetrics.average(metrics)))
                                            .set(
                                                    EvaluationRun::getFinishedAt,
                                                    LocalDateTime.now()));
                        }
                    });
        } catch (RuntimeException ex) {
            fail(
                    id,
                    ex instanceof BusinessException business ? business.getCode() : "RUN_FAILED",
                    "评测运行失败，请检查详情并重新发起");
        }
    }

    public PageResult<EvaluationRun> list(Long datasetId, long pageNo, long pageSize) {
        // 即使数据集逻辑删除，历史运行仍可只读查询。
        return PageResult.of(
                runs.selectPage(
                        PageResult.page(pageNo, pageSize),
                        new LambdaQueryWrapper<EvaluationRun>()
                                .eq(EvaluationRun::getDatasetId, datasetId)
                                .orderByDesc(EvaluationRun::getId)));
    }

    public PageResult<EvaluationCaseResult> results(Long runId, long pageNo, long pageSize) {
        require(runId);
        return PageResult.of(
                results.selectPage(
                        PageResult.page(pageNo, pageSize),
                        new LambdaQueryWrapper<EvaluationCaseResult>()
                                .eq(EvaluationCaseResult::getRunId, runId)
                                .orderByAsc(EvaluationCaseResult::getCaseId)));
    }

    private LambdaUpdateWrapper<EvaluationRun> update(Long id) {
        return new LambdaUpdateWrapper<EvaluationRun>()
                .eq(EvaluationRun::getId, id)
                .set(EvaluationRun::getUpdatedAt, LocalDateTime.now());
    }

    private void fail(Long id, String code, String message) {
        runs.update(
                null,
                update(id)
                        .set(EvaluationRun::getStatus, "FAILED")
                        .set(EvaluationRun::getErrorCode, code)
                        .set(EvaluationRun::getErrorMessage, message)
                        .set(EvaluationRun::getMetricsJson, null)
                        .set(EvaluationRun::getFinishedAt, LocalDateTime.now()));
    }
}
