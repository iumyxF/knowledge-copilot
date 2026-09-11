package com.example.knowledgecopilot.knowledge.document;

import com.example.knowledgecopilot.common.ApiResponse;
import com.example.knowledgecopilot.common.PageResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "2. 文档")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DocumentController {
    private final DocumentService service;

    @Operation(summary = "上传文档", description = "PDF/DOC/DOCX/TXT/MD，默认20MB。202仅表示受理，请查询状态。")
    @PostMapping(
            value = "/knowledge-bases/{id}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<DocumentView> upload(
            @PathVariable Long id, @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(DocumentView.of(service.upload(id, file)));
    }

    @Operation(summary = "分页查询文档")
    @GetMapping("/knowledge-bases/{id}/documents")
    public ApiResponse<PageResult<DocumentView>> list(
            @PathVariable Long id,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "20") long pageSize) {
        var page = service.list(id, status, pageNo, pageSize);
        return ApiResponse.ok(
                new PageResult<>(
                        page.total(),
                        page.pageNo(),
                        page.pageSize(),
                        page.records().stream().map(DocumentView::of).toList()));
    }

    @Operation(summary = "文档详情或处理状态", description = "AVAILABLE才能问答；errorCode说明失败阶段。")
    @GetMapping({"/documents/{id}", "/documents/{id}/status"})
    public ApiResponse<DocumentView> detail(@PathVariable Long id) {
        return ApiResponse.ok(DocumentView.of(service.require(id)));
    }

    @Operation(summary = "重试失败入库", description = "仅入库失败状态允许，其他状态返回409。")
    @PostMapping("/documents/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<DocumentView> retry(@PathVariable Long id) {
        return ApiResponse.ok(DocumentView.of(service.retry(id)));
    }

    @Operation(summary = "删除文档", description = "处理中返回409；DELETE_FAILED可重复调用，DELETED幂等成功。")
    @DeleteMapping("/documents/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }
}
