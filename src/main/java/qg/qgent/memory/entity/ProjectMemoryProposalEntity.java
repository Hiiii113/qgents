package qg.qgent.memory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 项目记忆候选，对应表 project_memory_proposals。
 * <p>
 * 候选原文、来源群、提出运行与审批回复在服务端绑定：审批消息必须回复候选所在的 Agent 消息，
 * 管理员身份按审批消息的真实作者核验，不信任模型传入的参数。
 */
@Data
@TableName("project_memory_proposals")
public class ProjectMemoryProposalEntity {

    // 候选 ID。
    @TableId(type = IdType.AUTO)
    private Long id;

    // 所属项目 ID。
    private Long projectId;

    // 提出候选的项目默认群 ID；受限群内容在授权规则明确前不进入项目记忆。
    private Long groupId;

    // 提出候选的 AgentRun ID；每个运行最多一个候选，后台发现时为空。
    private Long agentRunId;

    // 候选所在的 Agent 消息 ID；审批回复必须回复该消息。
    private Long messageId;

    // 候选 memory.md 全文。
    private String content;

    // 提出候选时的 memory.md 内容版本。
    private String baseVersion;

    // 后台发现候选时依据的群记忆内容版本；群聊中提出时为空。
    private String sourceMemoryVersion;

    // 状态枚举：PENDING/APPLIED。
    private String status;

    // 提出候选的真实用户 ID；后台发现时为空。
    private Long proposedBy;

    // 审批的管理员用户 ID。
    private Long approvedBy;

    // 审批回复消息 ID。
    private Long approvedMessageId;

    // 写入后的 memory.md 内容版本。
    private String appliedVersion;

    // 创建时间（UTC）。
    private LocalDateTime createdAt;

    // 审批完成时间（UTC）。
    private LocalDateTime approvedAt;
}
