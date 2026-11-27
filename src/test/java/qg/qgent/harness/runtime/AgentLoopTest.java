package qg.qgent.harness.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import qg.qgent.harness.entity.FailureCode;
import qg.qgent.harness.model.ModelClient;
import qg.qgent.harness.model.ModelFailureException;
import qg.qgent.harness.model.ModelMessage;
import qg.qgent.harness.tool.ParallelTool;
import qg.qgent.harness.tool.Tool;
import qg.qgent.harness.tool.ToolCallback;
import qg.qgent.harness.tool.ToolParam;
import qg.qgent.harness.tool.ToolRegistry;
import qg.qgent.support.TestIds;
import reactor.core.publisher.Mono;

class AgentLoopTest {
    private final ModelClient model = mock(ModelClient.class);

    private AgentLoop loop(int rounds) {
        return loop(rounds, Duration.ofMinutes(5));
    }

    private AgentLoop loop(int rounds, Duration timeout) {
        return new AgentLoop(model, new ObjectMapper(), rounds, 4, timeout);
    }

    @Test
    void textReplyFinishesWithoutExecutingTools() {
        AgentLoop loop = loop(3);
        when(model.stream(any(), any())).thenReturn(reply("你好"));
        Counter tools = new Counter();
        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        "通用 Agent",
                        List.of(new ModelMessage.UserMessage("你好")),
                        () -> callbacks(tools),
                        () -> false);
        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("你好");
        assertThat(tools.calls).isZero();
        verify(model, times(1)).stream(any(), any());
    }

    @Test
    void executesToolThenPassesResultBackToModel() {
        AgentLoop loop = loop(3);
        when(model.stream(any(), any())).thenReturn(toolCall("count"), reply("完成"));
        Counter tools = new Counter();
        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        "通用 Agent",
                        List.of(new ModelMessage.UserMessage("执行")),
                        () -> callbacks(tools),
                        () -> false);
        assertThat(result.success()).isTrue();
        assertThat(tools.calls).isEqualTo(1);
        ArgumentCaptor<ModelClient.Request> requests =
                ArgumentCaptor.forClass(ModelClient.Request.class);
        verify(model, times(2)).stream(requests.capture(), any());
        assertThat(requests.getAllValues().get(1).messages())
                .anySatisfy(message -> {
                    if (message instanceof ModelMessage.ToolResponseMessage response) {
                        assertThat(response.responses().getFirst().id()).isEqualTo("call-1");
                        assertThat(response.responses().getFirst().responseData()).contains("1");
                    } else {
                        throw new AssertionError("需要工具结果消息");
                    }
                });
        assertThat(requests.getAllValues().get(1).messages().getLast())
                .isInstanceOf(ModelMessage.ToolResponseMessage.class);
        assertThat(requests.getAllValues().getFirst().tools())
                .extracting(ModelClient.ToolDefinition::name)
                .containsExactly("count");
    }

    @Test
    void continuingChildConversationKeepsEarlierToolResults() {
        AgentLoop loop = loop(4);
        when(model.stream(any(), any()))
                .thenReturn(toolCall("count"), reply("第一轮完成"), reply("补充后完成"));
        Counter tools = new Counter();
        AgentLoop.Conversation conversation =
                loop.conversation("临时子 Agent", List.of(new ModelMessage.UserMessage("检查代码")));

        assertThat(
                        loop.run(
                                        TestIds.next(),
                                        conversation,
                                        () -> callbacks(tools),
                                        () -> false)
                                .success())
                .isTrue();
        conversation.addUserMessage("再说明刚才工具返回的数字");
        AgentLoop.Result resumed =
                loop.run(
                        TestIds.next(),
                        conversation,
                        () -> callbacks(tools),
                        () -> false);

        assertThat(resumed.success()).isTrue();
        assertThat(resumed.text()).isEqualTo("补充后完成");
        ArgumentCaptor<ModelClient.Request> requests =
                ArgumentCaptor.forClass(ModelClient.Request.class);
        verify(model, times(3)).stream(requests.capture(), any());
        assertThat(requests.getAllValues().getLast().messages())
                .anySatisfy(
                        message -> assertThat(text(message)).contains("再说明刚才工具返回的数字"))
                .anySatisfy(message -> {
                    if (message instanceof ModelMessage.ToolResponseMessage response) {
                        assertThat(response.responses().getFirst().responseData()).contains("1");
                    } else {
                        throw new AssertionError("续接应保留此前工具结果");
                    }
                });
    }

    @Test
    void completionNoticeDrainedAtFinalBoundaryIsSentBackToModel() {
        AgentLoop loop = loop(4);
        when(model.stream(any(), any())).thenReturn(reply("仍有工作"), reply("已汇总"));
        AtomicInteger drains = new AtomicInteger();
        AgentLoop.UpdateSource updates =
                new AgentLoop.UpdateSource() {
                    public List<ModelMessage> drain() {
                        return drains.getAndIncrement() == 1
                                ? List.of(new ModelMessage.UserMessage("子 Agent 已完成"))
                                : List.of();
                    }

                    public List<ModelMessage> awaitNext() {
                        return List.of();
                    }
                };
        AgentLoop.Conversation conversation =
                loop.conversation("Qgent", List.of(new ModelMessage.UserMessage("工作")));

        AgentLoop.Result result =
                loop.run(TestIds.next(), conversation, List::of, () -> false, updates);

        assertThat(result.text()).isEqualTo("已汇总");
        ArgumentCaptor<ModelClient.Request> requests =
                ArgumentCaptor.forClass(ModelClient.Request.class);
        verify(model, times(2)).stream(requests.capture(), any());
        assertThat(requests.getAllValues().getLast().messages())
                .anySatisfy(message -> assertThat(text(message)).contains("子 Agent 已完成"));
    }

    @Test
    void repeatedToolCallsStopAtConfiguredLimit() {
        AgentLoop loop = loop(2);
        when(model.stream(any(), any())).thenReturn(toolCall("count"));
        Counter tools = new Counter();
        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        "通用 Agent",
                        List.of(new ModelMessage.UserMessage("执行")),
                        () -> callbacks(tools),
                        () -> false);
        assertThat(result.success()).isFalse();
        assertThat(result.text()).contains("最大调用轮次");
        assertThat(tools.calls).isEqualTo(2);
        verify(model, times(2)).stream(any(), any());
    }

    @Test
    void unknownToolReturnsErrorAndLetsModelRecover() {
        AgentLoop loop = loop(3);
        when(model.stream(any(), any())).thenReturn(toolCall("missing"), reply("已修正"));
        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        "通用 Agent",
                        List.of(new ModelMessage.UserMessage("执行")),
                        List::of,
                        () -> false);
        assertThat(result.success()).isTrue();
        ArgumentCaptor<ModelClient.Request> requests =
                ArgumentCaptor.forClass(ModelClient.Request.class);
        verify(model, times(2)).stream(requests.capture(), any());
        ModelMessage.ToolResponseMessage response =
                (ModelMessage.ToolResponseMessage) requests.getAllValues().get(1).messages().getLast();
        assertThat(response.responses().getFirst().responseData()).contains("TOOL_NOT_FOUND");
    }

    @Test
    void annotatedParallelResultsArePassedBackWithOriginalCallIds() {
        AgentLoop loop = loop(3);
        when(model.stream(any(), any()))
                .thenReturn(
                        toolCalls(
                                new ModelMessage.ToolCall("read-a", "read", "{}"),
                                new ModelMessage.ToolCall("read-b", "read", "{}")),
                        reply("完成"));
        var callbacks = callbacks(new ReadTools());
        var result =
                loop.run(
                        TestIds.next(),
                        "Agent",
                        List.of(new ModelMessage.UserMessage("读取")),
                        () -> callbacks,
                        () -> false);
        assertThat(result.success()).isTrue();
        ArgumentCaptor<ModelClient.Request> requests =
                ArgumentCaptor.forClass(ModelClient.Request.class);
        verify(model, times(2)).stream(requests.capture(), any());
        ModelMessage.ToolResponseMessage response =
                (ModelMessage.ToolResponseMessage) requests.getAllValues().get(1).messages().getLast();
        assertThat(response.responses())
                .extracting(r -> r.id())
                .containsExactly("read-a", "read-b");
        assertThat(response.responses())
                .allSatisfy(r -> assertThat(r.responseData()).contains("content"));
    }

    public static class ReadTools {
        @qg.qgent.harness.tool.ParallelTool
        @Tool(name = "read", description = "读取")
        public String read() {
            return "content";
        }
    }

    @Test
    void stagedImagesAreAppendedAfterFullToolResultsInCallOrder() {
        AgentLoop loop = loop(3);
        AgentLoop.Conversation conversation =
                loop.conversation("Agent", List.of(new ModelMessage.UserMessage("查看图片")));
        ImageTools tools = new ImageTools(conversation, () -> {});
        when(model.stream(any(), any()))
                .thenReturn(
                        toolCalls(
                                new ModelMessage.ToolCall(
                                        "call-1", "show_image", "{\"attachmentId\":\"7\"}"),
                                new ModelMessage.ToolCall(
                                        "call-2", "show_image", "{\"attachmentId\":\"8\"}")),
                        reply("已看到"));

        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        conversation,
                        () -> callbacks(tools),
                        () -> false);

        assertThat(result.success()).isTrue();
        ArgumentCaptor<ModelClient.Request> requests =
                ArgumentCaptor.forClass(ModelClient.Request.class);
        verify(model, times(2)).stream(requests.capture(), any());
        List<ModelMessage> instructions =
                requests.getAllValues().get(1).messages();
        assertThat(instructions.get(instructions.size() - 2))
                .isInstanceOf(ModelMessage.ToolResponseMessage.class);
        ModelMessage.UserMessage images = (ModelMessage.UserMessage) instructions.getLast();
        assertThat(images.text()).contains("附件 7 7.png", "附件 8 8.png");
        assertThat(images.images())
                .extracting(uri -> uri.toString())
                .containsExactly(dataUri("7"), dataUri("8"));
    }

    @Test
    void lastRoundDoesNotAppendStagedImagesAndClearsThem() {
        AgentLoop loop = loop(1);
        AgentLoop.Conversation conversation =
                loop.conversation("Agent", List.of(new ModelMessage.UserMessage("查看图片")));
        ImageTools tools = new ImageTools(conversation, () -> {});
        when(model.stream(any(), any()))
                .thenReturn(toolCall("show_image", "{\"attachmentId\":\"7\"}"));

        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        conversation,
                        () -> callbacks(tools),
                        () -> false);

        assertThat(result.success()).isFalse();
        assertThat(result.text()).contains("最大调用轮次");
        when(model.stream(any(), any())).thenReturn(reply("完成"));
        loop.run(
                TestIds.next(),
                conversation,
                () -> callbacks(tools),
                () -> false);
        ArgumentCaptor<ModelClient.Request> requests =
                ArgumentCaptor.forClass(ModelClient.Request.class);
        verify(model, times(2)).stream(requests.capture(), any());
        assertThat(requests.getAllValues().get(1).messages())
                .noneMatch(message ->
                        message instanceof ModelMessage.UserMessage user && !user.images().isEmpty());
    }

    @Test
    void cancellationAfterImageToolDropsStagedImages() {
        AgentLoop loop = loop(3);
        AgentLoop.Conversation conversation =
                loop.conversation("Agent", List.of(new ModelMessage.UserMessage("查看图片")));
        AtomicBoolean cancelled = new AtomicBoolean();
        ImageTools tools = new ImageTools(conversation, () -> cancelled.set(true));
        when(model.stream(any(), any()))
                .thenReturn(toolCall("show_image", "{\"attachmentId\":\"7\"}"));

        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        conversation,
                        () -> callbacks(tools),
                        cancelled::get);

        assertThat(result.success()).isFalse();
        assertThat(result.text()).contains("取消");
        verify(model, times(1)).stream(any(), any());
        cancelled.set(false);
        when(model.stream(any(), any())).thenReturn(reply("完成"));
        loop.run(
                TestIds.next(),
                conversation,
                () -> callbacks(tools),
                () -> false);
        ArgumentCaptor<ModelClient.Request> requests =
                ArgumentCaptor.forClass(ModelClient.Request.class);
        verify(model, times(2)).stream(requests.capture(), any());
        assertThat(requests.getAllValues().get(1).messages())
                .noneMatch(message ->
                        message instanceof ModelMessage.UserMessage user && !user.images().isEmpty());
    }

    @Test
    void stagedImagesDoNotLeakBetweenConversations() {
        AgentLoop loop = loop(4);
        AgentLoop.Conversation first =
                loop.conversation("Agent", List.of(new ModelMessage.UserMessage("第一张")));
        AgentLoop.Conversation second =
                loop.conversation("Agent", List.of(new ModelMessage.UserMessage("第二张")));
        ImageTools firstTools = new ImageTools(first, () -> {});
        ImageTools secondTools = new ImageTools(second, () -> {});
        when(model.stream(any(), any()))
                .thenReturn(
                        toolCall("show_image", "{\"attachmentId\":\"1\"}"), reply("一"),
                        toolCall("show_image", "{\"attachmentId\":\"2\"}"), reply("二"));

        loop.run(
                TestIds.next(),
                first,
                () -> callbacks(firstTools),
                () -> false);
        loop.run(
                TestIds.next(),
                second,
                () -> callbacks(secondTools),
                () -> false);

        ArgumentCaptor<ModelClient.Request> requests =
                ArgumentCaptor.forClass(ModelClient.Request.class);
        verify(model, times(4)).stream(requests.capture(), any());
        assertThat(
                        ((ModelMessage.UserMessage) requests.getAllValues().get(1).messages().getLast())
                                .text())
                .contains("附件 1")
                .doesNotContain("附件 2");
        assertThat(
                        ((ModelMessage.UserMessage) requests.getAllValues().get(3).messages().getLast())
                                .text())
                .contains("附件 2")
                .doesNotContain("附件 1");
    }

    public static class ImageTools {
        private final AgentLoop.Conversation conversation;
        private final Runnable afterRead;

        ImageTools(AgentLoop.Conversation conversation, Runnable afterRead) {
            this.conversation = conversation;
            this.afterRead = afterRead;
        }

        @Tool(name = "show_image", description = "显示图片")
        public String show(@ToolParam(description = "附件 ID") String attachmentId) {
            conversation.stageImage(
                    attachmentId, attachmentId + ".png", URI.create(dataUri(attachmentId)));
            afterRead.run();
            return "{\"ok\":true}";
        }
    }

    private static String dataUri(String attachmentId) {
        return "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(attachmentId.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void forwardsModelTextChunksInOrderAndKeepsAggregatedAnswer() {
        AgentLoop loop = loop(3);
        when(model.stream(any(), any()))
                .thenAnswer(
                        invocation -> {
                            Consumer<String> delta = invocation.getArgument(1);
                            delta.accept("你");
                            delta.accept("好");
                            delta.accept("！");
                            return reply("你好！");
                        });
        RecordingSink sink = new RecordingSink();
        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        loop.conversation("Agent", List.of(new ModelMessage.UserMessage("打招呼"))),
                        List::of,
                        () -> false,
                        AgentLoop.UpdateSource.NONE,
                        sink);

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("你好！");
        assertThat(sink.deltas).containsExactly("你", "好", "！");
        assertThat(sink.rounds).isEqualTo(1);
    }

    @Test
    void aggregatesStreamedToolCallAndReportsToolLifecycle() {
        AgentLoop loop = loop(3);
        when(model.stream(any(), any()))
                .thenReturn(
                        Mono.just(
                                new ModelClient.Reply(
                                        new ModelMessage.AssistantMessage(
                                                "先查一下",
                                                List.of(
                                                        new ModelMessage.ToolCall(
                                                                "call-9", "count", "{}")),
                                                null),
                                        "tool_calls")),
                        reply("完成"));
        Counter tools = new Counter();
        RecordingSink sink = new RecordingSink();
        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        loop.conversation("Agent", List.of(new ModelMessage.UserMessage("执行"))),
                        () -> callbacks(tools),
                        () -> false,
                        AgentLoop.UpdateSource.NONE,
                        sink);

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("完成");
        assertThat(sink.events)
                .contains("tool:started:call-9:count", "tool:finished:call-9:count:null");
        assertThat(sink.rounds).isEqualTo(2);
        ArgumentCaptor<ModelClient.Request> requests =
                ArgumentCaptor.forClass(ModelClient.Request.class);
        verify(model, times(2)).stream(requests.capture(), any());
        ModelMessage.ToolResponseMessage response =
                (ModelMessage.ToolResponseMessage) requests.getAllValues().get(1).messages().getLast();
        assertThat(response.responses().getFirst().id()).isEqualTo("call-9");
    }

    @Test
    void lengthTruncatedTextReplySucceedsWithPartialText() {
        AgentLoop loop = loop(3);
        when(model.stream(any(), any()))
                .thenReturn(
                        Mono.just(
                                new ModelClient.Reply(
                                        new ModelMessage.AssistantMessage(
                                                "写到一半被截断", List.of(), null),
                                        "LENGTH")));
        RecordingSink sink = new RecordingSink();

        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        loop.conversation("Agent", List.of(new ModelMessage.UserMessage("长文"))),
                        List::of,
                        () -> false,
                        AgentLoop.UpdateSource.NONE,
                        sink);

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("写到一半被截断");
        assertThat(sink.rounds).isEqualTo(1);
        verify(model, times(1)).stream(any(), any());
    }

    @Test
    void truncatedToolCallIsDiscardedAndGuidedRetrySucceeds() {
        AgentLoop loop = loop(3);
        when(model.stream(any(), any()))
                .thenReturn(truncatedToolCall("count"), reply("拆小后完成"));
        Counter tools = new Counter();

        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        "通用 Agent",
                        List.of(new ModelMessage.UserMessage("大改动")),
                        () -> callbacks(tools),
                        () -> false);

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("拆小后完成");
        assertThat(tools.calls).isZero();
        ArgumentCaptor<ModelClient.Request> requests =
                ArgumentCaptor.forClass(ModelClient.Request.class);
        verify(model, times(2)).stream(requests.capture(), any());
        List<ModelMessage> retry = requests.getAllValues().get(1).messages();
        assertThat(retry).noneMatch(ModelMessage.AssistantMessage.class::isInstance);
        assertThat(retry)
                .anySatisfy(
                        message ->
                                assertThat(text(message)).contains("max_tokens", "file_edit"));
    }

    @Test
    void truncationRecoveryStopsAfterConfiguredRetries() {
        AgentLoop loop = loop(5);
        when(model.stream(any(), any())).thenReturn(truncatedToolCall("count"));
        Counter tools = new Counter();

        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        "通用 Agent",
                        List.of(new ModelMessage.UserMessage("持续截断")),
                        () -> callbacks(tools),
                        () -> false);

        assertThat(result.success()).isFalse();
        assertThat(result.text()).contains("连续被截断");
        assertThat(result.failureCode()).isEqualTo(FailureCode.AGENT_FAILURE);
        assertThat(tools.calls).isZero();
        ArgumentCaptor<ModelClient.Request> requests =
                ArgumentCaptor.forClass(ModelClient.Request.class);
        // 第一次是原始回复，之后最多两次引导重试。
        verify(model, times(3)).stream(requests.capture(), any());
        assertThat(requests.getAllValues().get(2).messages())
                .filteredOn(
                        message ->
                                message instanceof ModelMessage.UserMessage user
                                        && user.text().contains("max_tokens"))
                .hasSize(2);
    }

    @Test
    void retriesRateLimitedModelCallOnceWhenNoTextWasEmitted() {
        AgentLoop loop = loop(3);
        when(model.stream(any(), any()))
                .thenReturn(upstreamFailure(FailureCode.UPSTREAM_RATE_LIMITED))
                .thenReturn(reply("重试成功"));
        RecordingSink sink = new RecordingSink();

        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        loop.conversation("Agent", List.of(new ModelMessage.UserMessage("你好"))),
                        List::of,
                        () -> false,
                        AgentLoop.UpdateSource.NONE,
                        sink);

        assertThat(result.success()).isTrue();
        assertThat(result.text()).isEqualTo("重试成功");
        assertThat(result.failureCode()).isNull();
        assertThat(result.rounds()).isEqualTo(1);
        assertThat(sink.rounds).isEqualTo(1);
        verify(model, times(2)).stream(any(), any());
    }

    @Test
    void retriesOnlyOnceAndKeepsTheSecondFailureCode() {
        AgentLoop loop = loop(3);
        when(model.stream(any(), any()))
                .thenReturn(upstreamFailure(FailureCode.UPSTREAM_RATE_LIMITED))
                .thenReturn(upstreamFailure(FailureCode.UPSTREAM_SERVER_ERROR));

        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        loop.conversation("Agent", List.of(new ModelMessage.UserMessage("你好"))),
                        List::of,
                        () -> false);

        assertThat(result.success()).isFalse();
        assertThat(result.text()).isEqualTo("模型服务暂时不可用，请稍后重试");
        assertThat(result.failureCode()).isEqualTo(FailureCode.UPSTREAM_SERVER_ERROR);
        verify(model, times(2)).stream(any(), any());
    }

    @Test
    void doesNotRetryUpstreamFailureAfterTextWasEmitted() {
        AgentLoop loop = loop(3);
        when(model.stream(any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> delta = invocation.getArgument(1);
                    return Mono.<ModelClient.Reply>create(sink -> {
                        delta.accept("部分正文");
                        sink.error(
                                new ModelFailureException(
                                        FailureCode.UPSTREAM_SERVER_ERROR,
                                        new IllegalStateException("503")));
                    });
                })
                .thenReturn(reply("不应重试"));
        RecordingSink sink = new RecordingSink();

        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        loop.conversation("Agent", List.of(new ModelMessage.UserMessage("你好"))),
                        List::of,
                        () -> false,
                        AgentLoop.UpdateSource.NONE,
                        sink);

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo(FailureCode.UPSTREAM_SERVER_ERROR);
        assertThat(sink.deltas).containsExactly("部分正文");
        verify(model, times(1)).stream(any(), any());
    }

    private static Mono<ModelClient.Reply> upstreamFailure(FailureCode code) {
        return Mono.error(new ModelFailureException(code, new IllegalStateException(code.name())));
    }

    @Test
    void cancellationDuringStreamStopsForwardingLaterChunks() {
        AgentLoop loop = loop(3);
        AtomicBoolean cancelled = new AtomicBoolean();
        when(model.stream(any(), any()))
                .thenAnswer(
                        invocation -> {
                            Consumer<String> delta = invocation.getArgument(1);
                            delta.accept("一");
                            cancelled.set(true);
                            delta.accept("二");
                            delta.accept("三");
                            return reply("一二三");
                        });
        RecordingSink sink = new RecordingSink();

        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        loop.conversation("Agent", List.of(new ModelMessage.UserMessage("停下"))),
                        List::of,
                        cancelled::get,
                        AgentLoop.UpdateSource.NONE,
                        sink);

        assertThat(result.success()).isFalse();
        assertThat(result.text()).contains("取消");
        assertThat(sink.deltas).containsExactly("一");
    }

    @Test
    void cancellationUnsubscribesEvenWhenModelSendsNoChunks() throws Exception {
        AgentLoop loop = loop(3);
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch upstreamCancelled = new CountDownLatch(1);
        when(model.stream(any(), any()))
                .thenReturn(Mono.<ModelClient.Reply>never()
                        .doOnRequest(ignored -> subscribed.countDown())
                        .doOnCancel(upstreamCancelled::countDown));

        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> loop.run(
                    TestIds.next(), "Agent", List.of(), List::of, cancelled::get));
            try {
                assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
                cancelled.set(true);

                assertThat(upstreamCancelled.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(running.get(1, TimeUnit.SECONDS))
                        .isEqualTo(new AgentLoop.Result(false, "Agent 运行已取消", 1));
            } finally {
                running.cancel(true);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(5)
    void modelTimeoutUnsubscribesAndDoesNotExecuteTools(boolean receivedText) {
        AgentLoop loop = loop(3, Duration.ofMillis(500));
        AtomicBoolean upstreamCancelled = new AtomicBoolean();
        when(model.stream(any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> delta = invocation.getArgument(1);
                    return Mono.<ModelClient.Reply>create(sink -> {
                        sink.onCancel(() -> upstreamCancelled.set(true));
                        if (receivedText) delta.accept("部分正文");
                    });
                });
        Counter tools = new Counter();

        AgentLoop.Result result = loop.run(
                TestIds.next(), "Agent", List.of(),
                () -> callbacks(tools), () -> false);

        assertThat(result.success()).isFalse();
        assertThat(result.text()).isEqualTo("模型调用超时，请稍后重试");
        assertThat(result.failureCode()).isEqualTo(FailureCode.UPSTREAM_TIMEOUT);
        assertThat(result.rounds()).isEqualTo(1);
        assertThat(upstreamCancelled).isTrue();
        assertThat(tools.calls).isZero();
        // 有正文时不再重试；无正文时整轮只重试一次。
        verify(model, times(receivedText ? 1 : 2)).stream(any(), any());
    }

    @Test
    void keepsReasoningContentForTheNextRound() {
        AgentLoop loop = loop(3);
        when(model.stream(any(), any()))
                .thenReturn(
                        Mono.just(
                                new ModelClient.Reply(
                                        new ModelMessage.AssistantMessage(
                                                "",
                                                List.of(
                                                        new ModelMessage.ToolCall(
                                                                "call-1", "count", "{}")),
                                                "思考"),
                                        "tool_calls")),
                        reply("完成"));
        RecordingSink sink = new RecordingSink();

        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        loop.conversation(
                                "Agent", List.of(new ModelMessage.UserMessage("想想"))),
                        () -> callbacks(new Counter()),
                        () -> false,
                        AgentLoop.UpdateSource.NONE,
                        sink);

        assertThat(result.success()).isTrue();
        assertThat(sink.deltas).doesNotContain("思", "考");
        ArgumentCaptor<ModelClient.Request> requests =
                ArgumentCaptor.forClass(ModelClient.Request.class);
        verify(model, times(2)).stream(requests.capture(), any());
        ModelMessage.AssistantMessage assistant =
                requests.getAllValues().get(1).messages().stream()
                        .filter(ModelMessage.AssistantMessage.class::isInstance)
                        .map(ModelMessage.AssistantMessage.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertThat(assistant.reasoningContent()).isEqualTo("思考");
        assertThat(assistant.toolCalls())
                .extracting(ModelMessage.ToolCall::id)
                .containsExactly("call-1");
    }

    private static String text(ModelMessage message) {
        return switch (message) {
            case ModelMessage.SystemMessage system -> system.text();
            case ModelMessage.UserMessage user -> user.text();
            case ModelMessage.AssistantMessage assistant -> assistant.text();
            case ModelMessage.ToolResponseMessage ignored -> "";
        };
    }

    static class RecordingSink implements RunStreamSink {
        final List<String> deltas = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        int rounds;

        public void roundStarted() {
            rounds++;
            events.add("round:" + rounds);
        }

        public void delta(String text) {
            deltas.add(text);
        }

        public void toolStarted(String toolCallId, String toolName, String input) {
            events.add("tool:started:" + toolCallId + ":" + toolName);
        }

        public void toolFinished(String toolCallId, String toolName, Boolean ok, String output) {
            events.add("tool:finished:" + toolCallId + ":" + toolName + ":" + ok);
        }

        public void finished() {
            events.add("finished");
        }
    }

    private Mono<ModelClient.Reply> reply(String text) {
        return Mono.just(
                new ModelClient.Reply(
                        new ModelMessage.AssistantMessage(text, List.of(), null), null));
    }

    @Test
    void cancellationWhileWaitingForModelPreventsToolExecution() {
        AgentLoop loop = loop(3);
        AtomicBoolean cancelled = new AtomicBoolean();
        when(model.stream(any(), any()))
                .thenAnswer(invocation -> {
                    cancelled.set(true);
                    return toolCall("count");
                });
        Counter tools = new Counter();
        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        "通用 Agent",
                        List.of(new ModelMessage.UserMessage("执行")),
                        () -> callbacks(tools),
                        cancelled::get);
        assertThat(result.success()).isFalse();
        assertThat(tools.calls).isZero();
        verify(model).stream(any(), any());
    }

    @Test
    void cancellationBetweenToolsStopsTheRemainingBatch() {
        AgentLoop loop = loop(3);
        when(model.stream(any(), any()))
                .thenReturn(
                        toolCalls(
                                new ModelMessage.ToolCall("call-1", "count", "{}"),
                                new ModelMessage.ToolCall("call-2", "count", "{}")));
        Counter tools = new Counter();
        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        "通用 Agent",
                        List.of(new ModelMessage.UserMessage("执行")),
                        () -> callbacks(tools),
                        () -> tools.calls > 0);
        assertThat(result.success()).isFalse();
        assertThat(tools.calls).isEqualTo(1);
        verify(model).stream(any(), any());
    }

    @Test
    void accumulatesUsageTtftAndToolMetricsAcrossRounds() {
        AgentLoop loop = loop(4);
        when(model.stream(any(), any()))
                .thenAnswer(
                        invocation ->
                                Mono.just(
                                        new ModelClient.Reply(
                                                new ModelMessage.AssistantMessage(
                                                        "",
                                                        List.of(
                                                                new ModelMessage.ToolCall(
                                                                        "call-1", "count", "{}"),
                                                                new ModelMessage.ToolCall(
                                                                        "call-2", "missing_tool", "{}")),
                                                        null),
                                                "tool_calls",
                                                new ModelClient.Usage(100, 20, 60, 5))))
                .thenAnswer(
                        invocation -> {
                            Consumer<String> delta = invocation.getArgument(1);
                            delta.accept("完成");
                            return Mono.just(
                                    new ModelClient.Reply(
                                            new ModelMessage.AssistantMessage("完成", List.of(), null),
                                            "stop",
                                            new ModelClient.Usage(140, 30, 100, 0)));
                        });
        Counter tools = new Counter();

        AgentLoop.Result result =
                loop.run(
                        TestIds.next(),
                        "通用 Agent",
                        List.of(new ModelMessage.UserMessage("统计")),
                        () -> callbacks(tools),
                        () -> false);

        assertThat(result.success()).isTrue();
        assertThat(result.metrics().promptTokens()).isEqualTo(240);
        assertThat(result.metrics().completionTokens()).isEqualTo(50);
        assertThat(result.metrics().cachedTokens()).isEqualTo(160);
        assertThat(result.metrics().reasoningTokens()).isEqualTo(5);
        assertThat(result.metrics().ttftMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(result.metrics().modelTimeMs()).isGreaterThanOrEqualTo(0L);
        assertThat(result.metrics().toolTimeMs()).isGreaterThanOrEqualTo(0L);
        assertThat(result.metrics().toolCalls()).isEqualTo(2);
        assertThat(result.metrics().failedToolCalls()).isEqualTo(1);
    }

    private Mono<ModelClient.Reply> truncatedToolCall(String name) {
        return Mono.just(
                new ModelClient.Reply(
                        new ModelMessage.AssistantMessage(
                                "",
                                List.of(new ModelMessage.ToolCall("call-1", name, "{\"arg\":")),
                                null),
                        "length"));
    }

    private Mono<ModelClient.Reply> toolCall(String name) {
        return toolCall(name, "{}");
    }

    private Mono<ModelClient.Reply> toolCall(String name, String arguments) {
        return toolCalls(new ModelMessage.ToolCall("call-1", name, arguments));
    }

    private Mono<ModelClient.Reply> toolCalls(ModelMessage.ToolCall... calls) {
        return Mono.just(
                new ModelClient.Reply(
                        new ModelMessage.AssistantMessage("", List.of(calls), null), null));
    }

    private static List<ToolCallback> callbacks(Object... tools) {
        ToolRegistry registry = new ToolRegistry(new ObjectMapper());
        registry.register(tools);
        return registry.callbacks();
    }

    public static class Counter {
        int calls;

        @Tool(name = "count", description = "计数")
        public int count() {
            return ++calls;
        }
    }
}
