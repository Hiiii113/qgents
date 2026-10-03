package qg.qgent.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/** 团队 Agent 的身份、提示词、可选能力与发布状态。 */
@Data
@TableName(value = "agents", autoResultMap = true)
public class AgentEntity {
    // 是否开启 GitHub 能力，凭证由团队 GitHub App 提供
    private Boolean githubEnabled = false;

    // Agent ID（UUIDv7）。
    @TableId(type = IdType.INPUT)
    private UUID id;
    // 所属团队。
    private UUID teamId;
    // 创建者，用于私有可见性和编辑权限判断。
    private UUID createdBy;
    // 聊天和列表显示的名称。
    private String name;
    // 用途标签，仅用于展示。
    private String role;
    // 头像 URL。
    private String avatar;
    // 用途描述，供用户和 Agent 选择。
    private String description;
    // 系统提示词。
    private String prompt;
    // 发布拒绝原因，仅创建者可见。
    private String reviewReason;
    // 发布审核人。
    private UUID reviewedBy;
    // 审核时间（UTC）。
    private LocalDateTime reviewedAt;
    // PRIVATE 私有、PENDING 待审核、TEAM 团队共享；发布后不能恢复私有。
    private String visibility;
    // ACTIVE 可使用，ARCHIVED 已归档。
    private String status;
    // 团队默认 Agent；数据库唯一索引限制每团队每个默认角色一条。
    private Boolean isDefault;
    // 创建时间（UTC）。
    private LocalDateTime createdAt;
    // 更新时间（UTC）。
    private LocalDateTime updatedAt;
}
