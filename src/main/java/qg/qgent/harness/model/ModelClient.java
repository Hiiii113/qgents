package qg.qgent.harness.model;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Consumer;

public interface ModelClient {
    String complete(String systemPrompt, String userPrompt);

    /**
     * 发出正文增量，流完整结束后返回助手消息。取消订阅时关闭本轮请求。
     */
    Mono<Reply> stream(Request request, Consumer<String> textDelta);

    default String modelName() {
        return null;
    }

    default String baseUrl() {
        return null;
    }

    record Request(List<ModelMessage> messages, List<ToolDefinition> tools) {
        public Request {
            messages = List.copyOf(messages);
            tools = List.copyOf(tools);
        }
    }

    record ToolDefinition(String name, String description, String inputSchema) {}

    record Usage(
        long promptTokens, long completionTokens, long cachedTokens, long reasoningTokens) {
        public static final Usage ZERO = new Usage(0, 0, 0, 0);

        public Usage plus(Usage other) {
            if (other == null) return this;
            return new Usage(
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                cachedTokens + other.cachedTokens,
                reasoningTokens + other.reasoningTokens);
        }
    }

    record Reply(ModelMessage.AssistantMessage message, String finishReason, Usage usage) {
        public Reply {
            usage = usage == null ? Usage.ZERO : usage;
        }

        public Reply(ModelMessage.AssistantMessage message, String finishReason) {
            this(message, finishReason, Usage.ZERO);
        }
    }
}
