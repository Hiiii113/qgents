package qg.qgent.harness.runtime;

import qg.qgent.harness.service.AgentRunService;
import qg.qgent.sandbox.service.SandboxSessionManager;
import qg.qgent.workspace.service.WorkSession;
import qg.qgent.workspace.service.WorkspaceService;

import java.util.Objects;

/**
 * 一次 Agent 对话的工作区会话：当前选择与目录由单个对话独占，沙箱会话由根运行持有、跨子 Agent 复用。
 * <p>
 * 首次调用代码工具时才建立目录与沙箱；运行取消时不再新建环境。
 */
final class WorkspaceSession implements WorkSession {
    private final WorkContext work;
    private final ChildRuns.Execution execution;
    private final WorkspaceService workspaces;
    private final SandboxSessionManager sessions;
    private final AgentRunService runs;
    private Long workspaceId;
    private String directory;

    WorkspaceSession(
        WorkContext work,
        ChildRuns.Execution execution,
        WorkspaceService workspaces,
        SandboxSessionManager sessions,
        AgentRunService runs) {
        this.work = work;
        this.execution = execution;
        this.workspaces = workspaces;
        this.sessions = sessions;
        this.runs = runs;
        this.workspaceId = execution.initialWorkspaceId;
        this.directory = WorkSession.resolveDirectory(".", execution.initialDirectory);
    }

    @Override
    public synchronized Long workspaceId() {
        return workspaceId;
    }

    @Override
    public synchronized String directory() {
        return directory;
    }

    @Override
    public Long ensure() {
        return ensure(null);
    }

    @Override
    public Long ensure(Long workspaceId) {
        synchronized (work.environmentLock) {
            if (work.isCancelled() || execution.isStopRequested()) {
                throw new IllegalStateException("Agent 运行已取消");
            }
            if (workspaceId == null) {
                workspaceId = workspaceId();
            }
            if (workspaceId == null) {
                if (work.defaultWorkspaceId == null) {
                    work.defaultWorkspaceId =
                        workspaces.defaultWorkspace(work.projectId).getId();
                }
                workspaceId = work.defaultWorkspaceId;
                bindDefault(workspaceId);
            }
            workspaces.require(work.projectId, workspaceId);
            if (!workspaceId.equals(execution.run.getWorkspaceId())) {
                execution.run.setWorkspaceId(workspaceId);
                runs.save(execution.run);
            }
            if (work.openedWorkspaces.add(workspaceId)) {
                try {
                    sessions.acquire(work.rootRunId, work.projectId, workspaceId);
                } catch (RuntimeException exception) {
                    work.openedWorkspaces.remove(workspaceId);
                    throw exception;
                }
            }
            return workspaceId;
        }
    }

    @Override
    public Long requireInitialized() {
        synchronized (work.environmentLock) {
            if (work.isCancelled() || execution.isStopRequested()) {
                throw new IllegalStateException("Agent 运行已取消");
            }
            Long id = workspaceId();
            if (id == null || !work.openedWorkspaces.contains(id)) {
                throw new IllegalArgumentException("请先通过代码工具或 run_command 初始化当前 Workspace");
            }
            return id;
        }
    }

    @Override
    public void select(Long workspaceId) {
        synchronized (this) {
            if (!Objects.equals(this.workspaceId, workspaceId)) {
                this.directory = ".";
            }
            this.workspaceId = workspaceId;
        }
        execution.run.setWorkspaceId(workspaceId);
        runs.save(execution.run);
    }

    @Override
    public synchronized void setDirectory(String directory) {
        this.directory = WorkSession.resolveDirectory(".", directory);
    }

    private synchronized void bindDefault(Long workspaceId) {
        if (this.workspaceId == null) {
            this.workspaceId = workspaceId;
        }
    }
}
