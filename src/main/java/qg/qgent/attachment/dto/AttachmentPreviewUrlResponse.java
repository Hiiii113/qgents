package qg.qgent.attachment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentPreviewUrlResponse {

    @Schema(description = "附件 ID")
    private String attachmentId;

    @Schema(description = "原始文件名")
    private String fileName;

    @Schema(description = "MIME 媒体类型")
    private String mediaType;

    @Schema(description = "文件大小（字节）")
    private Long sizeBytes;

    @Schema(description = "是否可内联预览")
    private boolean previewable;

    @Schema(description = "预览类型：IMAGE/PDF/TEXT/CODE/UNSUPPORTED")
    private String previewType;

    @Schema(description = "短期签名预览地址（相对路径，带 token 查询参数）")
    private String previewUrl;

    @Schema(description = "下载地址；存储策略不支持时可为 null")
    private String downloadUrl;

    @Schema(description = "previewUrl 过期时间")
    private LocalDateTime expiresAt;
}
