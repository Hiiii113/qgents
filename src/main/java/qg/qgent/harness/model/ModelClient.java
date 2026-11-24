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

    record Request(List<ModelMessage> messages, List<ToolDefinition> tools) {
        public Request {
            messages = List.copyOf(messages);
            tools = List.copyOf(tools);
        }
    }

    record ToolDefinition(String name, String description, String inputSchema) {}

    record Reply(ModelMessage.AssistantMessage message, String finishReason) {}
}
