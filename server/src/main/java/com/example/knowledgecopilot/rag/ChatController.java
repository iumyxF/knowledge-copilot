package com.example.knowledgecopilot.rag;

import com.example.knowledgecopilot.common.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "4. 问答")
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {
    private final ChatService service;

    public record ChatRequest(
            @NotNull @Positive @Schema(example = "1001") Long knowledgeBaseId,
            @NotBlank @Size(max = 4000) @Schema(example = "购买后多久可以申请退货？") String question) {
    }

    @Operation(summary = "单轮非流式问答", description = "引用为实际上下文来源；无检索结果直接拒答。无会话记忆。")
    @PostMapping
    public ApiResponse<ChatService.Answer> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.ok(service.chat(request.knowledgeBaseId(), request.question()));
    }
}
