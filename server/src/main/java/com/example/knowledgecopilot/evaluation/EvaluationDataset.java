package com.example.knowledgecopilot.evaluation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("evaluation_dataset")
public class EvaluationDataset {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long knowledgeBaseId;
    private String name;
    private String description;
    private Long revision;
    private Boolean isDeleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
