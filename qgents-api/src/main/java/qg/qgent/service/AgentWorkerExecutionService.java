package qg.qgent.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import qg.qgent.entity.AgentRunEntity;
import qg.qgent.entity.AgentWorkerExecutionEntity;
import qg.qgent.mapper.AgentRunMapper;
import qg.qgent.mapper.AgentWorkerExecutionMapper;
import qg.qgent.orchestration.worker.WorkerToolExecution;
import qg.qgent.orchestration.ExecutionContentSanitizer;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 在 Worker 接收工具执行后立即持久化 AgentRun 关联，并在轮询到终态后更新受限诊断字段。
 */
@Service
public class AgentWorkerExecutionService {
    private final AgentWorkerExecutionMapper executions;
    private final AgentRunMapper runs;

    public AgentWorkerExecutionService(AgentWorkerExecutionMapper executions, AgentRunMapper runs) {
        this.executions = executions;
        this.runs = runs;
    }

    /**
     * 幂等记录 Worker 回执。调用点位于提交成功后和轮询终态后，因此进程在轮询期间中断时
     * 仍保留 executionId 与其所属 AgentRun。
     */
    @Transactional
    public void record(UUID agentRunId, WorkerToolExecution execution) {
        if (agentRunId == null || execution == null || execution.getId() == null) {
            return;
        }
        AgentRunEntity run = runs.selectById(agentRunId);
        if (run == null) {
            return;
        }
        AgentWorkerExecutionEntity stored = executions.selectById(execution.getId());
        boolean insert = stored == null;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (insert) {
            stored = new AgentWorkerExecutionEntity();
            stored.setExecutionId(execution.getId());
            stored.setProjectId(run.getProjectId());
            stored.setTaskId(run.getTaskId());
            stored.setAgentRunId(run.getId());
            stored.setCreatedAt(timestamp(execution.getCreatedAt(), now));
        }
        // 同一个 executionId 只能属于创建它的 AgentRun，禁止跨项目覆盖关联。
        if (!run.getId().equals(stored.getAgentRunId())) {
            return;
        }
        stored.setSandboxId(execution.getSandboxId());
        stored.setRepositoryId(execution.getRepositoryId());
        stored.setToolName(limit(execution.getTool(), 64));
        stored.setStatus(limit(execution.getStatus(), 24));
        stored.setExitCode(execution.getExitCode());
        stored.setFailureCode(limit(execution.getFailureCode(), 128));
        stored.setFailureReason(execution.getFailureReason() == null ? null
                : limit(ExecutionContentSanitizer.sanitizeDiagnosticDetail(execution.getFailureReason()), 512));
        stored.setStartedAt(timestamp(execution.getStartedAt(), stored.getStartedAt()));
        stored.setFinishedAt(timestamp(execution.getFinishedAt(), stored.getFinishedAt()));
        stored.setUpdatedAt(now);
        if (insert) {
            executions.insert(stored);
        } else {
            executions.updateById(stored);
        }
    }

    private LocalDateTime timestamp(String value, LocalDateTime fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.length() <= max ? normalized : normalized.substring(0, max - 1) + "…";
    }
}
