package qg.qgent.harness.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次聊天执行；子执行通过 parentRunId 关联，关联触发消息、实际 Agent 身份和工作区。
 */
@Data
@TableName("agent_runs")
public class AgentRunEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;
    private Long groupId;
    private Long agentId;
    private Long triggerMessageId;
    private Long parentRunId;
    private Long workspaceId;
    private Long createdBy;
    private RunStatus status;
    private String instruction;
    private String result;
    private Integer rounds;
    private Long promptTokens;
    private Long completionTokens;
    private Long cachedTokens;
    private Long reasoningTokens;
    private Long ttftMs;
    private Long modelTimeMs;
    private Long toolTimeMs;
    private Integer toolCalls;
    private Integer failedToolCalls;
    private String modelName;
    private LocalDateTime createdAt;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime finishedAt;

    public void addMetrics(qg.qgent.harness.runtime.AgentLoop.Metrics metrics, String model) {
        if (model != null && !model.isBlank() && (modelName == null || modelName.isBlank())) {
            this.modelName = model;
        }
        if (metrics == null) return;
        this.promptTokens = (this.promptTokens == null ? 0L : this.promptTokens) + metrics.promptTokens();
        this.completionTokens =
            (this.completionTokens == null ? 0L : this.completionTokens) + metrics.completionTokens();
        this.cachedTokens = (this.cachedTokens == null ? 0L : this.cachedTokens) + metrics.cachedTokens();
        this.reasoningTokens =
            (this.reasoningTokens == null ? 0L : this.reasoningTokens) + metrics.reasoningTokens();
        if (this.ttftMs == null && metrics.ttftMs() != null) {
            this.ttftMs = metrics.ttftMs();
        }
        this.modelTimeMs = (this.modelTimeMs == null ? 0L : this.modelTimeMs) + metrics.modelTimeMs();
        this.toolTimeMs = (this.toolTimeMs == null ? 0L : this.toolTimeMs) + metrics.toolTimeMs();
        this.toolCalls = (this.toolCalls == null ? 0 : this.toolCalls) + metrics.toolCalls();
        this.failedToolCalls =
            (this.failedToolCalls == null ? 0 : this.failedToolCalls) + metrics.failedToolCalls();
    }
}
