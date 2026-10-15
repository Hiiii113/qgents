package qg.qgent.attachment.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import qg.qgent.attachment.dto.AttachmentCreateRequest;
import qg.qgent.attachment.dto.AttachmentDownloadUrlResponse;
import qg.qgent.attachment.dto.AttachmentStatusResponse;
import qg.qgent.attachment.service.AttachmentService;
import qg.qgent.attachment.service.AttachmentService.AttachmentPreviewContent;
import qg.qgent.attachment.storage.AttachmentContent;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 附件上传、确认、下载和预览入口。 */
@RestController
@RequestMapping("/api/v1")
public class AttachmentController {

    private final AttachmentService attachmentService;
    private final ObjectMapper objectMapper;

    public AttachmentController(AttachmentService attachmentService, ObjectMapper objectMapper) {
        this.attachmentService = attachmentService;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "申请附件上传", description = "按存储配置返回 OSS 直传或后端 PUT 地址；上传完成后调用确认接口。")
    @PostMapping("/projects/{projectId}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> createCredential(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @Valid @RequestBody AttachmentCreateRequest body, HttpServletRequest request) {
        return ok(attachmentService.createCredential(userId, projectId, body), request);
    }

    @Operation(summary = "确认附件上传完成", description = "客户端上传文件后调用，服务端检查文件存在并置 READY。")
    @PostMapping("/projects/{projectId}/attachments/{attachmentId}/confirm")
    public ApiResponse<?> confirm(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID attachmentId, HttpServletRequest request) {
        AttachmentStatusResponse data = attachmentService.confirmUpload(userId, projectId, attachmentId);
        return ok(data, request);
    }

    @Operation(summary = "上传本地存储附件", description = "仅本地磁盘模式接收文件字节；OSS 模式使用申请接口返回的直传地址。随后调用 confirm 置 READY。")
    @PutMapping("/projects/{projectId}/attachments/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upload(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID attachmentId,
            HttpServletRequest request) throws IOException {
        attachmentService.uploadBytes(userId, projectId, attachmentId, request.getInputStream());
    }

    @Operation(summary = "获取附件临时下载地址", description = "返回短期有效的预签名 GET 地址，客户端据此下载/预览文件；过期需重新签发。")
    @GetMapping("/projects/{projectId}/attachments/{attachmentId}/download-url")
    public ApiResponse<?> downloadUrl(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID attachmentId, HttpServletRequest request) {
        AttachmentDownloadUrlResponse data = attachmentService.downloadUrl(userId, projectId, attachmentId);
        return ok(data, request);
    }

    @Operation(summary = "获取附件预览元数据与签名预览地址", description = "返回预览类型与短期签名预览地址（带 token），前端可直接交给 img/iframe/系统查看器打开。")
    @GetMapping("/projects/{projectId}/attachments/{attachmentId}/preview-url")
    public ApiResponse<?> getPreviewInfo(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID attachmentId, HttpServletRequest request) {
        return ok(attachmentService.getPreviewInfo(userId, projectId, attachmentId), request);
    }

    @Operation(summary = "获取附件内联预览内容（双通道鉴权 + Range）", description = "内联返回附件字节，浏览器/系统查看器直接展示；支持 Authorization 头或 ?token= 双通道鉴权与 HTTP Range 分段。")
    @GetMapping("/projects/{projectId}/attachments/{attachmentId}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable UUID projectId, @PathVariable UUID attachmentId,
            @RequestParam(name = "raw", required = false) Integer raw,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            @AuthenticationPrincipal UUID userId,
            HttpServletRequest request) {
        AttachmentPreviewContent pc = attachmentService.previewContent(userId, projectId, attachmentId);
        byte[] data = pc.bytes();
        String contentType = resolvePreviewContentType(pc.previewType(), pc.mediaType(), raw);
        ContentDisposition cd = ContentDisposition.builder("inline")
                .filename(pc.fileName() == null || pc.fileName().isBlank() ? "preview" : pc.fileName(),
                        StandardCharsets.UTF_8)
                .build();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            long[] range = parseRange(rangeHeader, data.length);
            if (range == null) {
                return rangeNotSatisfiable(data.length, request);
            }
            int start = (int) range[0];
            int end = (int) range[1];
            response = ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + data.length);
            data = Arrays.copyOfRange(data, start, end + 1);
        }
        return response
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(data.length)
                .body(data);
    }

    private String resolvePreviewContentType(String previewType, String mediaType, Integer raw) {
        if ("TEXT".equals(previewType) || "CODE".equals(previewType)) {
            if (raw != null && raw == 1 && mediaType != null && !mediaType.isBlank()) {
                return mediaType;
            }
            return "text/plain; charset=utf-8";
        }
        return mediaType == null || mediaType.isBlank() ? "application/octet-stream" : mediaType;
    }

    // 单段 Range：start-end、start-、-suffix；不合法时返回 416。
    private long[] parseRange(String header, int total) {
        if (!header.startsWith("bytes=")) {
            return null;
        }
        String spec = header.substring("bytes=".length()).trim();
        if (spec.contains(",")) {
            return null; // 多段 Range 暂不支持
        }
        String[] parts = spec.split("-", 2);
        if (parts.length != 2) {
            return null;
        }
        try {
            long start;
            long end;
            String startPart = parts[0].trim();
            String endPart = parts[1].trim();
            if (startPart.isEmpty()) {
                // 后缀式 bytes=-N：返回最后 N 字节
                long suffix = Long.parseLong(endPart);
                if (suffix <= 0 || total <= 0) {
                    return null;
                }
                start = Math.max(0, total - suffix);
                end = total - 1;
            } else {
                start = Long.parseLong(startPart);
                end = endPart.isEmpty() ? total - 1 : Long.parseLong(endPart);
                if (start < 0 || start >= total || end < start) {
                    return null;
                }
                end = Math.min(end, total - 1);
            }
            return new long[] { start, end };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ResponseEntity<byte[]> rangeNotSatisfiable(long total, HttpServletRequest request) {
        Map<String, Object> body = Map.of(
                "error", Map.of("code", "ATTACHMENT_RANGE_INVALID",
                        "message", "Range 越界或格式非法", "details", List.of()),
                "requestId", String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE)));
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化错误响应失败", e);
        }
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + total)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @Operation(summary = "获取附件内容（鉴权下载代理）", description = "鉴权后流式返回附件内容，图片 inline 展示、文件可下载；作为 content.url 的稳定地址。")
    @GetMapping("/projects/{projectId}/attachments/{attachmentId}/content")
    public ResponseEntity<InputStreamResource> content(@AuthenticationPrincipal UUID userId,
            @PathVariable UUID projectId, @PathVariable UUID attachmentId) {
        AttachmentContent content = attachmentService.downloadContent(userId, projectId, attachmentId);
        String disposition = content.getContentType() != null && content.getContentType().startsWith("image/")
                ? "inline"
                : "attachment";
        ContentDisposition cd = ContentDisposition.builder(disposition)
                .filename(content.getFileName() == null || content.getFileName().isBlank() ? "attachment"
                        : content.getFileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.getContentType()))
                .contentLength(content.getSizeBytes() == null ? -1 : content.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .body(new InputStreamResource(content.getStream()));
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
