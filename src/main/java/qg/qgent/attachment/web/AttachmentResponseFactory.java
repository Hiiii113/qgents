package qg.qgent.attachment.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import qg.qgent.attachment.service.AttachmentService.AttachmentPreviewResource;
import qg.qgent.attachment.storage.AttachmentContent;
import qg.qgent.attachment.storage.AttachmentResource;
import qg.qgent.common.web.RequestIdFilter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** 附件的 HTTP 展示策略：文件名、媒体类型、单段 Range 和错误响应。 */
@Component
public class AttachmentResponseFactory {
    public ResponseEntity<?> preview(AttachmentPreviewResource preview, Integer raw, String rangeHeader,
            HttpServletRequest request) {
        AttachmentResource resource = preview.resource();
        long total = resource.contentLength();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            try {
                List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
                if (ranges.size() != 1 || total == 0) throw new IllegalArgumentException("Expected one range");
                long start = ranges.getFirst().getRangeStart(total);
                long end = ranges.getFirst().getRangeEnd(total);
                if (start >= total || end < start) throw new IllegalArgumentException("Range out of bounds");
                resource = resource.region(start, end);
                response = ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + total);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + total)
                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("error", Map.of("code", "ATTACHMENT_RANGE_INVALID",
                                "message", "Range 越界或格式非法", "details", List.of()),
                                "requestId", String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE))));
            }
        }
        String type = resource.contentType();
        if (("TEXT".equals(preview.previewType()) || "CODE".equals(preview.previewType()))
                && !Integer.valueOf(1).equals(raw)) {
            type = "text/plain; charset=utf-8";
        }
        return response.header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition("inline", resource.getFilename()))
                .contentType(MediaType.parseMediaType(type))
                .contentLength(resource.contentLength()).body(resource);
    }

    public ResponseEntity<InputStreamResource> content(AttachmentContent content) {
        String type = content.getContentType() == null || content.getContentType().isBlank()
                ? "application/octet-stream" : content.getContentType();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
                .contentLength(content.getSizeBytes() == null ? -1 : content.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        disposition(type.startsWith("image/") ? "inline" : "attachment", content.getFileName()))
                .body(new InputStreamResource(content.getStream()));
    }

    private String disposition(String type, String fileName) {
        return ContentDisposition.builder(type)
                .filename(fileName == null || fileName.isBlank() ? "attachment" : fileName, StandardCharsets.UTF_8)
                .build().toString();
    }
}
