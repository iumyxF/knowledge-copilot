package com.example.knowledgecopilot.knowledge.chunk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.knowledgecopilot.common.ApiResponse;
import com.example.knowledgecopilot.common.BusinessException;
import com.example.knowledgecopilot.common.PageResult;
import com.example.knowledgecopilot.knowledge.document.DocumentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "3. 切片")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ChunkController {
    private final DocumentChunkMapper mapper;
    private final DocumentService documents;

    public record ChunkView(
            Long id,
            Long documentId,
            int attemptNo,
            int chunkIndex,
            String content,
            int tokenCount,
            Integer page,
            String section) {
        static ChunkView of(DocumentChunk chunk) {
            return new ChunkView(
                    chunk.getId(),
                    chunk.getDocumentId(),
                    chunk.getAttemptNo(),
                    chunk.getChunkIndex(),
                    chunk.getContent(),
                    chunk.getTokenCount(),
                    chunk.getPage(),
                    chunk.getSection());
        }
    }

    @Operation(summary = "当前处理批次切片分页")
    @GetMapping("/documents/{id}/chunks")
    public ApiResponse<PageResult<ChunkView>> list(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "20") long pageSize) {
        var document = documents.require(id);
        var page =
                mapper.selectPage(
                        PageResult.<DocumentChunk>page(pageNo, pageSize),
                        new LambdaQueryWrapper<DocumentChunk>()
                                .eq(DocumentChunk::getDocumentId, id)
                                .eq(DocumentChunk::getAttemptNo, document.getAttemptNo())
                                .orderByAsc(DocumentChunk::getChunkIndex));
        return ApiResponse.ok(
                new PageResult<>(
                        page.getTotal(),
                        page.getCurrent(),
                        page.getSize(),
                        page.getRecords().stream().map(ChunkView::of).toList()));
    }

    @Operation(summary = "切片正文和元数据")
    @GetMapping("/chunks/{id}")
    public ApiResponse<ChunkView> detail(@PathVariable Long id) {
        var chunk = mapper.selectById(id);
        if (chunk == null) {
            throw BusinessException.notFound();
        }
        var document = documents.require(chunk.getDocumentId());
        if (!document.getAttemptNo().equals(chunk.getAttemptNo())
                || "DELETED".equals(document.getStatus())) {
            throw BusinessException.notFound();
        }
        return ApiResponse.ok(ChunkView.of(chunk));
    }
}
