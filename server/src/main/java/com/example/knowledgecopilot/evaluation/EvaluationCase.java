package com.example.knowledgecopilot.evaluation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("evaluation_case")
public class EvaluationCase {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long datasetId;
    private String question;
    private String expectedAnswer;
    private String relevantDocumentIdsJson;
    private String evidenceJson;
    private Boolean expectedRefusal;
    private String tagsJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
