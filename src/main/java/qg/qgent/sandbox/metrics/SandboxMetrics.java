package qg.qgent.sandbox.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Sandbox 生命周期与命令执行指标。
 */
@Component
public class SandboxMetrics {
    private final MeterRegistry registry;

    public SandboxMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public static SandboxMetrics noop() {
        return new SandboxMetrics(new SimpleMeterRegistry());
    }

    public void bindActiveSessions(Supplier<Number> activeSessions) {
        Gauge.builder("qgents.sandbox.sessions.active", activeSessions)
            .description("Currently active sandbox sessions held in memory")
            .register(registry);
    }

    public void bindActiveCommands(Supplier<Number> activeCommands) {
        Gauge.builder("qgents.sandbox.commands.active", activeCommands)
            .description("Currently active sandbox command executions")
            .register(registry);
    }

    public void recordAcquire(String outcome, long durationNanos) {
        Timer.builder("qgents.sandbox.acquire.duration")
            .description("Duration of acquiring or reusing a sandbox session")
            .tag("outcome", normalizeLabel(outcome))
            .publishPercentileHistogram()
            .register(registry)
            .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
    }

    public void recordReclaimed(String reason) {
        Counter.builder("qgents.sandbox.reclaimed.total")
            .description("Total sandbox sessions reclaimed or failed to destroy")
            .tag("reason", normalizeLabel(reason))
            .register(registry)
            .increment();
    }

    public void recordCommand(String outcome, long durationNanos) {
        Timer.builder("qgents.sandbox.command.duration")
            .description("Duration of sandbox command executions")
            .tag("outcome", normalizeLabel(outcome))
            .publishPercentileHistogram()
            .register(registry)
            .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
    }

    private static String normalizeLabel(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
