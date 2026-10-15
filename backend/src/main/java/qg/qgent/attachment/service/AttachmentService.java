package qg.qgent.attachment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import qg.qgent.attachment.dto.AttachmentCreateRequest;
import qg.qgent.attachment.dto.AttachmentDownloadUrlResponse;
import qg.qgent.attachment.dto.AttachmentPreviewUrlResponse;
import qg.qgent.attachment.dto.AttachmentStatusResponse;
import qg.qgent.attachment.dto.AttachmentUploadCredentialResponse;
import qg.qgent.attachment.dto.UploadCredential;
import qg.qgent.attachment.entity.AttachmentEntity;
import qg.qgent.attachment.mapper.AttachmentMapper;
import qg.qgent.attachment.storage.AttachmentContent;
import qg.qgent.attachment.storage.AttachmentResource;
import qg.qgent.attachment.storage.AttachmentStorageStrategy;
import qg.qgent.auth.service.TokenService;
import qg.qgent.common.util.UuidV7;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.service.ProjectAccessService;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 文件存储独立于附件元数据；上传确认后可供消息展示和 Agent 读取。 */
@Service
public class AttachmentService {
    private final AttachmentMapper attachmentMapper;
    private final AttachmentStorageStrategy storage;
    private final ProjectAccessService access;
    private final TokenService tokens;
    private final long maxSizeBytes;
    private final long downloadExpirySeconds;

    public AttachmentService(AttachmentMapper attachmentMapper, AttachmentStorageStrategy storage,
                             ProjectAccessService access, TokenService tokens,
                             @Value("${app.attachment-max-size-bytes:52428800}") long maxSizeBytes,
                             @Value("${app.attachment-download-expiry-seconds:900}") long downloadExpirySeconds) {
        this.attachmentMapper = attachmentMapper;
        this.storage = storage;
        this.access = access;
        this.tokens = tokens;
        this.maxSizeBytes = maxSizeBytes;
        this.downloadExpirySeconds = downloadExpirySeconds;
    }

    /** 先保存 PENDING 记录，再按存储配置返回 OSS 直传或本地上传地址。 */
    @Transactional
    public AttachmentUploadCredentialResponse createCredential(UUID actor, UUID projectId,
                                                               AttachmentCreateRequest body) {
        access.requireProjectMember(projectId, actor);
        long sizeBytes = body.getSizeBytes();
        if (sizeBytes <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTACHMENT_SIZE_REQUIRED", "文件大小必须大于 0");
        }
        if (sizeBytes > maxSizeBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_TOO_LARGE", "文件大小超过上限 " + maxSizeBytes + " 字节");
        }
        AttachmentEntity attachment = new AttachmentEntity();
        attachment.setId(UuidV7.next());
        attachment.setProjectId(projectId);
        attachment.setUploadedBy(actor);
        attachment.setObjectKey("projects/" + projectId + "/attachments/" + attachment.getId());
        attachment.setFileName(body.getFileName().trim());
        attachment.setMediaType(body.getContentType());
        attachment.setSizeBytes(sizeBytes);
        attachment.setStatus("PENDING");
        attachmentMapper.insert(attachment);

        UploadCredential credential = storage.createCredential(attachment.getObjectKey(), attachment.getMediaType());
        return new AttachmentUploadCredentialResponse(attachment.getId().toString(), credential.getUploadUrl(),
                credential.getMethod(), credential.getExpiresAt(), credential.getHeaders());
    }

    public AttachmentDownloadUrlResponse downloadUrl(UUID actor, UUID projectId, UUID attachmentId) {
        access.requireProjectMember(projectId, actor);
        AttachmentEntity attachment = requireAttachment(projectId, attachmentId);
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(downloadExpirySeconds);
        String url;
        try {
            url = storage.createDownloadUrl(attachment.getObjectKey(), downloadExpirySeconds);
        } catch (UnsupportedOperationException e) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "ATTACHMENT_DOWNLOAD_UNSUPPORTED",
                    "当前存储策略暂不支持下载（请启用阿里云 OSS 或接入对象存储）");
        }
        return new AttachmentDownloadUrlResponse(attachment.getId().toString(), url, expiresAt);
    }

    @Transactional
    public AttachmentStatusResponse confirmUpload(UUID actor, UUID projectId, UUID attachmentId) {
        access.requireProjectMember(projectId, actor);
        AttachmentEntity attachment = requireAttachment(projectId, attachmentId);
        if ("READY".equals(attachment.getStatus())) {
            return new AttachmentStatusResponse(attachment.getId().toString(), "READY");
        }
        if (!"PENDING".equals(attachment.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_PENDING", "附件已确认或不可确认");
        }
        var actualSize = storage.objectSize(attachment.getObjectKey());
        if (actualSize.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_UPLOADED", "附件尚未上传，请先上传再确认");
        }
        if (actualSize.getAsLong() > maxSizeBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_TOO_LARGE", "文件大小超过上限 " + maxSizeBytes + " 字节");
        }
        if (actualSize.getAsLong() != attachment.getSizeBytes()) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_SIZE_MISMATCH", "实际文件大小与申请上传时不一致");
        }
        attachment.setStatus("READY");
        attachmentMapper.updateById(attachment);
        return new AttachmentStatusResponse(attachment.getId().toString(), attachment.getStatus());
    }

    public void uploadBytes(UUID actor, UUID projectId, UUID attachmentId, InputStream bytes) {
        access.requireProjectMember(projectId, actor);
        AttachmentEntity attachment = requireAttachment(projectId, attachmentId);
        if (!"PENDING".equals(attachment.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_PENDING", "附件不可上传（状态非待上传）");
        }
        storage.storeBytes(attachment.getObjectKey(), bytes, attachment.getSizeBytes(), maxSizeBytes);
    }

    public AttachmentContent downloadContent(UUID actor, UUID projectId, UUID attachmentId) {
        AttachmentEntity attachment = requireReadyAttachment(actor, projectId, attachmentId);
        return storage.loadContent(attachment.getObjectKey(), attachment.getFileName(), attachment.getMediaType());
    }

    public AttachmentPreviewUrlResponse getPreviewInfo(UUID actor, UUID projectId, UUID attachmentId) {
        AttachmentEntity attachment = requireReadyAttachment(actor, projectId, attachmentId);
        String previewType = classifyPreviewType(attachment);
        String downloadUrl = null;
        try {
            downloadUrl = storage.createDownloadUrl(attachment.getObjectKey(), downloadExpirySeconds);
        } catch (UnsupportedOperationException e) {
            // 本地磁盘通过 preview/content 接口读取，不提供独立下载 URL。
        }
        String accessToken = tokens.access(actor);
        String previewUrl = "/api/v1/projects/" + projectId + "/attachments/" + attachmentId
                + "/preview?token=" + accessToken;
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(tokens.accessSeconds());
        return new AttachmentPreviewUrlResponse(attachment.getId().toString(), attachment.getFileName(),
                attachment.getMediaType(), attachment.getSizeBytes(),
                !"UNSUPPORTED".equals(previewType), previewType, previewUrl, downloadUrl, expiresAt);
    }

    /** Agent 读取文件前，先用类型和大小判断是否放入模型上下文。 */
    public AttachmentPreviewMetadata previewMetadata(UUID actor, UUID projectId, UUID attachmentId) {
        AttachmentEntity attachment = requireReadyAttachment(actor, projectId, attachmentId);
        return new AttachmentPreviewMetadata(classifyPreviewType(attachment), attachment.getSizeBytes());
    }

    /** 消息回显只补充类型，避免将会过期的 token URL 存进消息。 */
    public MessageAttachmentPreview messagePreview(UUID projectId, String attachmentId) {
        UUID id;
        try {
            id = UUID.fromString(attachmentId);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
        AttachmentEntity attachment = attachmentMapper.selectById(id);
        if (attachment == null || !projectId.equals(attachment.getProjectId())) {
            return null;
        }
        if (!"READY".equals(attachment.getStatus())) {
            return null;
        }
        String previewType = classifyPreviewType(attachment);
        return new MessageAttachmentPreview(!"UNSUPPORTED".equals(previewType), previewType);
    }

    /** Agent 在已有大小预算内读取完整内容；浏览器预览使用 previewResource。 */
    public AttachmentPreviewContent previewContent(UUID actor, UUID projectId, UUID attachmentId) {
        AttachmentEntity attachment = requireReadyAttachment(actor, projectId, attachmentId);
        AttachmentContent loaded = storage.loadContent(attachment.getObjectKey(), attachment.getFileName(), attachment.getMediaType());
        return new AttachmentPreviewContent(readAll(loaded.getStream()), classifyPreviewType(attachment),
                attachment.getFileName(), attachment.getMediaType(), attachment.getSizeBytes());
    }

    /** 浏览器预览仅准备元数据，内容由 Spring 在写响应时按需打开。 */
    public AttachmentPreviewResource previewResource(UUID actor, UUID projectId, UUID attachmentId) {
        AttachmentEntity attachment = requireReadyAttachment(actor, projectId, attachmentId);
        long size = storage.objectSize(attachment.getObjectKey())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "附件文件不存在"));
        return new AttachmentPreviewResource(new AttachmentResource(storage, attachment.getObjectKey(),
                attachment.getFileName(), attachment.getMediaType(), size), classifyPreviewType(attachment));
    }

    public record AttachmentPreviewResource(AttachmentResource resource, String previewType) {
    }

    private byte[] readAll(InputStream in) {
        try (InputStream stream = in) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ATTACHMENT_READ_FAILED", "读取附件内容失败");
        }
    }

    private static final Set<String> CODE_MEDIA_TYPES = Set.of(
            "application/json", "application/xml", "application/javascript", "application/x-javascript",
            "application/x-yaml", "application/x-sh", "application/x-python");

    private static final Set<String> CODE_EXTENSIONS = Set.of(
            "java", "kt", "ts", "tsx", "js", "jsx", "py", "go", "rs", "c", "cpp", "cc", "h", "hpp",
            "sql", "sh", "bash", "zsh", "json", "yaml", "yml", "xml", "md", "markdown", "properties",
            "css", "scss", "html", "htm", "vue", "gradle", "toml", "ini", "cfg", "conf");

    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "log");

    private String classifyPreviewType(AttachmentEntity attachment) {
        String mediaType = attachment.getMediaType();
        if (mediaType != null) {
            String lower = mediaType.toLowerCase(Locale.ROOT).trim();
            if (lower.startsWith("image/")) {
                return "IMAGE";
            }
            if ("application/pdf".equals(lower)) {
                return "PDF";
            }
            if (lower.startsWith("text/")) {
                return "TEXT";
            }
            if (CODE_MEDIA_TYPES.contains(lower)) {
                return "CODE";
            }
        }
        String fileName = attachment.getFileName();
        if (fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0 && dot < fileName.length() - 1) {
                String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT).trim();
                if (CODE_EXTENSIONS.contains(ext)) {
                    return "CODE";
                }
                if (TEXT_EXTENSIONS.contains(ext)) {
                    return "TEXT";
                }
            }
        }
        return "UNSUPPORTED";
    }

    public record AttachmentPreviewMetadata(String previewType, Long sizeBytes) {}

    @lombok.Value
    public static class MessageAttachmentPreview {
        boolean previewable;
        String previewType;
    }

    public record AttachmentPreviewContent(byte[] bytes, String previewType, String fileName,
                                           String mediaType, Long sizeBytes) {}

    private AttachmentEntity requireReadyAttachment(UUID actor, UUID projectId, UUID attachmentId) {
        access.requireProjectMember(projectId, actor);
        AttachmentEntity attachment = requireAttachment(projectId, attachmentId);
        if (!"READY".equals(attachment.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_READY", "附件尚未上传完成");
        }
        return attachment;
    }

    private AttachmentEntity requireAttachment(UUID projectId, UUID attachmentId) {
        AttachmentEntity attachment = attachmentMapper.selectById(attachmentId);
        if (attachment == null || !projectId.equals(attachment.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "附件不存在或无权访问");
        }
        return attachment;
    }
}
