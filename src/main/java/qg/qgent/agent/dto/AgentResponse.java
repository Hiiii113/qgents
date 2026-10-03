package qg.qgent.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 团队 Agent 详情，提示词及拒绝原因按创建者身份返回。 */
@Data
@NoArgsConstructor
public class AgentResponse {
    @Schema(description = "是否开启 GitHub 能力")
    private boolean githubEnabled;

    @Schema(description = "Agent ID")
    private String id;
    private String name;
    private String avatar;
    private String role;
    private String description;
    private String prompt;
    private String visibility;
    private String status;
    private String createdBy;
    // 团队默认 Agent 不可编辑、发布或归档。
    @Schema(description = "是否为团队默认（系统预置）Agent")
    private Boolean isDefault;
    // 拒绝原因仅创建者可见。
    @Schema(description = "发布审核拒绝原因（仅创建者可见）")
    private String reviewReason;
    // 发布审核人 ID。
    @Schema(description = "发布审核人 ID")
    private String reviewedBy;
    // 审核时间，ISO8601 UTC。
    @Schema(description = "发布审核时间（ISO8601 UTC）")
    private String reviewedAt;

    public AgentResponse(String id, String name, String avatar, String role, String description, String prompt,
                         String visibility, String status, String createdBy, Boolean isDefault) {
        this.id = id;
        this.name = name;
        this.avatar = avatar;
        this.role = role;
        this.description = description;
        this.prompt = prompt;
        this.visibility = visibility;
        this.status = status;
        this.createdBy = createdBy;
        this.isDefault = isDefault;
    }
}
