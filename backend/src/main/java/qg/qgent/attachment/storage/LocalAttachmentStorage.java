package qg.qgent.attachment.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import qg.qgent.attachment.dto.UploadCredential;
import qg.qgent.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.OptionalLong;
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
    public void storeBytes(String objectKey, InputStream bytes, long expectedSize, long maxSize) {
        Path target = resolve(objectKey);
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try (InputStream in = bytes; var out = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > maxSize) {
                        throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_TOO_LARGE", "文件大小超过上限 " + maxSize + " 字节");
                    }
                    out.write(buffer, 0, count);
                }
                if (total != expectedSize) {
                    throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_SIZE_MISMATCH", "实际文件大小与申请上传时不一致");
                }
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to store attachment locally: " + objectKey, e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    throw new IllegalStateException("Unable to remove temporary upload", e);
                }
            }
        }
    }

    @Override
    public OptionalLong objectSize(String objectKey) {
        Path path = resolve(objectKey);
        try {
            return OptionalLong.of(Files.size(path));
        } catch (java.nio.file.NoSuchFileException e) {
            return OptionalLong.empty();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read attachment size: " + objectKey, e);
        }
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
