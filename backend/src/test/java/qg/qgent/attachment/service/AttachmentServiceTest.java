package qg.qgent.attachment.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import qg.qgent.attachment.dto.AttachmentCreateRequest;
import qg.qgent.attachment.dto.AttachmentUploadCredentialResponse;
import qg.qgent.attachment.storage.LocalAttachmentStorage;
import org.springframework.http.HttpStatus;

import qg.qgent.attachment.dto.AttachmentPreviewUrlResponse;
import qg.qgent.attachment.entity.AttachmentEntity;
import qg.qgent.attachment.mapper.AttachmentMapper;
import qg.qgent.attachment.service.AttachmentService.AttachmentPreviewContent;
import qg.qgent.attachment.service.AttachmentService.AttachmentPreviewMetadata;
import qg.qgent.attachment.storage.AttachmentContent;
import qg.qgent.attachment.storage.AttachmentStorageStrategy;
import qg.qgent.auth.service.TokenService;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.service.ProjectAccessService;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 附件内联预览服务单测：previewUrl / previewContent / previewType 分类 / 鉴权与状态拒绝。
 * <p>
 * 使用真实 TokenService（测试密钥签发短期 access token）与 Mockito 隔离 Mapper/存储/项目访问。
 */
class AttachmentServiceTest {

    private final AttachmentMapper mapper = mock(AttachmentMapper.class);
    private final AttachmentStorageStrategy storage = mock(AttachmentStorageStrategy.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final TokenService tokens = new TokenService("01234567890123456789012345678901", 15, 30, 30);
    private final AttachmentService service = new AttachmentService(mapper, storage, access, tokens, 52428800, 900);

    @Test
    void previewUrlReturnsSignedTokenUrlAndMetadata() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, "photo.png", "image/png", "READY"));
        when(storage.createDownloadUrl(eq("projects/" + projectId + "/attachments/" + attachmentId), eq(900L)))
                .thenReturn("https://oss.example/photo.png");

        AttachmentPreviewUrlResponse r = service.getPreviewInfo(actor, projectId, attachmentId);

        assertThat(r.getAttachmentId()).isEqualTo(attachmentId.toString());
        assertThat(r.getFileName()).isEqualTo("photo.png");
        assertThat(r.getMediaType()).isEqualTo("image/png");
        assertThat(r.getPreviewType()).isEqualTo("IMAGE");
        assertThat(r.isPreviewable()).isTrue();
        assertThat(r.getDownloadUrl()).isEqualTo("https://oss.example/photo.png");
        assertThat(r.getPreviewUrl()).startsWith("/api/v1/projects/" + projectId + "/attachments/" + attachmentId
                + "/preview?token=");
        String token = r.getPreviewUrl().substring(r.getPreviewUrl().indexOf("token=") + "token=".length());
        assertThat(tokens.verifyAccess(token)).isEqualTo(actor);
        assertThat(r.getExpiresAt()).isNotNull();
    }

    @Test
    void previewUrlKeepsPreviewUrlWhenDownloadUnsupported() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, "doc.pdf", "application/pdf", "READY"));
        doThrow(new UnsupportedOperationException()).when(storage).createDownloadUrl(any(), anyLong());

        AttachmentPreviewUrlResponse r = service.getPreviewInfo(actor, projectId, attachmentId);

        assertThat(r.getDownloadUrl()).isNull();
        assertThat(r.getPreviewUrl()).contains("?token=");
    }

    @Test
    void previewUrlRejectsNotReady() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, "x.txt", "text/plain", "PENDING"));

        assertThatThrownBy(() -> service.getPreviewInfo(UUID.randomUUID(), projectId, attachmentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_READY");
                    assertThat(((ApiException) e).status().value()).isEqualTo(409);
                });
    }

    @Test
    void previewUrlRejectsAttachmentNotInProject() {
        UUID projectId = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, otherProject, "x.txt", "text/plain", "READY"));

        assertThatThrownBy(() -> service.getPreviewInfo(UUID.randomUUID(), projectId, attachmentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    void previewUrlRejectsMissingAttachment() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(null);

        assertThatThrownBy(() -> service.getPreviewInfo(UUID.randomUUID(), projectId, attachmentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    void previewMetadataReturnsTypeAndDeclaredSizeWithoutReadingBytes() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        AttachmentEntity entity = attachment(attachmentId, projectId, "photo.png", "image/png", "READY");
        entity.setSizeBytes(102400L);
        when(mapper.selectById(attachmentId)).thenReturn(entity);

        AttachmentPreviewMetadata meta = service.previewMetadata(actor, projectId, attachmentId);

        assertThat(meta.previewType()).isEqualTo("IMAGE");
        assertThat(meta.sizeBytes()).isEqualTo(102400L);
    }

    @Test
    void previewMetadataRejectsNotReady() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, "x.txt", "text/plain", "PENDING"));

        assertThatThrownBy(() -> service.previewMetadata(UUID.randomUUID(), projectId, attachmentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_READY"));
    }

    @Test
    void previewContentReadsBytesAndClassifiesType() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        byte[] payload = "hello qwen".getBytes(StandardCharsets.UTF_8);
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, "doc.pdf", "application/pdf", "READY"));
        when(storage.loadContent(any(), any(), any())).thenReturn(
                new AttachmentContent(new ByteArrayInputStream(payload), "application/pdf", "doc.pdf", (long) payload.length));

        AttachmentPreviewContent pc = service.previewContent(actor, projectId, attachmentId);

        assertThat(pc.previewType()).isEqualTo("PDF");
        assertThat(pc.bytes()).isEqualTo(payload);
        assertThat(pc.mediaType()).isEqualTo("application/pdf");
        assertThat(pc.fileName()).isEqualTo("doc.pdf");
    }

    @Test
    void previewContentRejectsNotReady() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, "x.txt", "text/plain", "FAILED"));

        assertThatThrownBy(() -> service.previewContent(UUID.randomUUID(), projectId, attachmentId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_READY");
                    assertThat(((ApiException) e).status().value()).isEqualTo(409);
                });
    }

    @Test
    void uploadBytesStoresPendingAttachmentBytes() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        byte[] payload = "hello qwen".getBytes(StandardCharsets.UTF_8);
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, "doc.pdf", "application/pdf", "PENDING"));

        service.uploadBytes(actor, projectId, attachmentId, new ByteArrayInputStream(payload));

        verify(storage).storeBytes(eq("projects/" + projectId + "/attachments/" + attachmentId),
                any(InputStream.class), eq(10L), eq(52428800L));
    }

    @Test
    void uploadBytesRejectsNotPending() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, "x.txt", "text/plain", "READY"));

        assertThatThrownBy(() -> service.uploadBytes(UUID.randomUUID(), projectId, attachmentId,
                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_PENDING");
                    assertThat(((ApiException) e).status().value()).isEqualTo(409);
                });
    }

    @Test
    void uploadBytesRejectsMissingAttachment() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(null);

        assertThatThrownBy(() -> service.uploadBytes(UUID.randomUUID(), projectId, attachmentId,
                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_FOUND"));
    }

    @Test
    void uploadBytesRejectsExceedingMaxSize(@TempDir Path root) {
        AttachmentService small = new AttachmentService(mapper, new LocalAttachmentStorage(root.toString(), 900), access, tokens, 10, 900);
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        byte[] oversized = "more-than-ten-bytes".getBytes(StandardCharsets.UTF_8);
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, "big.txt", "text/plain", "PENDING"));

        assertThatThrownBy(() -> small.uploadBytes(UUID.randomUUID(), projectId, attachmentId,
                new ByteArrayInputStream(oversized)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_TOO_LARGE");
                    assertThat(((ApiException) e).status().value()).isEqualTo(413);
                });
    }

    @Test
    void uploadBytesRejectsNonMember() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问"))
                .when(access).requireProjectMember(projectId, actor);

        assertThatThrownBy(() -> service.uploadBytes(actor, projectId, attachmentId,
                new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("FORBIDDEN"));
    }

    @Test
    void classifiesPreviewTypesByMediaTypeAndExtension() {
        assertPreviewType("photo.png", "image/png", "IMAGE");
        assertPreviewType("doc.pdf", "application/pdf", "PDF");
        assertPreviewType("notes.txt", "text/plain", "TEXT");
        assertPreviewType("config.json", "application/json", "CODE");
        assertPreviewType("Main.java", null, "CODE");
        assertPreviewType("script.sh", "application/x-sh", "CODE");
        assertPreviewType("readme.md", "text/markdown", "TEXT");
        assertPreviewType("data.bin", "application/octet-stream", "UNSUPPORTED");
    }

    @Test
    void localUploadLifecycleRequiresStoredFileBeforeConfirmation(@TempDir Path root) {
        LocalAttachmentStorage local = new LocalAttachmentStorage(root.toString(), 900);
        AttachmentService localService = new AttachmentService(mapper, local, access, tokens, 1024, 900);
        UUID actor = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        AttachmentCreateRequest body = new AttachmentCreateRequest();
        body.setFileName("notes.txt");
        body.setContentType("text/plain");
        body.setSizeBytes(5L);
        when(mapper.insert(any(AttachmentEntity.class))).thenAnswer(invocation -> {
            AttachmentEntity entity = invocation.getArgument(0);
            when(mapper.selectById(entity.getId())).thenReturn(entity);
            return 1;
        });

        AttachmentUploadCredentialResponse credential = localService.createCredential(actor, projectId, body);
        UUID id = UUID.fromString(credential.getAttachmentId());
        assertThat(credential.getUploadUrl()).isEqualTo("/api/v1/projects/" + projectId + "/attachments/" + id);
        assertThatThrownBy(() -> localService.confirmUpload(actor, projectId, id))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_UPLOADED"));

        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        localService.uploadBytes(actor, projectId, id, new ByteArrayInputStream(data));
        assertThat(localService.confirmUpload(actor, projectId, id).getStatus()).isEqualTo("READY");
        assertThat(localService.previewContent(actor, projectId, id).bytes()).isEqualTo(data);
        assertThat(localService.messagePreview(projectId, id.toString()).getPreviewType()).isEqualTo("TEXT");
        assertThat(localService.getPreviewInfo(actor, projectId, id).getDownloadUrl()).isNull();
    }

    private void assertPreviewType(String fileName, String mediaType, String expected) {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        when(mapper.selectById(attachmentId)).thenReturn(attachment(attachmentId, projectId, fileName, mediaType, "READY"));
        doThrow(new UnsupportedOperationException()).when(storage).createDownloadUrl(any(), anyLong());

        AttachmentPreviewUrlResponse r = service.getPreviewInfo(UUID.randomUUID(), projectId, attachmentId);

        assertThat(r.getPreviewType()).as("%s (%s) 应为 %s", fileName, mediaType, expected).isEqualTo(expected);
    }

    @Test
    void confirmationChecksStoredSizeBeforeMarkingReady() {
        UUID project = UUID.randomUUID(), id = UUID.randomUUID(), actor = UUID.randomUUID();
        AttachmentEntity entity = attachment(id, project, "file.txt", "text/plain", "PENDING");
        when(mapper.selectById(id)).thenReturn(entity);
        when(storage.objectSize(entity.getObjectKey())).thenReturn(java.util.OptionalLong.empty());
        assertThatThrownBy(() -> service.confirmUpload(actor, project, id))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_NOT_UPLOADED"));
        when(storage.objectSize(entity.getObjectKey())).thenReturn(java.util.OptionalLong.of(9));
        assertThatThrownBy(() -> service.confirmUpload(actor, project, id))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_SIZE_MISMATCH"));
        when(storage.objectSize(entity.getObjectKey())).thenReturn(java.util.OptionalLong.of(52428801));
        assertThatThrownBy(() -> service.confirmUpload(actor, project, id))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("ATTACHMENT_TOO_LARGE"));
        assertThat(entity.getStatus()).isEqualTo("PENDING");
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never()).updateById(any(AttachmentEntity.class));
        when(storage.objectSize(entity.getObjectKey())).thenReturn(java.util.OptionalLong.of(10));
        assertThat(service.confirmUpload(actor, project, id).getStatus()).isEqualTo("READY");
        assertThat(service.confirmUpload(actor, project, id).getStatus()).isEqualTo("READY");
        verify(mapper).updateById(entity);
    }

    private AttachmentEntity attachment(UUID id, UUID projectId, String fileName, String mediaType, String status) {
        AttachmentEntity value = new AttachmentEntity();
        value.setId(id);
        value.setProjectId(projectId);
        value.setObjectKey("projects/" + projectId + "/attachments/" + id);
        value.setFileName(fileName);
        value.setMediaType(mediaType);
        value.setStatus(status);
        value.setSizeBytes(10L);
        return value;
    }
}
