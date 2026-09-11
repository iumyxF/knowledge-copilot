package com.example.knowledgecopilot.infrastructure;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.knowledgecopilot.evaluation.EvaluationRun;
import com.example.knowledgecopilot.evaluation.EvaluationRunMapper;
import com.example.knowledgecopilot.knowledge.document.DocumentStatus;
import com.example.knowledgecopilot.knowledge.document.KnowledgeDocument;
import com.example.knowledgecopilot.knowledge.document.KnowledgeDocumentMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class StartupRecovery implements ApplicationRunner {
    private final KnowledgeDocumentMapper documents;
    private final EvaluationRunMapper runs;

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        documents.update(
                null,
                new LambdaUpdateWrapper<KnowledgeDocument>()
                        .in(KnowledgeDocument::getStatus, DocumentStatus.PROCESSING)
                        .set(KnowledgeDocument::getStatus, "PROCESS_FAILED")
                        .set(KnowledgeDocument::getErrorCode, "PROCESS_INTERRUPTED")
                        .set(KnowledgeDocument::getErrorMessage, "应用重启导致任务中断，请重试")
                        .set(KnowledgeDocument::getUpdatedAt, LocalDateTime.now()));
        documents.update(
                null,
                new LambdaUpdateWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getStatus, "DELETING")
                        .set(KnowledgeDocument::getStatus, "DELETE_FAILED")
                        .set(KnowledgeDocument::getErrorCode, "PROCESS_INTERRUPTED")
                        .set(KnowledgeDocument::getErrorMessage, "删除中断，请再次删除")
                        .set(KnowledgeDocument::getUpdatedAt, LocalDateTime.now()));
        runs.update(
                null,
                new LambdaUpdateWrapper<EvaluationRun>()
                        .in(EvaluationRun::getStatus, List.of("QUEUED", "RUNNING"))
                        .set(EvaluationRun::getStatus, "FAILED")
                        .set(EvaluationRun::getErrorCode, "PROCESS_INTERRUPTED")
                        .set(EvaluationRun::getErrorMessage, "应用重启导致评测中断，请新建运行")
                        .set(EvaluationRun::getFinishedAt, LocalDateTime.now()));
    }
}
