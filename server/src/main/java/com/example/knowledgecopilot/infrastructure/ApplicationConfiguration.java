package com.example.knowledgecopilot.infrastructure;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties(CopilotProperties.class)
public class ApplicationConfiguration {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longIds() {
        return builder -> builder.serializerByType(Long.class, ToStringSerializer.instance);
    }

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Knowledge Copilot V1")
                                .version("v1")
                                .description("文档异步入库、单轮问答与文档级检索评测。202 表示已受理，请轮询状态。"));
    }

    @Bean
    public TokenCountEstimator tokenCountEstimator() {
        return new OpenAiTokenCountEstimator("gpt-4o-mini");
    }

    @Bean("ingestionExecutor")
    public ThreadPoolTaskExecutor ingestionExecutor(CopilotProperties properties) {
        return executor(
                "ingestion-", properties.getIngestionWorkers(), properties.getIngestionQueue());
    }

    @Bean("evaluationExecutor")
    public ThreadPoolTaskExecutor evaluationExecutor(CopilotProperties properties) {
        return executor(
                "evaluation-", properties.getEvaluationWorkers(), properties.getEvaluationQueue());
    }

    private ThreadPoolTaskExecutor executor(String prefix, int workers, int capacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(capacity);
        executor.setThreadNamePrefix(prefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
