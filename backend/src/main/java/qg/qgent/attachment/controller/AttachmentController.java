package qg.qgent.attachment.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import qg.qgent.attachment.dto.AttachmentCreateRequest;
import qg.qgent.attachment.dto.AttachmentDownloadUrlResponse;
import qg.qgent.attachment.dto.AttachmentStatusResponse;
import qg.qgent.attachment.service.AttachmentService;
import qg.qgent.attachment.web.AttachmentResponseFactory;
import qg.qgent.common.web.ApiResponse;
import qg.qgent.common.web.RequestIdFilter;

import java.io.IOException;
import java.util.UUID;

/** 附件上传、确认、下载和预览入口。 */
@RestController
@RequestMapping("/api/v1")
public class AttachmentController {

    private final AttachmentService attachmentService;
    private final AttachmentResponseFactory responses;

    public AttachmentController(AttachmentService attachmentService, AttachmentResponseFactory responses) {
        this.attachmentService = attachmentService;
        this.responses = responses;
    }

    @Operation(summary = "申请附件上传", description = "按存储配置返回 OSS 直传或后端 PUT 地址；上传完成后调用确认接口。")
    @PostMapping("/projects/{projectId}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<?> createCredential(@AuthenticationPrincipal UUID userId, @PathVariable UUID projectId,
            @Valid @RequestBody AttachmentCreateRequest body, HttpServletRequest request) {
        return ok(attachmentService.createCredential(userId, projectId, body), request);
    }

    @Operation(summary = "确认附件上传完成", description = "客户端上传文件后调用，服务端检查文件存在及实际大小后置 READY；重复确认返回成功。")
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
    public ResponseEntity<?> preview(@PathVariable UUID projectId, @PathVariable UUID attachmentId,
            @RequestParam(name = "raw", required = false) Integer raw,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            @AuthenticationPrincipal UUID userId,
            HttpServletRequest request) {
        return responses.preview(attachmentService.previewResource(userId, projectId, attachmentId), raw, rangeHeader,
                request);
    }

    @Operation(summary = "获取附件内容（鉴权下载代理）", description = "鉴权后流式返回附件内容，图片 inline 展示、文件可下载；作为 content.url 的稳定地址。")
    @GetMapping("/projects/{projectId}/attachments/{attachmentId}/content")
    public ResponseEntity<InputStreamResource> content(@AuthenticationPrincipal UUID userId,
            @PathVariable UUID projectId, @PathVariable UUID attachmentId) {
        return responses.content(attachmentService.downloadContent(userId, projectId, attachmentId));
    }

    private ApiResponse<?> ok(Object data, HttpServletRequest request) {
        return ApiResponse.ok(data, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
