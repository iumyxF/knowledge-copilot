package com.example.knowledgecopilot.knowledge.ingestion;

import com.example.knowledgecopilot.common.BusinessException;
import com.example.knowledgecopilot.infrastructure.CopilotProperties;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.TokenCountEstimator;

import lombok.RequiredArgsConstructor;

import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DocumentTextProcessor {
    private final CopilotProperties properties;
    private final TokenCountEstimator estimator;

    public String parse(Path path, String type) {
        try {
            if ("txt".equals(type) || "md".equals(type)) {
                StandardCharsets.UTF_8
                        .newDecoder()
                        .decode(ByteBuffer.wrap(Files.readAllBytes(path)));
            }
            ParseContext context = new ParseContext();
            PDFParserConfig pdf = new PDFParserConfig();
            pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
            context.set(PDFParserConfig.class, pdf);
            TesseractOCRConfig ocr = new TesseractOCRConfig();
            ocr.setSkipOcr(true);
            context.set(TesseractOCRConfig.class, ocr);
            // 每次解析独立上下文与 Handler，避免异步任务共享可变解析状态。
            ApacheTikaDocumentParser parser =
                    new ApacheTikaDocumentParser(
                            new AutoDetectParser(),
                            new BodyContentHandler(5_000_000),
                            new org.apache.tika.metadata.Metadata(),
                            context);
            try (var input = Files.newInputStream(path)) {
                String text = clean(parser.parse(input).text());
                if (text.isBlank()) {
                    throw new BusinessException(422, "NO_TEXT", "未提取到有效文本，不支持扫描件 OCR");
                }
                return text;
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            String code =
                    ex.getClass().getSimpleName().contains("Blank") ? "NO_TEXT" : "PARSE_ERROR";
            throw new BusinessException(422, code, "文件无法解析、编码无效或没有可提取文本");
        }
    }

    public String clean(String text) {
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "")
                .replaceAll("(?m)[ \\t]+$", "")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }

    public List<TextSegment> split(String text) {
        if (properties.getOverlapTokens() >= properties.getChunkTokens()) {
            throw BusinessException.invalid("overlapTokens 必须小于 chunkTokens");
        }
        return DocumentSplitters.recursive(
                        properties.getChunkTokens(), properties.getOverlapTokens(), estimator)
                .split(Document.from(text));
    }
}
