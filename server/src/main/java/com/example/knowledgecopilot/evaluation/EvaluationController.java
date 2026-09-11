package com.example.knowledgecopilot.evaluation;

import com.example.knowledgecopilot.common.ApiResponse;
import com.example.knowledgecopilot.common.JsonSupport;
import com.example.knowledgecopilot.common.PageResult;
import com.example.knowledgecopilot.evaluation.EvaluationRequests.CaseRequest;
import com.example.knowledgecopilot.evaluation.EvaluationRequests.DatasetRequest;
import com.example.knowledgecopilot.evaluation.EvaluationRequests.ImportRequest;
import com.example.knowledgecopilot.evaluation.EvaluationViews.CaseView;
import com.example.knowledgecopilot.evaluation.EvaluationViews.DatasetView;
import com.example.knowledgecopilot.evaluation.EvaluationViews.ResultView;
import com.example.knowledgecopilot.evaluation.EvaluationViews.RunView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "5. 评测")
@RestController
@RequestMapping("/api/v1/evaluation")
@RequiredArgsConstructor
public class EvaluationController {
    private final EvaluationService service;
    private final EvaluationRunService runs;
    private final JsonSupport json;

    @Operation(summary = "创建评测集")
    @PostMapping("/datasets")
    public ApiResponse<DatasetView> create(@Valid @RequestBody DatasetRequest request) {
        return ApiResponse.ok(DatasetView.of(service.create(request)));
    }

    @Operation(summary = "分页查询评测集")
    @GetMapping("/datasets")
    public ApiResponse<PageResult<DatasetView>> list(
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "20") long pageSize) {
        var page = service.list(pageNo, pageSize);
        return ApiResponse.ok(
                new PageResult<>(
                        page.total(),
                        page.pageNo(),
                        page.pageSize(),
                        page.records().stream().map(DatasetView::of).toList()));
    }

    @Operation(summary = "评测集详情")
    @GetMapping("/datasets/{id}")
    public ApiResponse<DatasetView> detail(@PathVariable Long id) {
        return ApiResponse.ok(DatasetView.of(service.require(id)));
    }

    @Operation(summary = "修改评测集", description = "不允许更换所属知识库")
    @PutMapping("/datasets/{id}")
    public ApiResponse<DatasetView> update(
            @PathVariable Long id, @Valid @RequestBody DatasetRequest request) {
        return ApiResponse.ok(DatasetView.of(service.update(id, request)));
    }

    @Operation(summary = "逻辑删除评测集", description = "保留历史运行；存在未完成运行时409")
    @DeleteMapping("/datasets/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "新增评测用例", description = "正向用例需相关文档，无答案用例相关文档必须为空。")
    @PostMapping("/datasets/{id}/cases")
    public ApiResponse<CaseView> createCase(
            @PathVariable Long id, @Valid @RequestBody CaseRequest request) {
        return ApiResponse.ok(CaseView.of(service.createCase(id, request), json));
    }

    @Operation(summary = "分页查询评测用例")
    @GetMapping("/datasets/{id}/cases")
    public ApiResponse<PageResult<CaseView>> cases(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "20") long pageSize) {
        var page = service.listCases(id, pageNo, pageSize);
        return ApiResponse.ok(
                new PageResult<>(
                        page.total(),
                        page.pageNo(),
                        page.pageSize(),
                        page.records().stream().map(item -> CaseView.of(item, json)).toList()));
    }

    @Operation(summary = "用例详情")
    @GetMapping("/cases/{id}")
    public ApiResponse<CaseView> caseDetail(@PathVariable Long id) {
        return ApiResponse.ok(CaseView.of(service.requireCase(id), json));
    }

    @Operation(summary = "修改评测用例", description = "不影响已保存的运行快照")
    @PutMapping("/cases/{id}")
    public ApiResponse<CaseView> updateCase(
            @PathVariable Long id, @Valid @RequestBody CaseRequest request) {
        return ApiResponse.ok(CaseView.of(service.updateCase(id, request), json));
    }

    @Operation(summary = "删除评测用例")
    @DeleteMapping("/cases/{id}")
    public ApiResponse<Void> deleteCase(@PathVariable Long id) {
        service.deleteCase(id);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "导入JSON基线", description = "提交 documentMapping 和 cases，映射缺失或跨知识库整体回滚。")
    @PostMapping("/datasets/import")
    public ApiResponse<DatasetView> importDataset(@Valid @RequestBody ImportRequest request) {
        return ApiResponse.ok(DatasetView.of(service.importDataset(request)));
    }

    @Operation(summary = "异步运行检索评测", description = "202后轮询runId；只调用Embedding，不调用Chat。")
    @PostMapping("/datasets/{id}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<RunView> start(@PathVariable Long id) {
        return ApiResponse.ok(RunView.of(runs.start(id), json));
    }

    @Operation(summary = "分页查询历史运行")
    @GetMapping("/datasets/{id}/runs")
    public ApiResponse<PageResult<RunView>> runs(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "20") long pageSize) {
        var page = runs.list(id, pageNo, pageSize);
        return ApiResponse.ok(
                new PageResult<>(
                        page.total(),
                        page.pageNo(),
                        page.pageSize(),
                        page.records().stream().map(item -> RunView.of(item, json)).toList()));
    }

    @Operation(summary = "运行状态、配置快照与指标")
    @GetMapping("/runs/{id}")
    public ApiResponse<RunView> run(@PathVariable Long id) {
        return ApiResponse.ok(RunView.of(runs.require(id), json));
    }

    @Operation(summary = "逐用例结果与检索快照")
    @GetMapping("/runs/{id}/results")
    public ApiResponse<PageResult<ResultView>> results(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "20") long pageSize) {
        var page = runs.results(id, pageNo, pageSize);
        return ApiResponse.ok(
                new PageResult<>(
                        page.total(),
                        page.pageNo(),
                        page.pageSize(),
                        page.records().stream().map(item -> ResultView.of(item, json)).toList()));
    }
}
