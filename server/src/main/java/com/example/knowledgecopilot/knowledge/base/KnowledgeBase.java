package com.example.knowledgecopilot.knowledge.base;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_base")
public class KnowledgeBase {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String name;
    private String description;
    private Long corpusRevision;
    private Boolean isDeleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
