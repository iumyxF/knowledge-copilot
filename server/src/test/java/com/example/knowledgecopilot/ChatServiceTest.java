package com.example.knowledgecopilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.knowledgecopilot.common.BusinessException;
import com.example.knowledgecopilot.infrastructure.CopilotProperties;
import com.example.knowledgecopilot.infrastructure.ModelConfiguration;
import com.example.knowledgecopilot.rag.AnswerGenerator;
import com.example.knowledgecopilot.rag.ChatService;
import com.example.knowledgecopilot.rag.RetrievalService;

import dev.langchain4j.model.TokenCountEstimator;

import org.junit.jupiter.api.Test;

import java.util.List;

class ChatServiceTest {
    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final AnswerGenerator generator = mock(AnswerGenerator.class);
    private final ModelConfiguration models = mock(ModelConfiguration.class);
    private final TokenCountEstimator estimator = mock(TokenCountEstimator.class);
    private final CopilotProperties properties = new CopilotProperties();
    private final ChatService service =
            new ChatService(retrieval, generator, models, properties, estimator);

    @Test
    void noRetrievalNeverCallsChatModel() {
        when(retrieval.retrieve(1L, "问题", 5)).thenReturn(List.of());
        var answer = service.chat(1L, "问题");
        assertThat(answer.refused()).isTrue();
        assertThat(answer.citations()).isEmpty();
        verifyNoInteractions(generator);
    }

    @Test
    void onlyActualContextIsCitedAndRetrievedOnce() {
        var first = new RetrievalService.Hit(2L, "政策", 3L, 1, "可退货", 3, null, null);
        var second = new RetrievalService.Hit(4L, "长文档", 5L, 1, "超预算", 9999, null, null);
        when(retrieval.retrieve(1L, "问题", 5)).thenReturn(List.of(first, second));
        when(estimator.estimateTokenCountInText(anyString()))
                .thenAnswer(call -> ((String) call.getArgument(0)).contains("超预算") ? 9999 : 10);
        when(generator.generate(anyString(), anyString())).thenReturn("可以退货");
        var answer = service.chat(1L, "问题");
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.citations().getFirst().chunkId()).isEqualTo(3L);
        verify(generator).generate(eq("问题"), contains("可退货"));
        verify(retrieval).retrieve(1L, "问题", 5);
        verify(retrieval, times(2)).verifyAvailable(List.of(first));
    }

    @Test
    void modelFailureIsNotRefusal() {
        var hit = new RetrievalService.Hit(2L, "政策", 3L, 1, "可退货", 3, null, null);
        when(retrieval.retrieve(1L, "问题", 5)).thenReturn(List.of(hit));
        when(generator.generate(anyString(), anyString()))
                .thenThrow(new BusinessException(504, "MODEL_TIMEOUT", "超时"));
        assertThatThrownBy(() -> service.chat(1L, "问题"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("MODEL_TIMEOUT");
    }
}
