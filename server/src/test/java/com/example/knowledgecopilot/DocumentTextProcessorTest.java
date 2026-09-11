package com.example.knowledgecopilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.knowledgecopilot.common.BusinessException;
import com.example.knowledgecopilot.infrastructure.CopilotProperties;
import com.example.knowledgecopilot.knowledge.ingestion.DocumentTextProcessor;

import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;

class DocumentTextProcessorTest {
    @TempDir Path temporary;
    private final CopilotProperties properties = new CopilotProperties();
    private final DocumentTextProcessor processor =
            new DocumentTextProcessor(properties, new OpenAiTokenCountEstimator("gpt-4o-mini"));

    @ParameterizedTest
    @CsvSource({
        "product.pdf,pdf",
        "policy.md,md",
        "faq.txt,txt",
        "rules.docx,docx",
        "operations.doc,doc"
    })
    void parsesAllFiveFormats(String file, String type) {
        String text = processor.parse(Path.of("samples/documents", file), type);
        assertThat(text).contains("星河");
        assertThat(processor.split(text))
                .isNotEmpty()
                .allSatisfy(segment -> assertThat(segment.text()).isNotBlank());
    }

    @Test
    void rejectsImageOnlyOrEmptyPdfAndCorruptDocument() throws Exception {
        Path blank = temporary.resolve("blank.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(blank.toFile());
        }
        assertThatThrownBy(() -> processor.parse(blank, "pdf"))
                .isInstanceOf(BusinessException.class);
        Path corrupt = temporary.resolve("corrupt.docx");
        Files.write(corrupt, new byte[] {0, 1, 2, 3});
        assertThatThrownBy(() -> processor.parse(corrupt, "docx"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void preservesIndentationAndValidatesUtf8() throws Exception {
        assertThat(processor.clean("# 标题\r\n    code  \r\n\r\n\r\n结束"))
                .isEqualTo("# 标题\n    code\n\n结束");
        Path invalid = temporary.resolve("invalid.txt");
        Files.write(invalid, new byte[] {(byte) 0xff, (byte) 0xfe, 0});
        assertThatThrownBy(() -> processor.parse(invalid, "txt"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void splittingRespectsTokenBudget() {
        properties.setChunkTokens(30);
        properties.setOverlapTokens(5);
        var estimator = new OpenAiTokenCountEstimator("gpt-4o-mini");
        var chunks = processor.split("设备在购买后七天内可以申请退货。".repeat(100));
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks)
                .allSatisfy(
                        chunk ->
                                assertThat(estimator.estimateTokenCountInText(chunk.text()))
                                        .isLessThanOrEqualTo(30));
    }

    @Test
    void bundledInvalidPdfsFailWithoutOcr() {
        assertThatThrownBy(() -> processor.parse(Path.of("samples/invalid/image-only.pdf"), "pdf"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> processor.parse(Path.of("samples/invalid/corrupt.pdf"), "pdf"))
                .isInstanceOf(BusinessException.class);
    }
}
