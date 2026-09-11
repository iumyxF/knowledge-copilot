package com.example.knowledgecopilot.knowledge.document;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_document")
public class KnowledgeDocument {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long knowledgeBaseId;
    private String originalName;
    private String storagePath;
    private String fileType;
    private Long fileSize;
    private String sha256;
    private String status;
    private Integer attemptNo;
    private Integer chunkCount;
    private String pipelineConfigJson;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
