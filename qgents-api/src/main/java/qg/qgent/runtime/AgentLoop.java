package qg.qgent.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 所有 Agent 共用的循环：工具结果回给模型，普通文本结束本次运行。 */
@Component
public class AgentLoop {
    private final ChatModel model;
    private final ObjectMapper json;
    private final int maxRounds;

    public AgentLoop(ChatModel model, ObjectMapper json,
                     @Value("${app.agent.max-rounds:30}") int maxRounds) {
        this.model = model;
        this.json = json;
        if (maxRounds < 1) throw new IllegalArgumentException("max-rounds 必须大于 0");
        this.maxRounds = maxRounds;
    }

    /** 工具列表按轮读取，使模型创建工作区后即可在下一轮使用文件工具。 */
    public Result run(String systemPrompt, List<Message> initialHistory,
                      Supplier<List<ToolCallback>> availableTools, BooleanSupplier cancelled) {
        List<Message> history = new ArrayList<>(initialHistory);
        for (int round = 1; round <= maxRounds; round++) {
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
                return new Result(false, "Agent 运行已取消", round - 1);
            }
            List<ToolCallback> tools = availableTools.get();
            Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
            for (ToolCallback tool : tools) {
                String name = tool.getToolDefinition().name();
                if (callbacks.putIfAbsent(name, tool) != null) {
                    throw new IllegalArgumentException("重复工具名：" + name);
                }
            }
            if (!(model.getOptions() instanceof OpenAiChatOptions defaults)) {
                throw new IllegalStateException("当前模型需要 OpenAiChatOptions");
            }
            OpenAiChatOptions options = (OpenAiChatOptions) defaults.mutate()
                    .toolCallbacks(tools).toolChoice(tools.isEmpty() ? "none" : "auto")
                    .maxRetries(0).build();
            List<Message> request = new ArrayList<>();
            request.add(new SystemMessage(systemPrompt));
            request.addAll(history);
            ChatResponse response = model.call(new Prompt(request, options));
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
                return new Result(false, "Agent 运行已取消", round);
            }
            if (response == null || response.getResult() == null) {
                return new Result(false, "模型没有返回有效回复", round);
            }
            if (response.getResult().getMetadata() != null
                    && "length".equalsIgnoreCase(response.getResult().getMetadata().getFinishReason())) {
                return new Result(false, "模型回复被截断，请缩小问题范围后重试", round);
            }
            AssistantMessage assistant = response.getResult().getOutput();
            if (assistant == null) return new Result(false, "模型没有返回有效回复", round);
            if (!assistant.hasToolCalls()) {
                String text = assistant.getText();
                return text == null || text.isBlank()
                        ? new Result(false, "模型返回了空回复", round)
                        : new Result(true, text, round);
            }
            // 保留原始消息及供应商元数据，下一轮完整回传 tool_calls/reasoning_content。
            history.add(assistant);
            List<ToolResponseMessage.ToolResponse> results = new ArrayList<>();
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
                    return new Result(false, "Agent 运行已取消", round);
                }
                String result;
                ToolCallback callback = callbacks.get(call.name());
                if (callback == null) {
                    result = error("TOOL_NOT_FOUND", "本轮没有工具：" + call.name());
                } else {
                    try {
                        result = callback.call(call.arguments());
                    } catch (RuntimeException exception) {
                        result = error("TOOL_FAILED", exception.getMessage() == null
                                ? "工具执行失败" : exception.getMessage());
                    }
                }
                results.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
            }
            history.add(ToolResponseMessage.builder().responses(results).build());
        }
        return new Result(false, "Agent 已达到最大调用轮次（" + maxRounds + "），请缩小问题范围后重试", maxRounds);
    }

    private String error(String code, String message) {
        try {
            return json.writeValueAsString(Map.of("ok", false, "errorCode", code, "error", message));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法编码工具结果", exception);
        }
    }

    public record Result(boolean success, String text, int rounds) { }
}
