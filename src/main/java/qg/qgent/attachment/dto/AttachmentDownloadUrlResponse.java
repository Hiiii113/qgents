package qg.qgent.attachment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentDownloadUrlResponse {

    @Schema(description = "附件 ID")
    private String attachmentId;

    @Schema(description = "预签名下载地址")
    private String downloadUrl;

    @Schema(description = "地址过期时间")
    private LocalDateTime expiresAt;
}
