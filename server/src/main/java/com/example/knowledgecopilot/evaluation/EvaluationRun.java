package com.example.knowledgecopilot.evaluation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("evaluation_run")
public class EvaluationRun {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long datasetId;
    private String status;
    private String caseSnapshotJson;
    private String configSnapshotJson;
    private String corpusManifestJson;
    private Long datasetRevision;
    private Long corpusRevision;
    private String metricsJson;
    private Integer totalCases;
    private Integer completedCases;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
