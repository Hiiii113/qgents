package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/** 一次聊天 Agent 执行；子执行通过 parentRunId 关联，不强制创建工作任务。 */
@Data
@TableName("agent_runs")
public class AgentRunEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID projectId;
    private UUID groupId;
    private UUID triggerMessageId;
    private UUID agentId;
    private UUID parentRunId;
    private UUID taskId;
    private UUID createdBy;
    private String status;
    private String instruction;
    private String result;
    private Integer rounds;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
