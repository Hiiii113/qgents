package qg.qgent.sandbox.dto;

/** 当前工具线程的 AgentRun 身份；并行调度显式传递，子运行结束后恢复父身份。 */
public final class SandboxExecutionContext {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private SandboxExecutionContext() {}

    public static Long currentAgentRunId() {
        return CURRENT.get();
    }

    public static void set(Long agentRunId) {
        if (agentRunId == null) CURRENT.remove();
        else CURRENT.set(agentRunId);
    }
}
