package com.example.knowledgecopilot.knowledge.ingestion;

import com.example.knowledgecopilot.common.BusinessException;
import com.example.knowledgecopilot.infrastructure.CopilotProperties;

import lombok.RequiredArgsConstructor;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class LocalFileStorage {
    private static final Map<String, Set<String>> TYPES =
            Map.of(
                    "pdf", Set.of("application/pdf"),
                    "doc", Set.of("application/msword", "application/x-tika-msoffice"),
                    "docx",
                    Set.of(
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                    "txt", Set.of("text/plain"),
                    "md", Set.of("text/plain", "text/markdown", "text/x-markdown"));
    private final CopilotProperties properties;

    public record StoredFile(String path, String name, String type, long size, String hash) {
    }

    public StoredFile save(MultipartFile file) {
        if (file.isEmpty()) {
            throw BusinessException.invalid("文件不能为空");
        }
        if (file.getSize() > properties.getMaxFileBytes()) {
            throw new BusinessException(413, "FILE_TOO_LARGE", "文件超过大小限制");
        }
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("file");
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.length() > 255 || !name.contains(".")) {
            throw BusinessException.invalid("文件名称无效");
        }
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!TYPES.containsKey(extension)) {
            throw new BusinessException(415, "UNSUPPORTED_FILE", "仅支持 PDF、DOC、DOCX、TXT、Markdown");
        }
        Path target = root().resolve(UUID.randomUUID() + "." + extension);
        Path temporary = root().resolve(UUID.randomUUID() + ".upload");
        try {
            Files.createDirectories(root());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (InputStream input = file.getInputStream();
                 var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    size += count;
                    if (size > properties.getMaxFileBytes()) {
                        throw new BusinessException(413, "FILE_TOO_LARGE", "文件超过大小限制");
                    }
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            try (InputStream input = Files.newInputStream(temporary)) {
                String type = new Tika().detect(input);
                if (!TYPES.get(extension).contains(type)) {
                    throw new BusinessException(415, "FILE_TYPE_MISMATCH", "文件实际内容与扩展名不一致");
                }
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            return new StoredFile(
                    target.toString(),
                    name,
                    extension,
                    size,
                    HexFormat.of().formatHex(digest.digest()));
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(503, "FILE_STORAGE_FAILED", "文件保存失败");
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (Exception ignored) {
                // 崩溃或文件占用残留由运维说明中的孤立文件检查处理。
            }
        }
    }

    public Path path(String storedPath) {
        Path path = Path.of(storedPath).toAbsolutePath().normalize();
        if (!path.startsWith(root()) || path.equals(root())) {
            throw new IllegalStateException("文件路径不在配置存储目录中");
        }
        return path;
    }

    public void delete(String storedPath) {
        try {
            Files.deleteIfExists(path(storedPath));
        } catch (Exception ex) {
            throw new BusinessException(503, "FILE_DELETE_FAILED", "原始文件清理失败");
        }
    }

    private Path root() {
        return Path.of(properties.getStorageRoot()).toAbsolutePath().normalize();
    }
}
