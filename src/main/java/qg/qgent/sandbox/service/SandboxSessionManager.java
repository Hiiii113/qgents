package qg.qgent.sandbox.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.sandbox.config.SandboxProperties;
import qg.qgent.sandbox.execution.service.CommandExecutionService;
import qg.qgent.sandbox.metrics.SandboxMetrics;
import qg.qgent.sandbox.runtime.SandboxAllocation;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.mapper.WorkspaceMapper;
import qg.qgent.workspace.service.WorkspaceService;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 首次代码工具调用时准备 Workspace 和沙箱；会话按工作区复用，由根 AgentRun 持有。
 * 同一 Workspace 的写入互斥只在本进程内通过会话缓存与初始化锁保证。
 */
@Slf4j
@Service
public class SandboxSessionManager {

    private final SandboxService sandboxes;
    private final ProjectDirectoryService projectDirectories;
    private final CommandExecutionService executions;
    private final SandboxProperties properties;
    private final WorkspaceMapper workspaceMapper;
    private final SandboxMetrics metrics;
    private final Map<Long, SandboxSession> sessions = new ConcurrentHashMap<>();
    private final Map<Long, ReentrantLock> acquireLocks = new ConcurrentHashMap<>();

    public SandboxSessionManager(
        SandboxService sandboxes,
        ProjectDirectoryService projectDirectories,
        CommandExecutionService executions,
        SandboxProperties properties,
        WorkspaceMapper workspaceMapper) {
        this(sandboxes, projectDirectories, executions, properties, workspaceMapper, SandboxMetrics.noop());
    }

    @Autowired
    public SandboxSessionManager(
        SandboxService sandboxes,
        ProjectDirectoryService projectDirectories,
        CommandExecutionService executions,
        SandboxProperties properties,
        WorkspaceMapper workspaceMapper,
        SandboxMetrics metrics) {
        this.sandboxes = sandboxes;
        this.projectDirectories = projectDirectories;
        this.executions = executions;
        this.properties = properties;
        this.workspaceMapper = workspaceMapper;
        this.metrics = metrics;
        this.metrics.bindActiveSessions(sessions::size);
    }

    /**
     * 为根 AgentRun 准备指定工作区的沙箱会话；同一持有者复用已有会话，
     * 其他 AgentRun 已占用时立即返回冲突，避免两个运行并发写同一工作区。
     */
    public SandboxSession acquire(Long agentRunId, Long projectId, Long workspaceId) {
        long startNanos = System.nanoTime();
        ReentrantLock acquireLock =
            acquireLocks.computeIfAbsent(workspaceId, ignored -> new ReentrantLock());
        // 初始化或释放已由其他线程处理时返回冲突，避免等待 沙箱初始化。
        if (!acquireLock.tryLock()) {
            metrics.recordAcquire("lease_conflict", System.nanoTime() - startNanos);
            throw new ApiException(
                HttpStatus.CONFLICT,
                "WORKSPACE_WRITE_LEASE_HELD",
                "Workspace is currently being initialized by another AgentRun");
        }
        try {
            SandboxSession existing = sessions.get(workspaceId);
            if (existing != null) {
                if (!existing.agentRunId().equals(agentRunId)) {
                    metrics.recordAcquire("lease_conflict", System.nanoTime() - startNanos);
                    throw new ApiException(
                        HttpStatus.CONFLICT,
                        "WORKSPACE_WRITE_LEASE_HELD",
                        "Workspace is currently being modified by another AgentRun");
                }
                metrics.recordAcquire("reused", System.nanoTime() - startNanos);
                return existing;
            }
            SandboxSession created = doAcquire(agentRunId, projectId, workspaceId);
            sessions.put(workspaceId, created);
            metrics.recordAcquire("created", System.nanoTime() - startNanos);
            return created;
        } catch (RuntimeException exception) {
            if (!(exception instanceof ApiException api
                && "WORKSPACE_WRITE_LEASE_HELD".equals(api.code()))) {
                metrics.recordAcquire("error", System.nanoTime() - startNanos);
            }
            throw exception;
        } finally {
            acquireLock.unlock();
        }
    }

    /**
     * 返回指定 Workspace 的当前会话；不存在时抛错，供 Sandbox 工具在调用工具前断言。
     */
    public SandboxSession require(Long workspaceId) {
        SandboxSession session = sessions.get(workspaceId);
        if (session == null) {
            throw new IllegalStateException("no sandbox session for workspace " + workspaceId);
        }
        return session;
    }

    /**
     * Stops only commands recorded against the given AgentRun and waits for their worker bodies to exit.
     */
    public void stopExecutionsByAgentRun(Long agentRunId) {
        if (agentRunId != null) executions.stopAndAwaitByAgentRun(agentRunId);
    }

    /**
     * Requests cancellation for one AgentRun without blocking its caller on command completion.
     */
    public void cancelExecutionsByAgentRun(Long agentRunId) {
        if (agentRunId != null) executions.cancelByAgentRun(agentRunId);
    }

    /**
     * 销毁会话对应的 Sandbox 并移除记录；Workspace 保留。销毁失败交给 Sandbox 后续清理，不阻断执行收尾。
     */
    public void release(Long workspaceId, Long agentRunId) {
        if (workspaceId == null || agentRunId == null) {
            return;
        }
        ReentrantLock acquireLock =
            acquireLocks.computeIfAbsent(workspaceId, ignored -> new ReentrantLock());
        // acquire() 失败后编排器仍会进入 finally；释放路径不能再次阻塞在同一把卡死的锁上。
        // 持锁者完成后会负责自己的会话清理，当前 AgentRun 没有可安全移除的会话时直接返回。
        if (!acquireLock.tryLock()) {
            return;
        }
        try {
            SandboxSession session = sessions.get(workspaceId);
            // acquire 可能因其他 AgentRun 已持有会话而失败；只能清理自己的会话。
            if (session == null || !agentRunId.equals(session.agentRunId())) {
                return;
            }
            if (!sessions.remove(workspaceId, session)) {
                return;
            }
            try {
                executions.stopAndAwaitBySandbox(session.sandboxId());
                sandboxes.destroy(session.sandboxId());
                metrics.recordReclaimed("release");
            } catch (RuntimeException ignored) {
                // 销毁失败由 Sandbox 的清理任务兜底，不阻断执行结果返回。
                metrics.recordReclaimed("destroy_error");
            }
        } finally {
            acquireLock.unlock();
        }
    }

    /**
     * 清理任务销毁过期 Sandbox 后同步移除对应会话缓存，避免残留会话继续占用 Workspace。
     */
    public void forgetSandbox(UUID sandboxId) {
        if (sandboxId == null) {
            return;
        }
        sessions.forEach((workspaceId, session) -> {
            if (sandboxId.equals(session.sandboxId()) && sessions.remove(workspaceId, session)) {
                log.info(
                    "sandbox session forgotten workspaceId={} sandboxId={}",
                    workspaceId,
                    sandboxId);
            }
        });
    }

    /**
     * 在整个 AgentRun 会话存活期间维持 Sandbox 租约，覆盖 LLM 推理等没有工具调用的空档。
     * 单个 Sandbox 的续租失败不影响其他会话；实际工具调用会继续得到 Sandbox 的明确错误。
     */
    @Scheduled(fixedDelayString = "${sandbox.lease-renew-interval:10s}")
    void renewActiveLeases() {
        sessions.forEach((workspaceId, session) -> {
            if (sessions.get(workspaceId) != session) {
                return;
            }
            try {
                sandboxes.renew(session.sandboxId(), null);
            } catch (RuntimeException exception) {
                // 当前工具调用会传播 Sandbox 错误；心跳不应阻塞其他活跃 Sandbox 的续租。
                log.debug(
                    "sandbox lease renew failed workspaceId={}: {}",
                    workspaceId,
                    exception.getMessage());
            }
        });
    }

    private SandboxSession doAcquire(Long agentRunId, Long projectId, Long workspaceId) {
        WorkspaceEntity workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null || !projectId.equals(workspace.getProjectId()))
            throw new IllegalArgumentException("工作区不存在");
        // 目录按需建立；同一项目同一仓库的 Git 数据在首次准备 worktree 时创建。
        projectDirectories.workspace(projectId, WorkspaceService.directoryName(workspace));
        String storageKey = WorkspaceService.storageKey(workspace);
        SandboxAllocation sandbox =
            sandboxes.create(
                UUID.randomUUID(), agentRunId, storageKey, properties.getImageProfile());
        return new SandboxSession(agentRunId, workspaceId, sandbox.getId(), storageKey);
    }
}
