package qg.qgent.harness.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;
import qg.qgent.harness.entity.RunStatus;
import qg.qgent.harness.model.ModelClient;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Harness 核心业务指标：Agent 运行、模型调用与工具执行。
 * <p>
 * 所有 Tag 严格限制为低基数枚举，不放入 projectId、groupId、runId 等高基数标识。
 */
@Component
public class HarnessMetrics {
    private static final String MCP_TOOL_PREFIX = "mcp_";

    private final MeterRegistry registry;
    private final AtomicInteger activeRootRuns = new AtomicInteger();
    private final AtomicInteger activeChildRuns = new AtomicInteger();

    public HarnessMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("qgents.agent.runs.active", activeRootRuns, AtomicInteger::get)
            .description("Active root Agent runs")
            .tag("role", "root")
            .register(registry);
        Gauge.builder("qgents.agent.runs.active", activeChildRuns, AtomicInteger::get)
            .description("Active child Agent runs")
            .tag("role", "child")
            .register(registry);
    }

    public static HarnessMetrics noop() {
        return new HarnessMetrics(new SimpleMeterRegistry());
    }

    public long runStarted(boolean root) {
        activeCounter(root).incrementAndGet();
        return System.nanoTime();
    }

    public void runFinished(
        boolean root, RunStatus status, String model, int rounds, long startNanos) {
        activeCounter(root).updateAndGet(v -> Math.max(0, v - 1));
        String role = root ? "root" : "child";
        String statusTag = status == null ? "unknown" : status.name().toLowerCase(Locale.ROOT);
        String modelTag = normalizeLabel(model);
        long durationNanos = Math.max(0L, System.nanoTime() - startNanos);

        Counter.builder("qgents.agent.runs.total")
            .description("Total finished Agent runs")
            .tag("role", role)
            .tag("status", statusTag)
            .tag("model", modelTag)
            .register(registry)
            .increment();

        Timer.builder("qgents.agent.run.duration")
            .description("End-to-end duration of an Agent run")
            .tag("role", role)
            .tag("status", statusTag)
            .publishPercentileHistogram()
            .register(registry)
            .record(durationNanos, TimeUnit.NANOSECONDS);

        DistributionSummary.builder("qgents.agent.run.rounds")
            .description("Model rounds consumed per Agent run")
            .tag("role", role)
            .tag("status", statusTag)
            .register(registry)
            .record(Math.max(0, rounds));
    }

    public void recordLlmCall(
        String model, String outcome, long durationNanos, ModelClient.Usage usage) {
        String modelTag = normalizeLabel(model);
        Timer.builder("qgents.llm.call.duration")
            .description("Duration of one LLM streaming call")
            .tag("model", modelTag)
            .tag("outcome", normalizeLabel(outcome))
            .publishPercentileHistogram()
            .register(registry)
            .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);

        if (usage == null) return;
        incrementTokens(modelTag, "prompt", usage.promptTokens());
        incrementTokens(modelTag, "completion", usage.completionTokens());
        incrementTokens(modelTag, "cached", usage.cachedTokens());
        incrementTokens(modelTag, "reasoning", usage.reasoningTokens());
    }

    public void recordLlmTtft(String model, long ttftMs) {
        Timer.builder("qgents.llm.ttft")
            .description("Time to first token for an LLM call")
            .tag("model", normalizeLabel(model))
            .publishPercentileHistogram()
            .register(registry)
            .record(Math.max(0L, ttftMs), TimeUnit.MILLISECONDS);
    }

    public void recordToolCall(String rawToolName, String outcome, long durationNanos) {
        String toolTag = normalizeToolName(rawToolName);
        Counter.builder("qgents.tool.calls.total")
            .description("Total Agent tool invocations")
            .tag("tool", toolTag)
            .tag("outcome", normalizeLabel(outcome))
            .register(registry)
            .increment();

        Timer.builder("qgents.tool.call.duration")
            .description("Duration of one Agent tool invocation")
            .tag("tool", toolTag)
            .publishPercentileHistogram()
            .register(registry)
            .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
    }

    /**
     * 外部 MCP 工具名含动态 serverId 与哈希，统一折叠为 mcp 以避免高基数。
     */
    public static String normalizeToolName(String rawToolName) {
        if (rawToolName == null || rawToolName.isBlank()) return "unknown";
        if (rawToolName.startsWith(MCP_TOOL_PREFIX)) return "mcp";
        return rawToolName;
    }

    private void incrementTokens(String model, String type, long count) {
        if (count <= 0) return;
        Counter.builder("qgents.llm.tokens.total")
            .description("Total LLM tokens consumed")
            .tag("model", model)
            .tag("type", type)
            .register(registry)
            .increment(count);
    }

    private AtomicInteger activeCounter(boolean root) {
        return root ? activeRootRuns : activeChildRuns;
    }

    private static String normalizeLabel(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
