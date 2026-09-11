package com.example.knowledgecopilot.evaluation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.knowledgecopilot.common.BusinessException;
import com.example.knowledgecopilot.common.JsonSupport;
import com.example.knowledgecopilot.common.PageResult;
import com.example.knowledgecopilot.evaluation.EvaluationRequests.CaseRequest;
import com.example.knowledgecopilot.evaluation.EvaluationRequests.DatasetRequest;
import com.example.knowledgecopilot.evaluation.EvaluationRequests.Evidence;
import com.example.knowledgecopilot.evaluation.EvaluationRequests.ImportCase;
import com.example.knowledgecopilot.evaluation.EvaluationRequests.ImportRequest;
import com.example.knowledgecopilot.knowledge.base.KnowledgeBaseService;
import com.example.knowledgecopilot.knowledge.document.KnowledgeDocument;
import com.example.knowledgecopilot.knowledge.document.KnowledgeDocumentMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EvaluationService {
    private final EvaluationDatasetMapper datasets;
    private final EvaluationCaseMapper cases;
    private final EvaluationRunMapper runs;
    private final KnowledgeDocumentMapper documents;
    private final KnowledgeBaseService bases;
    private final JsonSupport json;

    public EvaluationDataset require(Long id) {
        var dataset = datasets.selectById(id);
        if (dataset == null || Boolean.TRUE.equals(dataset.getIsDeleted())) {
            throw BusinessException.notFound();
        }
        return dataset;
    }

    public EvaluationDataset lock(Long id) {
        var existing = require(id);
        bases.lock(existing.getKnowledgeBaseId());
        var dataset =
                datasets.selectOne(
                        new LambdaQueryWrapper<EvaluationDataset>()
                                .eq(EvaluationDataset::getId, id)
                                .last("FOR UPDATE"));
        if (Boolean.TRUE.equals(dataset.getIsDeleted())) {
            throw BusinessException.notFound();
        }
        return dataset;
    }

    @Transactional
    public EvaluationDataset create(DatasetRequest request) {
        bases.lock(request.knowledgeBaseId());
        EvaluationDataset dataset = new EvaluationDataset();
        dataset.setKnowledgeBaseId(request.knowledgeBaseId());
        dataset.setName(request.name().trim());
        dataset.setDescription(request.description());
        dataset.setRevision(0L);
        dataset.setIsDeleted(false);
        datasets.insert(dataset);
        return require(dataset.getId());
    }

    @Transactional
    public EvaluationDataset update(Long id, DatasetRequest request) {
        var dataset = lock(id);
        if (!dataset.getKnowledgeBaseId().equals(request.knowledgeBaseId())) {
            throw BusinessException.invalid("评测集不允许更换所属知识库");
        }
        datasets.update(
                null,
                new LambdaUpdateWrapper<EvaluationDataset>()
                        .eq(EvaluationDataset::getId, id)
                        .set(EvaluationDataset::getName, request.name().trim())
                        .set(EvaluationDataset::getDescription, request.description()));
        increment(id);
        return require(id);
    }

    public PageResult<EvaluationDataset> list(long pageNo, long pageSize) {
        return PageResult.of(
                datasets.selectPage(
                        PageResult.page(pageNo, pageSize),
                        new LambdaQueryWrapper<EvaluationDataset>()
                                .eq(EvaluationDataset::getIsDeleted, false)
                                .orderByDesc(EvaluationDataset::getId)));
    }

    @Transactional
    public void delete(Long id) {
        lock(id);
        if (runs.selectCount(
                new LambdaQueryWrapper<EvaluationRun>()
                        .eq(EvaluationRun::getDatasetId, id)
                        .in(EvaluationRun::getStatus, "QUEUED", "RUNNING"))
                > 0) {
            throw BusinessException.conflict("评测集有未完成运行");
        }
        datasets.update(
                null,
                new LambdaUpdateWrapper<EvaluationDataset>()
                        .eq(EvaluationDataset::getId, id)
                        .set(EvaluationDataset::getIsDeleted, true)
                        .set(EvaluationDataset::getUpdatedAt, LocalDateTime.now()));
    }

    public EvaluationCase requireCase(Long id) {
        var item = cases.selectById(id);
        if (item == null) {
            throw BusinessException.notFound();
        }
        require(item.getDatasetId());
        return item;
    }

    public PageResult<EvaluationCase> listCases(Long id, long pageNo, long pageSize) {
        require(id);
        return PageResult.of(
                cases.selectPage(
                        PageResult.page(pageNo, pageSize),
                        new LambdaQueryWrapper<EvaluationCase>()
                                .eq(EvaluationCase::getDatasetId, id)
                                .orderByAsc(EvaluationCase::getId)));
    }

    public List<EvaluationCase> allCases(Long id) {
        return cases.selectList(
                new LambdaQueryWrapper<EvaluationCase>()
                        .eq(EvaluationCase::getDatasetId, id)
                        .orderByAsc(EvaluationCase::getId));
    }

    @Transactional
    public EvaluationCase createCase(Long id, CaseRequest request) {
        var dataset = lock(id);
        validate(dataset.getKnowledgeBaseId(), request);
        EvaluationCase item = new EvaluationCase();
        item.setDatasetId(id);
        fill(item, request);
        cases.insert(item);
        increment(id);
        return item;
    }

    @Transactional
    public EvaluationCase updateCase(Long id, CaseRequest request) {
        var existing = requireCase(id);
        var dataset = lock(existing.getDatasetId());
        var item = requireCase(id);
        validate(dataset.getKnowledgeBaseId(), request);
        fill(item, request);
        item.setUpdatedAt(LocalDateTime.now());
        cases.updateById(item);
        // 显式支持清空参考答案，避免 MyBatis 默认忽略 null。
        if (request.expectedAnswer() == null) {
            cases.update(
                    null,
                    new LambdaUpdateWrapper<EvaluationCase>()
                            .eq(EvaluationCase::getId, id)
                            .set(EvaluationCase::getExpectedAnswer, null));
        }
        increment(item.getDatasetId());
        return requireCase(id);
    }

    @Transactional
    public void deleteCase(Long id) {
        var item = requireCase(id);
        lock(item.getDatasetId());
        cases.deleteById(id);
        increment(item.getDatasetId());
    }

    @Transactional
    public EvaluationDataset importDataset(ImportRequest request) {
        bases.lock(request.knowledgeBaseId());
        request.documentMapping()
                .values()
                .forEach(id -> requireDocument(request.knowledgeBaseId(), id));
        List<CaseRequest> converted = new ArrayList<>();
        for (ImportCase item : request.cases()) {
            List<Long> ids =
                    item.relevantDocumentKeys().stream().map(key -> mapped(request, key)).toList();
            List<Evidence> evidence =
                    item.evidence().stream()
                            .map(
                                    entry ->
                                            new Evidence(
                                                    mapped(request, entry.documentKey()),
                                                    entry.text()))
                            .toList();
            CaseRequest convertedCase =
                    new CaseRequest(
                            item.question(),
                            item.expectedAnswer(),
                            ids,
                            evidence,
                            item.expectedRefusal(),
                            item.tags());
            validate(request.knowledgeBaseId(), convertedCase);
            converted.add(convertedCase);
        }
        var dataset =
                create(
                        new DatasetRequest(
                                request.knowledgeBaseId(), request.name(), request.description()));
        for (CaseRequest item : converted) {
            createCase(dataset.getId(), item);
        }
        return require(dataset.getId());
    }

    private Long mapped(ImportRequest request, String key) {
        Long id = request.documentMapping().get(key);
        if (id == null) {
            throw BusinessException.invalid("缺少 documentKey 映射：" + key);
        }
        return id;
    }

    private void validate(Long baseId, CaseRequest request) {
        Set<Long> ids = new HashSet<>(request.relevantDocumentIds());
        if (request.expectedRefusal() != ids.isEmpty()) {
            throw BusinessException.invalid("正向用例必须有相关文档；无答案用例必须没有相关文档");
        }
        ids.forEach(id -> requireDocument(baseId, id));
        for (Evidence evidence : request.evidence()) {
            if (!ids.contains(evidence.documentId())) {
                throw BusinessException.invalid("证据文档必须属于相关文档集合");
            }
        }
    }

    public KnowledgeDocument requireDocument(Long baseId, Long id) {
        var document = documents.selectById(id);
        if (document == null
                || !baseId.equals(document.getKnowledgeBaseId())
                || "DELETED".equals(document.getStatus())
                || "DELETING".equals(document.getStatus())
                || "DELETE_FAILED".equals(document.getStatus())) {
            throw BusinessException.invalid("相关文档不存在或不属于目标知识库：" + id);
        }
        return document;
    }

    private void fill(EvaluationCase item, CaseRequest request) {
        item.setQuestion(request.question());
        item.setExpectedAnswer(request.expectedAnswer());
        item.setRelevantDocumentIdsJson(
                json.write(new LinkedHashSet<>(request.relevantDocumentIds())));
        item.setEvidenceJson(json.write(request.evidence()));
        item.setExpectedRefusal(request.expectedRefusal());
        item.setTagsJson(json.write(request.tags()));
    }

    private void increment(Long id) {
        datasets.update(
                null,
                new LambdaUpdateWrapper<EvaluationDataset>()
                        .eq(EvaluationDataset::getId, id)
                        .setSql("revision = revision + 1")
                        .set(EvaluationDataset::getUpdatedAt, LocalDateTime.now()));
    }
}
