package qg.qgent.harness.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import qg.qgent.chat.service.MessageService;
import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.harness.entity.RunStatus;
import qg.qgent.harness.mapper.AgentRunMapper;
import qg.qgent.project.service.ProjectAccessService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按触发消息停止执行，执行状态保留在内部运行记录中。
 */
@Service
@RequiredArgsConstructor
public class AgentRunService {
    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);

    private final AgentRunMapper runs;
    private final MessageService messages;
    private final ProjectAccessService access;
    private final Map<Long, Runnable> cancellationSignals = new ConcurrentHashMap<>();

    /**
     * 服务重启后，上一进程留下的运行不可能再继续；收尾为失败，避免前端永远看到 RUNNING。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedRuns() {
        int interrupted =
            runs.update(
                null,
                Wrappers.<AgentRunEntity>lambdaUpdate()
                    .set(AgentRunEntity::getStatus, RunStatus.FAILED)
                    .set(AgentRunEntity::getResult, "服务重启，运行已中断")
                    .set(AgentRunEntity::getFinishedAt, LocalDateTime.now(ZoneOffset.UTC))
                    .in(
                        AgentRunEntity::getStatus,
                        RunStatus.RUNNING,
                        RunStatus.CANCELLING));
        if (interrupted > 0) {
            log.warn("marked {} interrupted agent runs as FAILED after restart", interrupted);
        }
    }

    /**
     * 一次根运行由一条已提交的用户消息触发；群聊与私聊共用同一条记录，group_id 就是会话 ID。
     */
    public AgentRunEntity startRoot(
        Long agentId,
        Long projectId,
        Long conversationId,
        Long triggerMessageId,
        Long actor,
        String instruction) {
        AgentRunEntity run = new AgentRunEntity();
        run.setProjectId(projectId);
        run.setGroupId(conversationId);
        run.setAgentId(agentId);
        run.setTriggerMessageId(triggerMessageId);
        run.setCreatedBy(actor);
        run.setInstruction(instruction);
        run.setStatus(RunStatus.RUNNING);
        run.setRounds(0);
        initMetrics(run);
        run.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        runs.insert(run);
        return run;
    }

    public AgentRunEntity startChild(AgentRunEntity root, String instruction, Long workspaceId) {
        AgentRunEntity run = new AgentRunEntity();
        run.setProjectId(root.getProjectId());
        run.setGroupId(root.getGroupId());
        run.setAgentId(root.getAgentId());
        run.setTriggerMessageId(root.getTriggerMessageId());
        run.setParentRunId(root.getId());
        run.setWorkspaceId(workspaceId);
        run.setCreatedBy(root.getCreatedBy());
        run.setInstruction(instruction);
        run.setStatus(RunStatus.RUNNING);
        run.setRounds(0);
        initMetrics(run);
        run.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        runs.insert(run);
        return run;
    }

    private static void initMetrics(AgentRunEntity run) {
        run.setPromptTokens(0L);
        run.setCompletionTokens(0L);
        run.setCachedTokens(0L);
        run.setReasoningTokens(0L);
        run.setModelTimeMs(0L);
        run.setToolTimeMs(0L);
        run.setToolCalls(0);
        run.setFailedToolCalls(0);
    }

    public void save(AgentRunEntity run) {
        runs.updateById(run);
    }

    public void finish(AgentRunEntity run, RunStatus status, String result, int rounds) {
        run.setStatus(status);
        run.setFailureCode(status == RunStatus.FAILED ? run.getFailureCode() : null);
        run.setResult(result);
        run.setRounds((run.getRounds() == null ? 0 : run.getRounds()) + rounds);
        run.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
        save(run);
    }

    /**
     * 仅在运行仍为 RUNNING 时结束根运行；已开始的取消优先转为 CANCELLED。
     */
    public void finishRoot(AgentRunEntity run, RunStatus status, String result, int rounds) {
        int totalRounds = (run.getRounds() == null ? 0 : run.getRounds()) + rounds;
        LocalDateTime finishedAt = LocalDateTime.now(ZoneOffset.UTC);
        int updated =
            runs.update(
                null,
                Wrappers.<AgentRunEntity>lambdaUpdate()
                    .set(AgentRunEntity::getStatus, status)
                    .set(
                        AgentRunEntity::getFailureCode,
                        status == RunStatus.FAILED ? run.getFailureCode() : null)
                    .set(AgentRunEntity::getResult, result)
                    .set(AgentRunEntity::getRounds, totalRounds)
                    .set(AgentRunEntity::getPromptTokens, orZero(run.getPromptTokens()))
                    .set(AgentRunEntity::getCompletionTokens, orZero(run.getCompletionTokens()))
                    .set(AgentRunEntity::getCachedTokens, orZero(run.getCachedTokens()))
                    .set(AgentRunEntity::getReasoningTokens, orZero(run.getReasoningTokens()))
                    .set(AgentRunEntity::getTtftMs, run.getTtftMs())
                    .set(AgentRunEntity::getModelTimeMs, orZero(run.getModelTimeMs()))
                    .set(AgentRunEntity::getToolTimeMs, orZero(run.getToolTimeMs()))
                    .set(AgentRunEntity::getToolCalls, orZero(run.getToolCalls()))
                    .set(AgentRunEntity::getFailedToolCalls, orZero(run.getFailedToolCalls()))
                    .set(AgentRunEntity::getModelName, run.getModelName())
                    .set(AgentRunEntity::getFinishedAt, finishedAt)
                    .eq(AgentRunEntity::getId, run.getId())
                    .eq(AgentRunEntity::getStatus, RunStatus.RUNNING));
        if (updated > 0) {
            run.setStatus(status);
            run.setFailureCode(status == RunStatus.FAILED ? run.getFailureCode() : null);
            run.setResult(result);
            run.setRounds(totalRounds);
            run.setFinishedAt(finishedAt);
            return;
        }

        String cancellationResult = "Agent 运行已取消";
        int cancelled =
            runs.update(
                null,
                Wrappers.<AgentRunEntity>lambdaUpdate()
                    .set(AgentRunEntity::getStatus, RunStatus.CANCELLED)
                    .set(AgentRunEntity::getFailureCode, null)
                    .set(AgentRunEntity::getResult, cancellationResult)
                    .set(AgentRunEntity::getRounds, totalRounds)
                    .set(AgentRunEntity::getPromptTokens, orZero(run.getPromptTokens()))
                    .set(AgentRunEntity::getCompletionTokens, orZero(run.getCompletionTokens()))
                    .set(AgentRunEntity::getCachedTokens, orZero(run.getCachedTokens()))
                    .set(AgentRunEntity::getReasoningTokens, orZero(run.getReasoningTokens()))
                    .set(AgentRunEntity::getTtftMs, run.getTtftMs())
                    .set(AgentRunEntity::getModelTimeMs, orZero(run.getModelTimeMs()))
                    .set(AgentRunEntity::getToolTimeMs, orZero(run.getToolTimeMs()))
                    .set(AgentRunEntity::getToolCalls, orZero(run.getToolCalls()))
                    .set(AgentRunEntity::getFailedToolCalls, orZero(run.getFailedToolCalls()))
                    .set(AgentRunEntity::getModelName, run.getModelName())
                    .set(AgentRunEntity::getFinishedAt, finishedAt)
                    .eq(AgentRunEntity::getId, run.getId())
                    .eq(AgentRunEntity::getStatus, RunStatus.CANCELLING));
        if (cancelled > 0) {
            run.setStatus(RunStatus.CANCELLED);
            run.setFailureCode(null);
            run.setResult(cancellationResult);
            run.setRounds(totalRounds);
            run.setFinishedAt(finishedAt);
            return;
        }

        AgentRunEntity current = runs.selectById(run.getId());
        if (current != null) {
            run.setStatus(current.getStatus());
            run.setFailureCode(current.getFailureCode());
            run.setResult(current.getResult());
            run.setRounds(current.getRounds());
            run.setFinishedAt(current.getFinishedAt());
        }
    }

    public void resume(AgentRunEntity run, String instruction) {
        run.setStatus(RunStatus.RUNNING);
        run.setFailureCode(null);
        run.setFinishedAt(null);
        if (instruction != null && !instruction.isBlank()) {
            run.setInstruction(run.getInstruction() + "\n\n继续要求：" + instruction);
        }
        save(run);
    }

    public void registerCancellationSignal(Long rootRunId, Runnable signal) {
        cancellationSignals.put(rootRunId, signal);
        if (isCancelled(rootRunId)) signal.run();
    }

    public void unregisterCancellationSignal(Long rootRunId) {
        cancellationSignals.remove(rootRunId);
    }

    public boolean isCancelled(Long rootRunId) {
        AgentRunEntity root = runs.selectById(rootRunId);
        return root != null && root.getStatus().cancelled();
    }

    public boolean requestChildCancellation(Long rootRunId, Long childRunId) {
        return runs.update(
            null,
            Wrappers.<AgentRunEntity>lambdaUpdate()
                .set(AgentRunEntity::getStatus, RunStatus.CANCELLING)
                .eq(AgentRunEntity::getId, childRunId)
                .eq(AgentRunEntity::getParentRunId, rootRunId)
                .eq(AgentRunEntity::getStatus, RunStatus.RUNNING))
            > 0;
    }

    public int cancel(Long actor, Long projectId, Long groupId, Long messageId) {
        var message = messages.getParticipantMessage(actor, projectId, groupId, messageId);
        if (!actor.toString().equals(message.getSenderId()))
            access.requireProjectAdmin(projectId, actor);
        List<AgentRunEntity> activeRoots =
            runs.selectList(
                Wrappers.<AgentRunEntity>lambdaQuery()
                    .eq(AgentRunEntity::getProjectId, projectId)
                    .eq(AgentRunEntity::getGroupId, groupId)
                    .eq(AgentRunEntity::getTriggerMessageId, messageId)
                    .isNull(AgentRunEntity::getParentRunId)
                    .eq(AgentRunEntity::getStatus, RunStatus.RUNNING));
        int cancelled =
            runs.update(
                null,
                Wrappers.<AgentRunEntity>lambdaUpdate()
                    .set(AgentRunEntity::getStatus, RunStatus.CANCELLING)
                    .eq(AgentRunEntity::getProjectId, projectId)
                    .eq(AgentRunEntity::getGroupId, groupId)
                    .eq(AgentRunEntity::getTriggerMessageId, messageId)
                    .isNull(AgentRunEntity::getParentRunId)
                    .eq(AgentRunEntity::getStatus, RunStatus.RUNNING));
        if (cancelled > 0 && activeRoots != null) {
            activeRoots.stream()
                .map(AgentRunEntity::getId)
                .map(cancellationSignals::get)
                .filter(java.util.Objects::nonNull)
                .forEach(Runnable::run);
        }
        return cancelled;
    }

    /**
     * 按根运行 ID 发起取消：供评测超时或内部调度使用，不依赖聊天触发消息。
     */
    public int cancelRoot(Long rootRunId) {
        int cancelled =
            runs.update(
                null,
                Wrappers.<AgentRunEntity>lambdaUpdate()
                    .set(AgentRunEntity::getStatus, RunStatus.CANCELLING)
                    .eq(AgentRunEntity::getId, rootRunId)
                    .isNull(AgentRunEntity::getParentRunId)
                    .eq(AgentRunEntity::getStatus, RunStatus.RUNNING));
        if (cancelled > 0) {
            Runnable signal = cancellationSignals.get(rootRunId);
            if (signal != null) signal.run();
        }
        return cancelled;
    }

    public AgentRunEntity findById(Long runId) {
        return runs.selectById(runId);
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
