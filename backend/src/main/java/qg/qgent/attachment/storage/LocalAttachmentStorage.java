package qg.qgent.attachment.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import qg.qgent.attachment.dto.UploadCredential;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/** 未启用 OSS 时，将附件存入本地磁盘。 */
@Component
@ConditionalOnProperty(prefix = "aliyun.oss", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalAttachmentStorage implements AttachmentStorageStrategy {

    private static final String OBJECT_KEY_PATTERN = "projects/[0-9a-fA-F-]{36}/attachments/[0-9a-fA-F-]{36}";

    private final Path root;
    private final Duration expiry;

    public LocalAttachmentStorage(
            @Value("${app.attachment-local-root:./data/attachments}") String root,
            @Value("${app.attachment-upload-expiry-seconds:900}") long expirySeconds) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.expiry = Duration.ofSeconds(expirySeconds);
    }

    @Override
    public UploadCredential createCredential(String objectKey, String mediaType) {
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plus(expiry);
        // 代理上传：uploadUrl 指向后端代理端点（相对路径，前端按 API base origin 解析）。
        Map<String, String> headers = mediaType == null || mediaType.isBlank()
                ? Map.of()
                : Map.of("Content-Type", mediaType);
        return new UploadCredential("/api/v1/" + objectKey, "PUT", headers, expiresAt);
    }

    @Override
    public void storeBytes(String objectKey, InputStream bytes, String contentType) {
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = bytes) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to store attachment locally: " + objectKey, e);
        }
    }

    @Override
    public boolean objectExists(String objectKey) {
        return Files.exists(resolve(objectKey));
    }

    @Override
    public AttachmentContent loadContent(String objectKey, String fileName, String contentType) {
        Path target = resolve(objectKey);
        String resolvedType = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        try {
            long size = Files.size(target);
            return new AttachmentContent(Files.newInputStream(target), resolvedType, fileName, size);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read attachment from local storage: " + objectKey, e);
        }
    }

    private Path resolve(String objectKey) {
        if (objectKey == null || !objectKey.matches(OBJECT_KEY_PATTERN)) {
            throw new IllegalArgumentException("Invalid attachment object key");
        }
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Attachment path escapes configured root");
        }
        return target;
    }
}
