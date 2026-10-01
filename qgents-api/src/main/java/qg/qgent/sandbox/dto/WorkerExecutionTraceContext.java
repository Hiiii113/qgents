package qg.qgent.sandbox.dto;

import java.util.UUID;

/** 当前同步 Agent loop 的运行身份，供 Worker 回执关联；子运行结束后恢复父身份。 */
public final class WorkerExecutionTraceContext {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private WorkerExecutionTraceContext() { }

    public static UUID currentAgentRunId() { return CURRENT.get(); }

    public static void set(UUID agentRunId) {
        if (agentRunId == null) CURRENT.remove();
        else CURRENT.set(agentRunId);
    }
}
