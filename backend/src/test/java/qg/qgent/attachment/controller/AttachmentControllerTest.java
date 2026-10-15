package qg.qgent.attachment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import qg.qgent.attachment.dto.AttachmentPreviewUrlResponse;
import qg.qgent.attachment.service.AttachmentService;
import qg.qgent.attachment.service.AttachmentService.AttachmentPreviewContent;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 附件内联预览控制器单测：经过鉴权的预览响应、Range 206/416、raw 语义、preview-url 包装。
 * <p>
 * 直接调用控制器方法（与项目既有控制器测试一致），用真实 TokenService 签发短期 access token。
 */
class AttachmentControllerTest {

    private final AttachmentService service = mock(AttachmentService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AttachmentController controller = new AttachmentController(service, objectMapper);
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        request = mock(HttpServletRequest.class);
        when(request.getAttribute(RequestIdFilter.ATTRIBUTE)).thenReturn("req-1");
    }

    @Test
    void previewServesRangeWithQueryToken() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        byte[] data = "hello qwen".getBytes(StandardCharsets.UTF_8);
        when(service.previewContent(actor, projectId, attachmentId)).thenReturn(
                new AttachmentPreviewContent(data, "TEXT", "notes.txt", "text/plain", (long) data.length));

        ResponseEntity<byte[]> resp = controller.preview(projectId, attachmentId,
                null, "bytes=0-4", actor, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 0-4/" + data.length);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(resp.getHeaders().getContentType().toString()).startsWith("text/plain");
        assertThat(resp.getBody()).isEqualTo(Arrays.copyOfRange(data, 0, 5));
    }

    @Test
    void previewSupportsSuffixRange() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        when(service.previewContent(actor, projectId, attachmentId)).thenReturn(
                new AttachmentPreviewContent(data, "TEXT", "notes.txt", "text/plain", (long) data.length));

        ResponseEntity<byte[]> resp = controller.preview(projectId, attachmentId,
                null, "bytes=-3", actor, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 2-4/" + data.length);
        assertThat(resp.getBody()).isEqualTo("llo".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void previewReturns416ForUnsatisfiableRange() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        when(service.previewContent(actor, projectId, attachmentId)).thenReturn(
                new AttachmentPreviewContent(data, "TEXT", "notes.txt", "text/plain", (long) data.length));

        ResponseEntity<byte[]> resp = controller.preview(projectId, attachmentId,
                null, "bytes=99-100", actor, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */" + data.length);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(resp.getBody()).isNotNull();
    }

    @Test
    void previewServesFullContentViaHeaderChannel() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        when(service.previewContent(actor, projectId, attachmentId)).thenReturn(
                new AttachmentPreviewContent(data, "CODE", "Main.java", "application/octet-stream", (long) data.length));

        // Authorization 头通道：@AuthenticationPrincipal 直接解析出 userId，无需 query token
        ResponseEntity<byte[]> resp = controller.preview(projectId, attachmentId, null, null, actor, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(resp.getHeaders().getContentType().toString()).startsWith("text/plain");
        assertThat(resp.getBody()).isEqualTo(data);
    }

    @Test
    void previewRawReturnsOriginalMediaType() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        byte[] data = "def x = 1".getBytes(StandardCharsets.UTF_8);
        when(service.previewContent(actor, projectId, attachmentId)).thenReturn(
                new AttachmentPreviewContent(data, "CODE", "Main.java", "application/octet-stream", (long) data.length));

        ResponseEntity<byte[]> resp = controller.preview(projectId, attachmentId,
                1, null, actor, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType().toString()).startsWith("application/octet-stream");
        assertThat(resp.getBody()).isEqualTo(data);
    }

    @Test
    void previewUrlWrapsServiceResponse() {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        AttachmentPreviewUrlResponse dto = new AttachmentPreviewUrlResponse();
        dto.setAttachmentId(attachmentId.toString());
        dto.setPreviewable(true);
        dto.setPreviewType("IMAGE");
        when(service.getPreviewInfo(actor, projectId, attachmentId)).thenReturn(dto);

        ApiResponse<?> resp = controller.getPreviewInfo(actor, projectId, attachmentId, request);

        assertThat(resp.data()).isSameAs(dto);
    }

    @Test
    void uploadDelegatesRawBytesToService() throws IOException {
        UUID projectId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        MockHttpServletRequest req = new MockHttpServletRequest("PUT",
                "/api/v1/projects/" + projectId + "/attachments/" + attachmentId);
        req.setContent("file-bytes".getBytes(StandardCharsets.UTF_8));

        controller.upload(actor, projectId, attachmentId, req);

        verify(service).uploadBytes(eq(actor), eq(projectId), eq(attachmentId), any(InputStream.class));
    }
}
