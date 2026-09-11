package com.example.knowledgecopilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.knowledgecopilot.common.BusinessException;
import com.example.knowledgecopilot.infrastructure.CopilotProperties;
import com.example.knowledgecopilot.knowledge.ingestion.LocalFileStorage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

class LocalFileStorageTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"product.pdf", "policy.md", "faq.txt", "rules.docx", "operations.doc"})
    void acceptsAllSupportedFileSignatures(String file) throws Exception {
        var properties = new CopilotProperties();
        properties.setStorageRoot(temporary.toString());
        var storage = new LocalFileStorage(properties);
        var saved =
                storage.save(
                        new MockMultipartFile(
                                "file",
                                "../../" + file,
                                "application/octet-stream",
                                Files.readAllBytes(Path.of("samples/documents", file))));
        assertThat(Path.of(saved.path())).startsWith(temporary);
        assertThat(saved.name()).isEqualTo(file);
        assertThat(saved.hash()).hasSize(64);
        storage.delete(saved.path());
        storage.delete(saved.path());
    }

    @Test
    void rejectsOversizedFilesAndDisguisedTypes() {
        var properties = new CopilotProperties();
        properties.setStorageRoot(temporary.toString());
        properties.setMaxFileBytes(4);
        var storage = new LocalFileStorage(properties);
        assertThatThrownBy(
                        () ->
                                storage.save(
                                        new MockMultipartFile(
                                                "file", "a.txt", "text/plain", "12345".getBytes())))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(413);
        assertThatThrownBy(
                        () ->
                                storage.save(
                                        new MockMultipartFile(
                                                "file",
                                                "a.pdf",
                                                "application/pdf",
                                                "text".getBytes())))
                .isInstanceOf(BusinessException.class)
                .extracting("status")
                .isEqualTo(415);
    }
}
