package qg.qgent.attachment.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import qg.qgent.attachment.dto.UploadCredential;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 本地磁盘附件存储单测：凭证代理路径、字节落盘/回读/存在性、对象键路径逃逸防护。 */
class LocalAttachmentStorageTest {

    @TempDir
    Path root;

    private LocalAttachmentStorage storage() {
        return new LocalAttachmentStorage(root.toString(), 900);
    }

    @Test
    void credentialPointsToProxyUploadPath() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        String objectKey = "projects/" + projectId + "/attachments/" + attachmentId;

        UploadCredential credential = storage().createCredential(objectKey, "image/png");

        assertThat(credential.getUploadUrl()).isEqualTo("/api/v1/" + objectKey);
        assertThat(credential.getMethod()).isEqualTo("PUT");
        assertThat(credential.getHeaders()).containsEntry("Content-Type", "image/png");
        assertThat(credential.getExpiresAt()).isNotNull();
    }

    @Test
    void storesAndLoadsBytesAndChecksExistence() throws Exception {
        LocalAttachmentStorage storage = storage();
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        String objectKey = "projects/" + projectId + "/attachments/" + attachmentId;
        byte[] payload = "hello qwen".getBytes(StandardCharsets.UTF_8);

        assertThat(storage.objectSize(objectKey)).isEmpty();

        storage.storeBytes(objectKey, new ByteArrayInputStream(payload), payload.length, 1024);

        assertThat(storage.objectSize(objectKey)).hasValue(payload.length);
        AttachmentContent content = storage.loadContent(objectKey, "notes.txt", "text/plain");
        assertThat(content.getFileName()).isEqualTo("notes.txt");
        assertThat(content.getContentType()).isEqualTo("text/plain");
        try (var stream = content.getStream()) {
            assertThat(stream.readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    void rejectsPathTraversalObjectKey() {
        LocalAttachmentStorage storage = storage();
        assertThatThrownBy(() -> storage.storeBytes("../evil.txt", new ByteArrayInputStream(new byte[0]), 0, 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.loadContent("../../etc/passwd", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsOnlyTheRequestedFileRegion() throws Exception {
        LocalAttachmentStorage storage = storage();
        String key = "projects/" + UUID.randomUUID() + "/attachments/" + UUID.randomUUID();
        byte[] payload = "0123456789".getBytes(StandardCharsets.UTF_8);
        storage.storeBytes(key, new ByteArrayInputStream(payload), payload.length, 1024);
        try (var stream = storage.loadRange(key, 6, 8)) {
            assertThat(stream.read()).isEqualTo('6');
            assertThat(stream.readAllBytes()).isEqualTo("78".getBytes(StandardCharsets.UTF_8));
            assertThat(stream.read()).isEqualTo(-1);
        }
        try (var stream = storage.loadRange(key, 0, 0)) {
            assertThat(stream.readAllBytes()).containsExactly((byte) '0');
        }
    }

    @Test
    void failedUploadsNeverPublishPartialFiles() throws Exception {
        LocalAttachmentStorage storage = storage();
        String key = "projects/" + UUID.randomUUID() + "/attachments/" + UUID.randomUUID();
        assertThatThrownBy(() -> storage.storeBytes(key, new ByteArrayInputStream(new byte[5]), 6, 10))
                .isInstanceOf(qg.qgent.common.web.ApiException.class);
        assertThat(storage.objectSize(key)).isEmpty();
        assertThatThrownBy(() -> storage.storeBytes(key, new ByteArrayInputStream(new byte[11]), 11, 10))
                .isInstanceOf(qg.qgent.common.web.ApiException.class);
        assertThat(storage.objectSize(key)).isEmpty();
        java.io.InputStream broken = new java.io.InputStream() {
            @Override public int read() throws java.io.IOException { throw new java.io.IOException("interrupted"); }
        };
        assertThatThrownBy(() -> storage.storeBytes(key, broken, 6, 10)).isInstanceOf(IllegalStateException.class);
        assertThat(storage.objectSize(key)).isEmpty();
        try (var files = java.nio.file.Files.walk(root)) {
            assertThat(files.filter(java.nio.file.Files::isRegularFile).toList()).isEmpty();
        }
    }
}
