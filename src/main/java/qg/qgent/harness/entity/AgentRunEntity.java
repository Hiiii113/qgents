package qg.qgent.harness.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 一次聊天执行；子执行通过 parentRunId 关联，关联触发消息和实际工作区。 */
@Data
@TableName("agent_runs")
public class AgentRunEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private Long groupId;
    private Long triggerMessageId;
    private Long parentRunId;
    private Long workspaceId;
    private Long createdBy;
    private RunStatus status;
    private String instruction;
    private String result;
    private Integer rounds;
    private LocalDateTime createdAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime finishedAt;
}
