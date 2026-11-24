package qg.qgent.harness.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import qg.qgent.harness.model.ModelClient;
import qg.qgent.harness.model.ModelMessage;
import qg.qgent.harness.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 所有 Agent 共用的循环：工具结果回给模型，普通文本结束本次运行。
 */
@Component
public class AgentLoop {
    private static final Duration CANCELLATION_CHECK_INTERVAL = Duration.ofMillis(100);
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
        try {
            for (int round = 1; round <= maxRounds; round++) {
                if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
                    return new Result(false, "Agent 运行已取消", round - 1);
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
                try {
                    reply = chat(request(tools, messages), sink, cancelled);
                } catch (ModelCallTimeoutException exception) {
                    return new Result(false, "模型调用超时，请稍后重试", round);
                }
                if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
                    return new Result(false, "Agent 运行已取消", round);
                }
                if (reply == null || reply.message() == null) {
                    return new Result(false, "模型没有返回有效回复", round);
                }
                if ("length".equalsIgnoreCase(reply.finishReason())) {
                    return new Result(false, "模型回复被截断，请缩小问题范围后重试", round);
                }
                ModelMessage.AssistantMessage assistant = reply.message();
                if (assistant.toolCalls().isEmpty()) {
                    String text = assistant.text();
                    if (text == null || text.isBlank()) return new Result(false, "模型返回了空回复", round);
                    conversation.append(assistant);
                    List<ModelMessage> arrivedBeforeWait = updates.drain();
                    conversation.appendAll(arrivedBeforeWait);
                    List<ModelMessage> completedWork = updates.awaitNext();
                    if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
                        return new Result(false, "Agent 运行已取消", round);
                    }
                    if (!arrivedBeforeWait.isEmpty() || !completedWork.isEmpty()) {
                        conversation.appendAll(completedWork);
                        continue;
                    }
                    return new Result(true, text, round);
                }
                conversation.append(assistant);
                ToolCallExecutor.Result results =
                    toolCalls.execute(
                        runId, assistant.toolCalls(), callbacks, cancelled, sink);
                if (results.cancelled()) return new Result(false, "Agent 运行已取消", round);
                conversation.append(new ModelMessage.ToolResponseMessage(results.responses()));
                // 图片必须在完整工具结果之后追加，且只在确实还有下一轮模型调用时发送。
                if (round < maxRounds) conversation.appendAll(conversation.takeStagedImages());
            }
            return new Result(false, "Agent 已达到最大调用轮次（" + maxRounds + "），请缩小问题范围后重试", maxRounds);
        } finally {
            conversation.clearStagedImages();
        }
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
     * 消费模型流：正文增量交给实时层，聚合后的助手消息与结束原因由模型客户端给出。
     */
    private ModelClient.Reply chat(
        ModelClient.Request request, RunStreamSink sink, BooleanSupplier cancelled) {
        Mono<Long> cancellation = Flux.interval(CANCELLATION_CHECK_INTERVAL)
            .filter(tick -> cancelled.getAsBoolean())
            .next();
        return model.stream(
                request,
                text -> {
                    // 思考内容不进正文流；取消后立即停止推送。
                    if (!cancelled.getAsBoolean()) sink.delta(text);
                })
            // 约束的是整轮调用，不会被新分片重置。
            .timeout(modelCallTimeout, Mono.error(ModelCallTimeoutException::new))
            .takeUntilOther(cancellation)
            .block();
    }

    private static final class ModelCallTimeoutException extends RuntimeException {}

    public record Result(boolean success, String text, int rounds) {
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
