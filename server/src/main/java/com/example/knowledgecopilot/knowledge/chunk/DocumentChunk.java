package com.example.knowledgecopilot.knowledge.chunk;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("document_chunk")
public class DocumentChunk {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long knowledgeBaseId;
    private Long documentId;
    private Integer attemptNo;
    private Integer chunkIndex;
    private String content;
    private Integer tokenCount;
    private Integer page;
    private String section;
    private String embeddingId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
