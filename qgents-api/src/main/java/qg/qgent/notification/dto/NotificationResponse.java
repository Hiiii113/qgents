package qg.qgent.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 通知列表响应，包含通知内容、已读状态与跳转资源。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResponse {

    /**
     * 通知 ID。
     */
    @Schema(description = "通知 ID")
    private String id;

    /**
     * 通知类型：MESSAGE_MENTION/MR_PENDING/INVITED/TEAM_JOINED/PROJECT_ADDED。
     */
    @Schema(description = "通知类型：MESSAGE_MENTION/MR_PENDING/INVITED/TEAM_JOINED/PROJECT_ADDED")
    private String kind;

    /**
     * 一行标题。
     */
    @Schema(description = "一行标题")
    private String title;

    /**
     * 补充说明，可为空。
     */
    @Schema(description = "补充说明")
    private String description;

    /**
     * 是否已读。
     */
    @Schema(description = "是否已读")
    private Boolean isRead;

    /**
     * 产生时间（ISO8601 UTC）。
     */
    @Schema(description = "产生时间（ISO8601 UTC）")
    private String createdAt;

    /**
     * 所属项目 ID，点击跳转用；可为空。
     */
    @Schema(description = "所属项目 ID")
    private String projectId;

    /**
     * 来源需求群 ID，可为空。
     */
    @Schema(description = "来源需求群 ID")
    private String groupId;

    /**
     * 关联资源 ID（taskId/mrId），跳转定位用；可为空。
     */
    @Schema(description = "关联资源 ID（taskId/mrId）")
    private String resourceId;
}
