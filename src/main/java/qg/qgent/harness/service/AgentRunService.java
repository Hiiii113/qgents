package qg.qgent.harness.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.harness.mapper.AgentRunMapper;
import qg.qgent.chat.service.MessageService;
import qg.qgent.chat.event.MessageSentEvent;
import qg.qgent.project.service.ProjectAccessService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按触发消息停止执行，执行状态保留在内部运行记录中。 */
@Service
@RequiredArgsConstructor
public class AgentRunService {
    private final AgentRunMapper runs;
    private final MessageService messages;
    private final ProjectAccessService access;
    private final Map<Long, Runnable> cancellationSignals = new ConcurrentHashMap<>();

    public AgentRunEntity startRoot(MessageSentEvent event, String instruction) {
        AgentRunEntity run = new AgentRunEntity();
        run.setProjectId(event.projectId());
        run.setGroupId(event.groupId());
        run.setTriggerMessageId(event.messageId());
        run.setCreatedBy(event.actor());
        run.setInstruction(instruction);
        run.setStatus("RUNNING");
        run.setRounds(0);
        run.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        runs.insert(run);
        return run;
    }

    public AgentRunEntity startChild(AgentRunEntity root, String instruction, Long workspaceId) {
        AgentRunEntity run = new AgentRunEntity();
        run.setProjectId(root.getProjectId());
        run.setGroupId(root.getGroupId());
        run.setTriggerMessageId(root.getTriggerMessageId());
        run.setParentRunId(root.getId());
        run.setWorkspaceId(workspaceId);
        run.setCreatedBy(root.getCreatedBy());
        run.setInstruction(instruction);
        run.setStatus("RUNNING");
        run.setRounds(0);
        run.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        runs.insert(run);
        return run;
    }

    public void save(AgentRunEntity run) {
        runs.updateById(run);
    }

    public void finish(AgentRunEntity run, String status, String result, int rounds) {
        run.setStatus(status);
        run.setResult(result);
        run.setRounds((run.getRounds() == null ? 0 : run.getRounds()) + rounds);
        run.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
        save(run);
    }

    /** 仅在运行仍为 RUNNING 时结束根运行；已开始的取消优先转为 CANCELLED。 */
    public void finishRoot(AgentRunEntity run, String status, String result, int rounds) {
        int totalRounds = (run.getRounds() == null ? 0 : run.getRounds()) + rounds;
        LocalDateTime finishedAt = LocalDateTime.now(ZoneOffset.UTC);
        int updated = runs.update(null, Wrappers.<AgentRunEntity>lambdaUpdate()
                .set(AgentRunEntity::getStatus, status)
                .set(AgentRunEntity::getResult, result)
                .set(AgentRunEntity::getRounds, totalRounds)
                .set(AgentRunEntity::getFinishedAt, finishedAt)
                .eq(AgentRunEntity::getId, run.getId())
                .eq(AgentRunEntity::getStatus, "RUNNING"));
        if (updated > 0) {
            run.setStatus(status);
            run.setResult(result);
            run.setRounds(totalRounds);
            run.setFinishedAt(finishedAt);
            return;
        }

        String cancellationResult = "Agent 运行已取消";
        int cancelled = runs.update(null, Wrappers.<AgentRunEntity>lambdaUpdate()
                .set(AgentRunEntity::getStatus, "CANCELLED")
                .set(AgentRunEntity::getResult, cancellationResult)
                .set(AgentRunEntity::getRounds, totalRounds)
                .set(AgentRunEntity::getFinishedAt, finishedAt)
                .eq(AgentRunEntity::getId, run.getId())
                .eq(AgentRunEntity::getStatus, "CANCELLING"));
        if (cancelled > 0) {
            run.setStatus("CANCELLED");
            run.setResult(cancellationResult);
            run.setRounds(totalRounds);
            run.setFinishedAt(finishedAt);
            return;
        }

        AgentRunEntity current = runs.selectById(run.getId());
        if (current != null) {
            run.setStatus(current.getStatus());
            run.setResult(current.getResult());
            run.setRounds(current.getRounds());
            run.setFinishedAt(current.getFinishedAt());
        }
    }

    public void resume(AgentRunEntity run, String instruction) {
        run.setStatus("RUNNING");
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
        return root != null && ("CANCELLING".equals(root.getStatus()) || "CANCELLED".equals(root.getStatus()));
    }

    public boolean requestChildCancellation(Long rootRunId, Long childRunId) {
        return runs.update(null, Wrappers.<AgentRunEntity>lambdaUpdate()
                .set(AgentRunEntity::getStatus, "CANCELLING")
                .eq(AgentRunEntity::getId, childRunId)
                .eq(AgentRunEntity::getParentRunId, rootRunId)
                .eq(AgentRunEntity::getStatus, "RUNNING")) > 0;
    }

    public int cancel(Long actor, Long projectId, Long groupId, Long messageId) {
        var message = messages.getMessage(actor, projectId, groupId, messageId);
        if (!actor.toString().equals(message.getSenderId())) access.requireProjectAdmin(projectId, actor);
        List<AgentRunEntity> activeRoots = runs.selectList(Wrappers.<AgentRunEntity>lambdaQuery()
                .eq(AgentRunEntity::getProjectId, projectId)
                .eq(AgentRunEntity::getGroupId, groupId)
                .eq(AgentRunEntity::getTriggerMessageId, messageId)
                .isNull(AgentRunEntity::getParentRunId)
                .eq(AgentRunEntity::getStatus, "RUNNING"));
        int cancelled = runs.update(null, Wrappers.<AgentRunEntity>lambdaUpdate()
                .set(AgentRunEntity::getStatus, "CANCELLING")
                .eq(AgentRunEntity::getProjectId, projectId)
                .eq(AgentRunEntity::getGroupId, groupId)
                .eq(AgentRunEntity::getTriggerMessageId, messageId)
                .isNull(AgentRunEntity::getParentRunId)
                .eq(AgentRunEntity::getStatus, "RUNNING"));
        if (cancelled > 0 && activeRoots != null) {
            activeRoots.stream().map(AgentRunEntity::getId).map(cancellationSignals::get)
                    .filter(java.util.Objects::nonNull).forEach(Runnable::run);
        }
        return cancelled;
    }
}
