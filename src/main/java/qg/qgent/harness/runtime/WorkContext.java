package qg.qgent.harness.runtime;

import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.chat.event.MessageSentEvent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 根运行自己的执行资源：Agent 身份、取消标记、工作区环境与工具集。子 Agent 由 {@link ChildRuns} 管理。
 */
final class WorkContext {
    final MessageSentEvent event;
    final Long agentId;
    // 当前运行的 Agent 配置：身份、职责与系统提示词来自它自己。
    final AgentEntity agent;
    final Long rootRunId;
    // 本次运行是否发生在私聊里：私聊没有群记忆，也不写共享的项目记忆。
    final boolean direct;
    final Object environmentLock = new Object();
    final Set<Long> openedWorkspaces = ConcurrentHashMap.newKeySet();
    final Map<Long, Object> toolsets = new ConcurrentHashMap<>();
    // 子 Agent 状态机需要根运行的取消标记，因此在两者都创建后挂上。
    ChildRuns children;
    volatile Long defaultWorkspaceId;

    private volatile boolean cancelled;

    WorkContext(
        MessageSentEvent event, Long agentId, AgentEntity agent, Long rootRunId, boolean direct) {
        this.event = event;
        this.agentId = agentId;
        this.agent = agent;
        this.rootRunId = rootRunId;
        this.direct = direct;
    }

    void cancel() {
        cancelled = true;
    }

    boolean isCancelled() {
        return cancelled;
    }
}
