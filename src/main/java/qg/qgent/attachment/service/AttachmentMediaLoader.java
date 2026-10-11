package qg.qgent.attachment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Component;
import org.springframework.util.InvalidMimeTypeException;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import qg.qgent.attachment.service.AttachmentService;
import qg.qgent.chat.dto.ContextMessage;
import qg.qgent.common.web.ApiException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 将群聊图片转换为多模态输入，将文本和代码附件加入提示词；无法读取的附件保留上下文中的引用。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttachmentMediaLoader {

    // 单张图片字节上限；超限时跳过媒体加载。
    public static final long IMAGE_BYTES_CAP = 3_900_000L;

    // 单个文本或代码附件的字节上限。
    public static final long FILE_TEXT_BYTES_CAP = 64 * 1024L;

    private final AttachmentService attachmentService;

    /** 以当前用户身份读取群聊附件，返回图片媒体和内联文本。 */
    public Result load(UUID actor, UUID projectId, List<ContextMessage> conversation) {
        List<Media> media = new ArrayList<>();
        StringBuilder extraText = new StringBuilder();
        if (actor == null || projectId == null || conversation == null) {
            return new Result(media, extraText.toString());
        }
        for (ContextMessage message : conversation) {
            if (message == null || message.getAttachmentId() == null || message.getAttachmentId().isBlank()) {
                continue;
            }
            if ("IMAGE".equals(message.getType())) {
                attachImage(actor, projectId, message, media);
            } else if ("FILE".equals(message.getType())) {
                appendFileText(actor, projectId, message, extraText);
            }
        }
        return new Result(media, extraText.toString());
    }

    private void attachImage(UUID actor, UUID projectId, ContextMessage message, List<Media> media) {
        try {
            UUID attachmentId = UUID.fromString(message.getAttachmentId());
            AttachmentService.AttachmentPreviewMetadata meta =
                    attachmentService.previewMetadata(actor, projectId, attachmentId);
            if (!"IMAGE".equals(meta.previewType()) || meta.sizeBytes() == null
                    || meta.sizeBytes() > IMAGE_BYTES_CAP) {
                log.warn("attachment image skipped attachment={} type={} size={} (exceeds budget or unsupported)",
                        attachmentId, meta.previewType(), meta.sizeBytes());
                return;
            }
            AttachmentService.AttachmentPreviewContent content =
                    attachmentService.previewContent(actor, projectId, attachmentId);
            byte[] bytes = content.bytes();
            if (bytes.length > IMAGE_BYTES_CAP) {
                log.warn("attachment image skipped attachment={} actualBytes={} (exceeds budget)",
                        attachmentId, bytes.length);
                return;
            }
            MimeType mime = resolveImageMimeType(content.mediaType());
            String dataUri = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
            media.add(new Media(mime, URI.create(dataUri)));
            log.info("attachment image attached attachment={} bytes={} mime={}",
                    attachmentId, bytes.length, mime);
        } catch (ApiException e) {
            log.warn("attachment image unavailable attachment={} code={}", message.getAttachmentId(), e.code());
        } catch (RuntimeException e) {
            log.warn("attachment image read failed attachment={} category={}",
                    message.getAttachmentId(), e.getClass().getSimpleName());
        }
    }

    private void appendFileText(UUID actor, UUID projectId, ContextMessage message, StringBuilder extraText) {
        try {
            UUID attachmentId = UUID.fromString(message.getAttachmentId());
            AttachmentService.AttachmentPreviewMetadata meta =
                    attachmentService.previewMetadata(actor, projectId, attachmentId);
            if (!"TEXT".equals(meta.previewType()) && !"CODE".equals(meta.previewType())) {
                return;
            }
            if (meta.sizeBytes() == null || meta.sizeBytes() > FILE_TEXT_BYTES_CAP) {
                log.warn("attachment file skipped attachment={} size={} (exceeds text budget)",
                        attachmentId, meta.sizeBytes());
                return;
            }
            AttachmentService.AttachmentPreviewContent content =
                    attachmentService.previewContent(actor, projectId, attachmentId);
            if (content.bytes().length > FILE_TEXT_BYTES_CAP) {
                return;
            }
            String name = message.getFileName() == null || message.getFileName().isBlank()
                    ? attachmentId.toString() : message.getFileName();
            extraText.append("\n\n[附件内容: ").append(name).append("]\n")
                    .append(new String(content.bytes(), StandardCharsets.UTF_8));
            log.info("attachment file attached attachment={} bytes={}", attachmentId, content.bytes().length);
        } catch (ApiException e) {
            log.warn("attachment file unavailable attachment={} code={}", message.getAttachmentId(), e.code());
        } catch (RuntimeException e) {
            log.warn("attachment file read failed attachment={} category={}",
                    message.getAttachmentId(), e.getClass().getSimpleName());
        }
    }

    /** 解析图片 MIME 类型，缺失或无效时使用 image/png。 */
    private MimeType resolveImageMimeType(String mediaType) {
        if (mediaType != null && !mediaType.isBlank()) {
            try {
                return MimeTypeUtils.parseMimeType(mediaType.trim().toLowerCase(Locale.ROOT));
            } catch (InvalidMimeTypeException e) {
                // MIME 无效时按 PNG 处理
            }
        }
        return MimeType.valueOf("image/png");
    }

    /** 图片媒体与内联附件文本。 */
    public record Result(List<Media> media, String extraText) {
        public Result {
            media = media == null ? List.of() : media;
            extraText = extraText == null ? "" : extraText;
        }
    }
}
