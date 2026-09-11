package com.example.knowledgecopilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.knowledgecopilot.common.BusinessException;
import com.example.knowledgecopilot.infrastructure.ModelConfiguration;
import com.example.knowledgecopilot.infrastructure.StartupRecovery;
import com.example.knowledgecopilot.infrastructure.VectorIndex;
import com.example.knowledgecopilot.knowledge.base.KnowledgeBaseService;
import com.example.knowledgecopilot.knowledge.chunk.DocumentChunk;
import com.example.knowledgecopilot.knowledge.chunk.DocumentChunkMapper;
import com.example.knowledgecopilot.knowledge.document.DocumentService;
import com.example.knowledgecopilot.knowledge.document.KnowledgeDocument;
import com.example.knowledgecopilot.knowledge.document.KnowledgeDocumentMapper;
import com.example.knowledgecopilot.knowledge.ingestion.IngestionService;
import com.example.knowledgecopilot.rag.RetrievalService;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@ActiveProfiles("test")
class PipelineLifecycleTest {
    @Autowired DocumentService documents;
    @Autowired KnowledgeDocumentMapper documentMapper;
    @Autowired KnowledgeBaseService bases;
    @Autowired DocumentChunkMapper chunks;
    @Autowired IngestionService ingestion;
    @Autowired StartupRecovery recovery;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ModelConfiguration models;
    @MockitoBean VectorIndex index;

    @MockitoBean(name = "ingestionExecutor")
    ThreadPoolTaskExecutor executor;

    @MockitoBean(name = "evaluationExecutor")
    ThreadPoolTaskExecutor evaluationExecutor;

    private Long baseId;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM evaluation_case_result");
        jdbc.update("DELETE FROM evaluation_run");
        jdbc.update("DELETE FROM evaluation_case");
        jdbc.update("DELETE FROM evaluation_dataset");
        jdbc.update("DELETE FROM document_chunk");
        jdbc.update("DELETE FROM knowledge_document");
        jdbc.update("DELETE FROM knowledge_base");
        baseId = bases.create("流程测试", null).getId();
        when(models.fingerprint()).thenReturn("fixed-test");
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        when(models.embedding()).thenReturn(embedding);
        when(embedding.embedAll(anyList()))
                .thenAnswer(
                        call ->
                                Response.from(
                                        ((List<?>) call.getArgument(0))
                                                .stream()
                                                        .map(
                                                                item ->
                                                                        Embedding.from(
                                                                                new float[] {
                                                                                    1, 0, 0
                                                                                }))
                                                        .toList()));
    }

    private KnowledgeDocument upload() {
        return documents.upload(
                baseId,
                new MockMultipartFile(
                        "file",
                        "policy.txt",
                        "text/plain",
                        "星河设备购买后七天内可以申请退货。".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void ingestsOnceAndDeletesIdempotently() {
        var document = upload();
        ingestion.process(document.getId(), 1);
        ingestion.process(document.getId(), 1);
        assertThat(documents.require(document.getId()).getStatus()).isEqualTo("AVAILABLE");
        verify(index, times(1)).add(anyList(), anyList(), anyList());
        assertThat(bases.require(baseId).getCorpusRevision()).isEqualTo(1);
        documents.delete(document.getId());
        documents.delete(document.getId());
        assertThat(documents.available(baseId)).isEmpty();
        assertThat(chunks.selectCount(new LambdaQueryWrapper<DocumentChunk>())).isZero();
        assertThat(bases.require(baseId).getCorpusRevision()).isEqualTo(2);
    }

    @Test
    void partialIndexFailureRemainsInvisibleAndRetryUsesNewBatch() {
        var document = upload();
        doThrow(new BusinessException(503, "INDEX_INCOMPLETE", "失败"))
                .doNothing()
                .when(index)
                .verifyVisible(anyLong(), anyInt(), anyInt());
        ingestion.process(document.getId(), 1);
        assertThat(documents.require(document.getId()).getStatus()).isEqualTo("INDEX_FAILED");
        assertThat(documents.available(baseId)).isEmpty();
        documents.retry(document.getId());
        assertThatThrownBy(() -> documents.retry(document.getId()))
                .isInstanceOf(BusinessException.class);
        ingestion.process(document.getId(), 2);
        assertThat(documents.require(document.getId()).getStatus()).isEqualTo("AVAILABLE");
        assertThat(chunks.selectList(new LambdaQueryWrapper<DocumentChunk>()))
                .allSatisfy(chunk -> assertThat(chunk.getAttemptNo()).isEqualTo(2));
    }

    @Test
    void queueRejectionAndRestartAllowExplicitRetry() {
        doThrow(new TaskRejectedException("full")).when(executor).execute(any(Runnable.class));
        var rejected = upload();
        assertThat(rejected.getStatus()).isEqualTo("PROCESS_FAILED");
        assertThat(rejected.getErrorCode()).isEqualTo("TASK_QUEUE_FULL");
        doNothing().when(executor).execute(any(Runnable.class));
        documents.retry(rejected.getId());
        recovery.run(new DefaultApplicationArguments());
        assertThat(documents.require(rejected.getId()).getErrorCode())
                .isEqualTo("PROCESS_INTERRUPTED");
        assertThat(documents.retry(rejected.getId()).getAttemptNo()).isEqualTo(3);
    }

    @Test
    void concurrentRetriesOnlyOneClaimsDocument() throws Exception {
        var document = upload();
        recovery.run(new DefaultApplicationArguments());
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> retry =
                    () -> {
                        ready.countDown();
                        start.await();
                        try {
                            documents.retry(document.getId());
                            return true;
                        } catch (BusinessException ex) {
                            return false;
                        }
                    };
            Future<Boolean> first = threads.submit(retry);
            Future<Boolean> second = threads.submit(retry);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void deleteFailureRemainsInvisibleAndCanBeRetried() {
        var document = upload();
        ingestion.process(document.getId(), 1);
        doThrow(new BusinessException(503, "ES_FAILED", "失败"))
                .when(index)
                .removeDocument(document.getId());
        assertThatThrownBy(() -> documents.delete(document.getId()))
                .isInstanceOf(BusinessException.class);
        assertThat(documents.require(document.getId()).getStatus()).isEqualTo("DELETE_FAILED");
        assertThat(documents.available(baseId)).isEmpty();
        doNothing().when(index).removeDocument(document.getId());
        documents.delete(document.getId());
        assertThat(documents.require(document.getId()).getStatus()).isEqualTo("DELETED");
    }

    @Test
    void filteringChecksKnowledgeBaseAndAttempt() {
        var document = upload();
        var filter = RetrievalService.filter(baseId, List.of(document));
        var valid =
                new dev.langchain4j.data.document.Metadata()
                        .put("knowledgeBaseId", baseId)
                        .put("documentId", document.getId())
                        .put("attemptNo", 1);
        assertThat(filter.test(valid)).isTrue();
        assertThat(filter.test(valid.copy().put("knowledgeBaseId", baseId + 1))).isFalse();
        assertThat(filter.test(valid.copy().put("attemptNo", 2))).isFalse();
    }
}
