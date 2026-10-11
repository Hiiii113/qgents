package qg.qgent.harness.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/** 一次聊天执行；子执行通过 parentRunId 关联，关联触发消息和实际工作区。 */
@Data
@TableName("agent_runs")
public class AgentRunEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID projectId;
    private UUID groupId;
    private UUID triggerMessageId;
    private UUID parentRunId;
    private UUID workspaceId;
    private UUID createdBy;
    private String status;
    private String instruction;
    private String result;
    private Integer rounds;
    private LocalDateTime createdAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime finishedAt;
}
