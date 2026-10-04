package qg.qgent.memory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import qg.qgent.user.dto.UserSummary;

import java.time.LocalDateTime;
import java.util.List;

/** Memory 视图。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoryResponse {

    @Schema(description = "Memory ID")
    private String id;

    @Schema(description = "所属项目 ID")
    private String projectId;

    @Schema(description = "知识标题")
    private String title;

    @Schema(description = "经确认的项目事实正文")
    private String content;

    @Schema(description = "知识分类标识")
    private String category;

    @Schema(description = "标签列表")
    private List<String> tags;

    @Schema(description = "状态：DRAFT / PENDING_REVIEW / APPROVED / REJECTED / ARCHIVED")
    private String status;

    @Schema(description = "创建者摘要")
    private UserSummary creator;

    @Schema(description = "最近审核者摘要")
    private UserSummary reviewer;

    @Schema(description = "最近驳回原因")
    private String rejectionReason;

    @Schema(description = "最近审核时间（UTC）")
    private LocalDateTime reviewedAt;

    @Schema(description = "创建时间（UTC）")
    private LocalDateTime createdAt;
}
