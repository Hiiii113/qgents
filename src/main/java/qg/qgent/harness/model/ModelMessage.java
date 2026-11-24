package qg.qgent.harness.model;

import java.net.URI;
import java.util.List;

public sealed interface ModelMessage {
    record SystemMessage(String text) implements ModelMessage {}

    record UserMessage(String text, List<URI> images) implements ModelMessage {
        public UserMessage {
            images = List.copyOf(images);
        }

        public UserMessage(String text) {
            this(text, List.of());
        }
    }

    record AssistantMessage(String text, List<ToolCall> toolCalls, String reasoningContent)
        implements ModelMessage {
        public AssistantMessage {
            toolCalls = List.copyOf(toolCalls);
        }
    }

    record ToolResponseMessage(List<ToolResponse> responses) implements ModelMessage {
        public ToolResponseMessage {
            responses = List.copyOf(responses);
        }
    }

    record ToolCall(String id, String name, String arguments) {}

    record ToolResponse(String id, String name, String responseData) {}
}
