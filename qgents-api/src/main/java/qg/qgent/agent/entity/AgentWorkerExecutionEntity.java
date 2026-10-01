package qg.qgent.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AgentRun 与 Sandbox Worker 工具执行的不可变归属关联。
 *
 * <p>仅保存定位和脱敏诊断字段；工具参数、文件内容、stdout、stderr 与服务令牌只保留在
 * Worker 受控域，不能写入主后端项目库或返回给项目成员。</p>
 */
@Data
@TableName("agent_worker_executions")
public class AgentWorkerExecutionEntity {
    /** Worker 生成的全局唯一工具执行编号。 */
    @TableId(value = "execution_id", type = IdType.INPUT)
    private UUID executionId;
    private UUID projectId;
    private UUID workspaceId;
    private UUID agentRunId;
    private UUID sandboxId;
    private String toolName;
    private String status;
    private Integer exitCode;
    private String failureCode;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
}
