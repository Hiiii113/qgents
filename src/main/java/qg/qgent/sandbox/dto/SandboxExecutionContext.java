package qg.qgent.sandbox.dto;

import java.util.UUID;

/** 当前工具线程的 AgentRun 身份；并行调度显式传递，子运行结束后恢复父身份。 */
public final class SandboxExecutionContext {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private SandboxExecutionContext() { }

    public static UUID currentAgentRunId() { return CURRENT.get(); }

    public static void set(UUID agentRunId) {
        if (agentRunId == null) CURRENT.remove();
        else CURRENT.set(agentRunId);
    }
}
