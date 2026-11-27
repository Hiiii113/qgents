package qg.qgent.chat.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import qg.qgent.chat.dto.ContextMessage;
import qg.qgent.chat.service.ChatHistoryService;
import qg.qgent.harness.tool.ToolRegistry;
import qg.qgent.support.TestIds;

class ChatHistorySearchToolTest {

    private final ChatHistoryService contextService = mock(ChatHistoryService.class);
    private final Long actor = TestIds.next();
    private final Long projectId = TestIds.next();
    private final Long groupId = TestIds.next();

    @Test
    void onlyQueriesCurrentGroupAndReturnsAtMostTenTruncatedMessages() {
        List<ContextMessage> messages = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            messages.add(new ContextMessage((long) index, "TEXT", "USER", "u", "x".repeat(240)));
        }
        when(contextService.searchChatHistory(actor, projectId, groupId, "登录", 50))
                .thenReturn(messages);
        ChatHistorySearchTool tool =
                new ChatHistorySearchTool(contextService, actor, projectId, groupId);

        Map<String, Object> result = tool.searchChatHistory("登录", 50);

        ToolRegistry registry = new ToolRegistry(new ObjectMapper());
        registry.register(tool);
        assertThat(registry.callbacks())
                .extracting(callback -> callback.name())
                .contains("search_chat_history", "read_chat_message");
        assertThat(result).containsEntry("ok", true);
        assertThat((List<?>) result.get("messages")).hasSize(10);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) result.get("messages")).get(0);
        assertThat((String) first.get("text")).hasSize(203).endsWith("...");
        verify(contextService).searchChatHistory(actor, projectId, groupId, "登录", 50);
    }

    @Test
    void blankQueryDoesNotQueryService() {
        ChatHistorySearchTool tool =
                new ChatHistorySearchTool(contextService, actor, projectId, groupId);

        assertThat(tool.searchChatHistory(" ", null))
                .containsEntry("ok", false)
                .containsEntry("errorCode", "CHAT_HISTORY_QUERY_REQUIRED");

        verifyNoInteractions(contextService);
    }

    @Test
    void everySearchInTheSameRunQueriesTheService() {
        ChatHistorySearchTool tool =
                new ChatHistorySearchTool(contextService, actor, projectId, groupId);
        when(contextService.searchChatHistory(actor, projectId, groupId, "key", 10))
                .thenReturn(List.of());

        assertThat(tool.searchChatHistory("key", null)).containsEntry("ok", true);
        assertThat(tool.searchChatHistory("key", null)).containsEntry("ok", true);

        verify(contextService, times(2)).searchChatHistory(actor, projectId, groupId, "key", 10);
    }
}
