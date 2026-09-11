package com.example.knowledgecopilot.knowledge.base;

import com.example.knowledgecopilot.common.ApiResponse;
import com.example.knowledgecopilot.common.PageResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "1. 知识库")
@RestController
@RequestMapping("/api/v1/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {
    private final KnowledgeBaseService service;

    public record BaseRequest(
            @NotBlank @Size(max = 100) String name, @Size(max = 1000) String description) {
    }

    public record BaseView(Long id, String name, String description, long corpusRevision) {
        static BaseView of(KnowledgeBase base) {
            return new BaseView(
                    base.getId(), base.getName(), base.getDescription(), base.getCorpusRevision());
        }
    }

    @Operation(summary = "创建知识库")
    @PostMapping
    public ApiResponse<BaseView> create(@Valid @RequestBody BaseRequest request) {
        return ApiResponse.ok(BaseView.of(service.create(request.name(), request.description())));
    }

    @Operation(summary = "分页查询知识库")
    @GetMapping
    public ApiResponse<PageResult<BaseView>> list(
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "20") long pageSize) {
        var page = service.list(pageNo, pageSize);
        return ApiResponse.ok(
                new PageResult<>(
                        page.total(),
                        page.pageNo(),
                        page.pageSize(),
                        page.records().stream().map(BaseView::of).toList()));
    }

    @Operation(summary = "查询知识库详情")
    @GetMapping("/{id}")
    public ApiResponse<BaseView> detail(@PathVariable Long id) {
        return ApiResponse.ok(BaseView.of(service.require(id)));
    }

    @Operation(summary = "修改知识库名称和描述")
    @PutMapping("/{id}")
    public ApiResponse<BaseView> update(
            @PathVariable Long id, @Valid @RequestBody BaseRequest request) {
        return ApiResponse.ok(
                BaseView.of(service.update(id, request.name(), request.description())));
    }

    @Operation(summary = "删除空知识库", description = "存在未清理文档或未删除评测集时返回 409。")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }
}
