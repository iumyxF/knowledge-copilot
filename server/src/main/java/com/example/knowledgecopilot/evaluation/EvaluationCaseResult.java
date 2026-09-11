package com.example.knowledgecopilot.evaluation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("evaluation_case_result")
public class EvaluationCaseResult {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long runId;
    private Long caseId;
    private String caseSnapshotJson;
    private String status;
    private String retrievedChunksJson;
    private String rankedDocumentsJson;
    private String metricsJson;
    private Long latencyMs;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
