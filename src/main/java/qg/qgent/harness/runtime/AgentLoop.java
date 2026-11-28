package qg.qgent.harness.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import qg.qgent.harness.entity.FailureCode;
import qg.qgent.harness.model.ModelClient;
import qg.qgent.harness.model.ModelFailureException;
import qg.qgent.harness.model.ModelMessage;
import qg.qgent.harness.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 所有 Agent 共用的循环：工具结果回给模型，普通文本结束本次运行。
 */
@Component
public class AgentLoop {
    private static final Duration CANCELLATION_CHECK_INTERVAL = Duration.ofMillis(100);
    // 截断的工具调用参数不完整，执行只会得到误导性错误；先让模型自己缩小一步再重试。
    private static final int MAX_TRUNCATION_RECOVERIES = 2;
    private static final String TRUNCATION_RECOVERY_GUIDE =
        "上一轮回复超过了 max_tokens 被截断，其中不完整的工具调用已丢弃且没有执行。"
            + "请把较大的修改或输出拆成更小的步骤后重试，例如把 file_edit 拆成多次小块修改，"
            + "不要在一次工具调用里写完整个大文件。";
    private final ModelClient model;
    private final ToolCallExecutor toolCalls;
    private final int maxRounds;
    private final Duration modelCallTimeout;

    public AgentLoop(
        ModelClient model,
        ObjectMapper json,
        @Value("${app.agent.max-rounds:30}") int maxRounds,
        @Value("${app.agent.max-parallel-tool-calls:4}") int maxParallelToolCalls,
        @Value("${app.agent.model-call-timeout:5m}") Duration modelCallTimeout) {
        this.model = model;
        this.toolCalls = new ToolCallExecutor(json, maxParallelToolCalls);
        if (maxRounds < 1) throw new IllegalArgumentException("max-rounds 必须大于 0");
        if (modelCallTimeout.isZero() || modelCallTimeout.isNegative())
            throw new IllegalArgumentException("model-call-timeout 必须大于 0");
        this.maxRounds = maxRounds;
        this.modelCallTimeout = modelCallTimeout;
    }

    /**
     * Creates a reusable, independent model conversation for a delegated child run.
     */
    public Conversation conversation(String systemPrompt, List<ModelMessage> initialHistory) {
        return new Conversation(systemPrompt, initialHistory);
    }

    /**
     * 逐轮调用模型，将工具结果加入历史；取消、轮次耗尽或模型异常回复时停止。
     */
    public Result run(
        Long runId,
        String systemPrompt,
        List<ModelMessage> initialHistory,
        Supplier<List<ToolCallback>> availableTools,
        BooleanSupplier cancelled) {
        return run(
            runId,
            conversation(systemPrompt, initialHistory),
            availableTools,
            cancelled,
            UpdateSource.NONE,
            RunStreamSink.NONE);
    }

    /**
     * Continues a saved conversation, retaining earlier user, assistant, and tool messages.
     */
    public Result run(
        Long runId,
        Conversation conversation,
        Supplier<List<ToolCallback>> availableTools,
        BooleanSupplier cancelled) {
        return run(runId, conversation, availableTools, cancelled, UpdateSource.NONE);
    }

    /**
     * Runs a conversation that can receive asynchronous completion notices from delegated work.
     */
    public Result run(
        Long runId,
        Conversation conversation,
        Supplier<List<ToolCallback>> availableTools,
        BooleanSupplier cancelled,
        UpdateSource updates) {
        return run(runId, conversation, availableTools, cancelled, updates, RunStreamSink.NONE);
    }

    public String modelName() {
        return model.modelName();
    }

    /**
     * 运行一段对话，并把可展示的正文和工具阶段同步给实时层。
     */
    public Result run(
        Long runId,
        Conversation conversation,
        Supplier<List<ToolCallback>> availableTools,
        BooleanSupplier cancelled,
        UpdateSource updates,
        RunStreamSink sink) {
        long loopStartNanos = System.nanoTime();
        AtomicReference<Long> firstTokenMs = new AtomicReference<>();
        ModelClient.Usage totalUsage = ModelClient.Usage.ZERO;
        long modelTimeMs = 0;
        long toolTimeMs = 0;
        int totalToolCalls = 0;
        int failedToolCalls = 0;
        int truncationRecoveries = 0;
        try {
            for (int round = 1; round <= maxRounds; round++) {
                if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
                    return new Result(
                        false,
                        "Agent 运行已取消",
                        round - 1,
                        snapshot(totalUsage, firstTokenMs.get(), modelTimeMs, toolTimeMs, totalToolCalls, failedToolCalls));
                }
                conversation.appendAll(updates.drain());
                List<ToolCallback> tools = availableTools.get();
                Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
                for (ToolCallback tool : tools) {
                    if (callbacks.putIfAbsent(tool.name(), tool) != null) {
                        throw new IllegalArgumentException("重复工具名：" + tool.name());
                    }
                }
                List<ModelMessage> messages = new ArrayList<>();
                messages.add(new ModelMessage.SystemMessage(conversation.systemPrompt()));
                messages.addAll(conversation.history());
                sink.roundStarted();
                ModelClient.Reply reply;
                long modelStartNanos = System.nanoTime();
                ModelCall call =
                    callModel(
                        request(tools, messages), sink, cancelled, loopStartNanos, firstTokenMs);
                modelTimeMs += elapsedMs(modelStartNanos);
                if (call.failureCode() != null) {
                    return new Result(
                        false,
                        failureText(call.failureCode()),
                        round,
                        snapshot(totalUsage, firstTokenMs.get(), modelTimeMs, toolTimeMs, totalToolCalls, failedToolCalls),
                        call.failureCode());
                }
                reply = call.reply();
                if (reply != null) {
                    totalUsage = totalUsage.plus(reply.usage());
                }
                if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
                    return new Result(
                        false,
                        "Agent 运行已取消",
                        round,
                        snapshot(totalUsage, firstTokenMs.get(), modelTimeMs, toolTimeMs, totalToolCalls, failedToolCalls));
                }
                if (reply == null || reply.message() == null) {
                    return new Result(
                        false,
                        "模型没有返回有效回复",
                        round,
                        snapshot(totalUsage, firstTokenMs.get(), modelTimeMs, toolTimeMs, totalToolCalls, failedToolCalls),
                        FailureCode.AGENT_FAILURE);
                }
                ModelMessage.AssistantMessage assistant = reply.message();
                if ("length".equalsIgnoreCase(reply.finishReason())) {
                    String text = assistant.text();
                    if (assistant.toolCalls().isEmpty() && text != null && !text.isBlank()) {
                        conversation.append(assistant);
                        return new Result(
                            true,
                            text,
                            round,
                            snapshot(totalUsage, firstTokenMs.get(), modelTimeMs, toolTimeMs, totalToolCalls, failedToolCalls));
                    }
                    if (truncationRecoveries >= MAX_TRUNCATION_RECOVERIES) {
                        return new Result(
                            false,
                            "模型回复连续被截断（已重试 " + MAX_TRUNCATION_RECOVERIES + " 次），请缩小问题范围后重试",
                            round,
                            snapshot(totalUsage, firstTokenMs.get(), modelTimeMs, toolTimeMs, totalToolCalls, failedToolCalls),
                            FailureCode.AGENT_FAILURE);
                    }
                    truncationRecoveries++;
                    conversation.append(new ModelMessage.UserMessage(TRUNCATION_RECOVERY_GUIDE));
                    continue;
                }
                if (assistant.toolCalls().isEmpty()) {
                    String text = assistant.text();
                    if (text == null || text.isBlank()) {
                        return new Result(
                            false,
                            "模型返回了空回复",
                            round,
                            snapshot(totalUsage, firstTokenMs.get(), modelTimeMs, toolTimeMs, totalToolCalls, failedToolCalls),
                            FailureCode.AGENT_FAILURE);
                    }
                    conversation.append(assistant);
                    List<ModelMessage> arrivedBeforeWait = updates.drain();
                    conversation.appendAll(arrivedBeforeWait);
                    List<ModelMessage> completedWork = updates.awaitNext();
                    if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
                        return new Result(
                            false,
                            "Agent 运行已取消",
                            round,
                            snapshot(totalUsage, firstTokenMs.get(), modelTimeMs, toolTimeMs, totalToolCalls, failedToolCalls));
                    }
                    if (!arrivedBeforeWait.isEmpty() || !completedWork.isEmpty()) {
                        conversation.appendAll(completedWork);
                        continue;
                    }
                    return new Result(
                        true,
                        text,
                        round,
                        snapshot(totalUsage, firstTokenMs.get(), modelTimeMs, toolTimeMs, totalToolCalls, failedToolCalls));
                }
                conversation.append(assistant);
                long toolStartNanos = System.nanoTime();
                ToolCallExecutor.Result results =
                    toolCalls.execute(
                        runId, assistant.toolCalls(), callbacks, cancelled, sink);
                toolTimeMs += elapsedMs(toolStartNanos);
                totalToolCalls += results.totalCalls();
                failedToolCalls += results.failedCalls();
                if (results.cancelled()) {
                    return new Result(
                        false,
                        "Agent 运行已取消",
                        round,
                        snapshot(totalUsage, firstTokenMs.get(), modelTimeMs, toolTimeMs, totalToolCalls, failedToolCalls));
                }
                conversation.append(new ModelMessage.ToolResponseMessage(results.responses()));
                // 图片必须在完整工具结果之后追加，且只在确实还有下一轮模型调用时发送。
                if (round < maxRounds) conversation.appendAll(conversation.takeStagedImages());
            }
            return new Result(
                false,
                "Agent 已达到最大调用轮次（" + maxRounds + "），请缩小问题范围后重试",
                maxRounds,
                snapshot(totalUsage, firstTokenMs.get(), modelTimeMs, toolTimeMs, totalToolCalls, failedToolCalls),
                FailureCode.AGENT_FAILURE);
        } finally {
            conversation.clearStagedImages();
        }
    }

    private static Metrics snapshot(
        ModelClient.Usage usage,
        Long ttftMs,
        long modelTimeMs,
        long toolTimeMs,
        int toolCalls,
        int failedToolCalls) {
        return new Metrics(
            usage.promptTokens(),
            usage.completionTokens(),
            usage.cachedTokens(),
            usage.reasoningTokens(),
            ttftMs,
            modelTimeMs,
            toolTimeMs,
            toolCalls,
            failedToolCalls);
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private ModelClient.Request request(List<ToolCallback> tools, List<ModelMessage> messages) {
        return new ModelClient.Request(
            messages,
            tools.stream()
                .map(tool -> new ModelClient.ToolDefinition(
                    tool.name(), tool.description(), tool.inputSchema()))
                .toList());
    }

    /**
     * 一轮模型调用：上游可重试失败且本轮尚未输出正文时，同一轮只重试一次。
     */
    private ModelCall callModel(
        ModelClient.Request request,
        RunStreamSink sink,
        BooleanSupplier cancelled,
        long loopStartNanos,
        AtomicReference<Long> firstTokenMs) {
        AtomicBoolean emitted = new AtomicBoolean();
        try {
            return new ModelCall(
                chat(request, sink, cancelled, loopStartNanos, firstTokenMs, emitted), null);
        } catch (RuntimeException exception) {
            FailureCode code = failureCode(exception);
            if (code == null) throw exception;
            if (!code.upstreamRetryable() || emitted.get() || stopped(cancelled)) {
                return new ModelCall(null, code);
            }
            try {
                return new ModelCall(
                    chat(request, sink, cancelled, loopStartNanos, firstTokenMs, emitted), null);
            } catch (RuntimeException retryException) {
                FailureCode retryCode = failureCode(retryException);
                if (retryCode == null) throw retryException;
                return new ModelCall(null, retryCode);
            }
        }
    }

    private static FailureCode failureCode(RuntimeException exception) {
        if (exception instanceof ModelFailureException modelFailure) return modelFailure.code();
        if (exception instanceof ModelCallTimeoutException) return FailureCode.UPSTREAM_TIMEOUT;
        return null;
    }

    private static String failureText(FailureCode code) {
        return switch (code) {
            case UPSTREAM_RATE_LIMITED -> "模型服务限流，请稍后重试";
            case UPSTREAM_SERVER_ERROR -> "模型服务暂时不可用，请稍后重试";
            case UPSTREAM_TIMEOUT -> "模型调用超时，请稍后重试";
            case AGENT_FAILURE -> "Agent 执行失败，请稍后重试";
        };
    }

    private static boolean stopped(BooleanSupplier cancelled) {
        return Thread.currentThread().isInterrupted() || cancelled.getAsBoolean();
    }

    /**
     * 消费模型流：正文增量交给实时层，聚合后的助手消息与结束原因由模型客户端给出。
     */
    private ModelClient.Reply chat(
        ModelClient.Request request,
        RunStreamSink sink,
        BooleanSupplier cancelled,
        long loopStartNanos,
        AtomicReference<Long> firstTokenMs,
        AtomicBoolean emitted) {
        Mono<Long> cancellation = Flux.interval(CANCELLATION_CHECK_INTERVAL)
            .filter(tick -> cancelled.getAsBoolean())
            .next();
        return model.stream(
                request,
                text -> {
                    if (text != null && !text.isEmpty()) {
                        firstTokenMs.compareAndSet(null, elapsedMs(loopStartNanos));
                        emitted.set(true);
                    }
                    // 思考内容不进正文流；取消后立即停止推送。
                    if (!cancelled.getAsBoolean()) sink.delta(text);
                })
            // 约束的是整轮调用，不会被新分片重置。
            .timeout(modelCallTimeout, Mono.error(ModelCallTimeoutException::new))
            .takeUntilOther(cancellation)
            .block();
    }

    private record ModelCall(ModelClient.Reply reply, FailureCode failureCode) {}

    private static final class ModelCallTimeoutException extends RuntimeException {}

    public record Metrics(
        long promptTokens,
        long completionTokens,
        long cachedTokens,
        long reasoningTokens,
        Long ttftMs,
        long modelTimeMs,
        long toolTimeMs,
        int toolCalls,
        int failedToolCalls) {
        public static final Metrics ZERO = new Metrics(0, 0, 0, 0, null, 0, 0, 0, 0);

        public Metrics plus(Metrics other) {
            if (other == null) return this;
            return new Metrics(
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                cachedTokens + other.cachedTokens,
                reasoningTokens + other.reasoningTokens,
                ttftMs != null ? ttftMs : other.ttftMs,
                modelTimeMs + other.modelTimeMs,
                toolTimeMs + other.toolTimeMs,
                toolCalls + other.toolCalls,
                failedToolCalls + other.failedToolCalls);
        }
    }

    public record Result(boolean success, String text, int rounds, Metrics metrics, FailureCode failureCode) {
        public Result {
            metrics = metrics == null ? Metrics.ZERO : metrics;
        }

        public Result(boolean success, String text, int rounds) {
            this(success, text, rounds, Metrics.ZERO, null);
        }

        public Result(boolean success, String text, int rounds, Metrics metrics) {
            this(success, text, rounds, metrics, null);
        }

        // 耗时与用量属于运行观测数据，不参与结果值相等性比较。
        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Result that)) return false;
            return success == that.success
                && rounds == that.rounds
                && failureCode == that.failureCode
                && java.util.Objects.equals(text, that.text);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(success, text, rounds, failureCode);
        }
    }

    /**
     * Supplies child completion messages to the main loop without polling storage.
     */
    public interface UpdateSource {
        UpdateSource NONE =
            new UpdateSource() {
                public List<ModelMessage> drain() {
                    return List.of();
                }

                public List<ModelMessage> awaitNext() {
                    return List.of();
                }
            };

        List<ModelMessage> drain();

        List<ModelMessage> awaitNext();
    }

    /**
     * A conversation can be resumed during the current root run without copying another agent's history.
     */
    public static final class Conversation {
        private final String systemPrompt;
        private final List<ModelMessage> history;
        private final List<StagedImage> stagedImages = new ArrayList<>();

        private Conversation(String systemPrompt, List<ModelMessage> initialHistory) {
            this.systemPrompt = systemPrompt;
            this.history = new ArrayList<>(initialHistory);
        }

        public synchronized void addUserMessage(String text) {
            history.add(new ModelMessage.UserMessage(text));
        }

        /**
         * 图片暂存在各自会话中，由 loop 在工具结果之后追加。
         */
        public synchronized void stageImage(String attachmentId, String fileName, URI imageUri) {
            stagedImages.add(new StagedImage(attachmentId, fileName, imageUri));
        }

        private synchronized List<ModelMessage> takeStagedImages() {
            if (stagedImages.isEmpty()) return List.of();
            StringBuilder text = new StringBuilder("以下图片由 read_attachment 读取，按工具调用顺序附在本消息中：\n");
            List<URI> images = new ArrayList<>();
            for (StagedImage image : stagedImages) {
                text.append("- 附件 ").append(image.attachmentId());
                if (image.fileName() != null && !image.fileName().isBlank()) {
                    text.append(' ').append(image.fileName());
                }
                text.append('\n');
                images.add(image.imageUri());
            }
            stagedImages.clear();
            return List.of(new ModelMessage.UserMessage(text.toString(), images));
        }

        private synchronized void clearStagedImages() {
            stagedImages.clear();
        }

        private record StagedImage(String attachmentId, String fileName, URI imageUri) {
        }

        private String systemPrompt() {
            return systemPrompt;
        }

        private synchronized List<ModelMessage> history() {
            return List.copyOf(history);
        }

        private synchronized void append(ModelMessage message) {
            history.add(message);
        }

        private synchronized void appendAll(List<ModelMessage> messages) {
            history.addAll(messages);
        }
    }
}
