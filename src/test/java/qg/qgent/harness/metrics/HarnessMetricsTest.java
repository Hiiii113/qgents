package qg.qgent.harness.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import qg.qgent.harness.entity.RunStatus;
import qg.qgent.harness.model.ModelClient;
import qg.qgent.harness.model.ModelMessage;
import qg.qgent.harness.runtime.AgentLoop;
import qg.qgent.harness.tool.ToolCallback;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.support.TestIds;
import reactor.core.publisher.Mono;

class HarnessMetricsTest {

    @Test
    void recordsLlmTokensTtftAndNormalizedToolMetricsAcrossRounds() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HarnessMetrics metrics = new HarnessMetrics(registry);
        ModelClient model = mock(ModelClient.class);
        when(model.modelName()).thenReturn("deepseek-chat");

        ModelClient.Reply toolRound =
            new ModelClient.Reply(
                new ModelMessage.AssistantMessage(
                    "",
                    List.of(
                        new ModelMessage.ToolCall("c1", "mcp_1a_search_abcdef123456", "{}"),
                        new ModelMessage.ToolCall("c2", "missing_tool", "{}"),
                        new ModelMessage.ToolCall("c3", "read_file", "{}")),
                    null),
                "tool_calls",
                new ModelClient.Usage(100, 20, 60, 10));
        ModelClient.Reply finalRound =
            new ModelClient.Reply(
                new ModelMessage.AssistantMessage("done", List.of(), null),
                "stop",
                new ModelClient.Usage(150, 30, 80, 0));

        when(model.stream(any(), any()))
            .thenAnswer(inv -> Mono.just(toolRound))
            .thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                Consumer<String> delta = inv.getArgument(1);
                delta.accept("done");
                return Mono.just(finalRound);
            });

        ObjectMapper json = new ObjectMapper();
        List<ToolCallback> tools =
            List.of(
                new ToolCallback("mcp_1a_search_abcdef123456", "mcp", "{}", false, args -> "{\"ok\":true}"),
                new ToolCallback(
                    "read_file",
                    "read",
                    "{}",
                    false,
                    args -> {
                        try {
                            return json.writeValueAsString(ToolResult.error("NOT_FOUND", "missing"));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }));

        AgentLoop loop = new AgentLoop(model, json, metrics, 5, 4, Duration.ofSeconds(30));
        AgentLoop.Result result =
            loop.run(
                TestIds.next(),
                "system",
                List.of(new ModelMessage.UserMessage("hi")),
                () -> tools,
                () -> false);

        assertThat(result.success()).isTrue();
        assertThat(registry.get("qgents.llm.call.duration").tag("model", "deepseek-chat").tag("outcome", "ok").timer().count())
            .isEqualTo(2);
        assertThat(registry.get("qgents.llm.ttft").tag("model", "deepseek-chat").timer().count())
            .isEqualTo(1);
        assertThat(registry.get("qgents.llm.tokens.total").tag("model", "deepseek-chat").tag("type", "prompt").counter().count())
            .isEqualTo(250.0);
        assertThat(registry.get("qgents.llm.tokens.total").tag("model", "deepseek-chat").tag("type", "completion").counter().count())
            .isEqualTo(50.0);
        assertThat(registry.get("qgents.llm.tokens.total").tag("model", "deepseek-chat").tag("type", "cached").counter().count())
            .isEqualTo(140.0);
        assertThat(registry.get("qgents.llm.tokens.total").tag("model", "deepseek-chat").tag("type", "reasoning").counter().count())
            .isEqualTo(10.0);

        // MCP tool name is collapsed to "mcp" to avoid cardinality explosion.
        assertThat(registry.get("qgents.tool.calls.total").tag("tool", "mcp").tag("outcome", "ok").counter().count())
            .isEqualTo(1.0);
        assertThat(registry.get("qgents.tool.calls.total").tag("tool", "missing_tool").tag("outcome", "unknown").counter().count())
            .isEqualTo(1.0);
        assertThat(registry.get("qgents.tool.calls.total").tag("tool", "read_file").tag("outcome", "error").counter().count())
            .isEqualTo(1.0);
    }

    @Test
    void recordsTimeoutAndTruncatedLlmOutcomes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HarnessMetrics metrics = new HarnessMetrics(registry);
        ModelClient model = mock(ModelClient.class);
        when(model.modelName()).thenReturn("deepseek-chat");
        when(model.stream(any(), any()))
            .thenReturn(Mono.never())
            .thenReturn(
                Mono.just(
                    new ModelClient.Reply(
                        new ModelMessage.AssistantMessage("cut", List.of(), null),
                        "length",
                        new ModelClient.Usage(40, 10, 0, 0))));

        AgentLoop loop = new AgentLoop(model, new ObjectMapper(), metrics, 3, 2, Duration.ofMillis(50));
        AgentLoop.Result timedOut =
            loop.run(TestIds.next(), "sys", List.of(new ModelMessage.UserMessage("a")), List::of, () -> false);
        AgentLoop.Result truncated =
            loop.run(TestIds.next(), "sys", List.of(new ModelMessage.UserMessage("b")), List::of, () -> false);

        assertThat(timedOut.success()).isFalse();
        assertThat(truncated.success()).isFalse();
        assertThat(registry.get("qgents.llm.call.duration").tag("outcome", "timeout").timer().count())
            .isEqualTo(1);
        assertThat(registry.get("qgents.llm.call.duration").tag("outcome", "truncated").timer().count())
            .isEqualTo(1);
    }

    @Test
    void tracksActiveRunsAndFinishedRunDistributions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HarnessMetrics metrics = new HarnessMetrics(registry);

        long rootStart = metrics.runStarted(true);
        long childStart = metrics.runStarted(false);
        assertThat(registry.get("qgents.agent.runs.active").tag("role", "root").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("qgents.agent.runs.active").tag("role", "child").gauge().value()).isEqualTo(1.0);

        metrics.runFinished(false, RunStatus.SUCCEEDED, "deepseek-chat", 2, childStart);
        metrics.runFinished(true, RunStatus.SUCCEEDED, "deepseek-chat", 4, rootStart);

        assertThat(registry.get("qgents.agent.runs.active").tag("role", "root").gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("qgents.agent.runs.active").tag("role", "child").gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("qgents.agent.runs.total").tag("role", "root").tag("status", "succeeded").counter().count())
            .isEqualTo(1.0);
        assertThat(registry.get("qgents.agent.run.rounds").tag("role", "root").tag("status", "succeeded").summary().totalAmount())
            .isEqualTo(4.0);
    }
}
