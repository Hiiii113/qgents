package qg.qgent.harness.runtime;

import qg.qgent.chat.event.MessageSentEvent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 根运行自己的执行资源：Agent 身份、取消标记、工作区环境与工具集。子 Agent 由 {@link ChildRuns} 管理。 */
final class WorkContext {
    final MessageSentEvent event;
    final Long agentId;
    final Long rootRunId;
    final Object environmentLock = new Object();
    final Set<Long> openedWorkspaces = ConcurrentHashMap.newKeySet();
    final Map<Long, Object> toolsets = new ConcurrentHashMap<>();
    // 子 Agent 状态机需要根运行的取消标记，因此在两者都创建后挂上。
    ChildRuns children;
    volatile Long defaultWorkspaceId;

    private volatile boolean cancelled;

    WorkContext(MessageSentEvent event, Long agentId, Long rootRunId) {
        this.event = event;
        this.agentId = agentId;
        this.rootRunId = rootRunId;
    }

    void cancel() {
        cancelled = true;
    }

    boolean isCancelled() {
        return cancelled;
    }
}
