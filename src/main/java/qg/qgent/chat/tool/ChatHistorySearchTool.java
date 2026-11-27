package qg.qgent.chat.tool;

import lombok.extern.slf4j.Slf4j;
import qg.qgent.chat.dto.ContextMessage;
import qg.qgent.chat.service.ChatHistoryService;
import qg.qgent.harness.tool.ParallelTool;
import qg.qgent.harness.tool.Tool;
import qg.qgent.harness.tool.ToolParam;
import qg.qgent.harness.tool.ToolResult;

import java.util.List;

/**
 * 当前群聊的历史消息检索与全文读取。
 */
@Slf4j
public class ChatHistorySearchTool {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final ChatHistoryService history;
    private final Long actor;
    private final Long projectId;
    private final Long groupId;

    public ChatHistorySearchTool(
        ChatHistoryService history, Long actor, Long projectId, Long groupId) {
        this.history = history;
        this.actor = actor;
        this.projectId = projectId;
        this.groupId = groupId;
    }

    /**
     * 按关键词检索当前群聊的历史消息。
     */
    @ParallelTool
    @Tool(
        name = "search_chat_history",
        description =
            "仅在当前项目群聊的历史聊天记录中按关键字检索；" + "当最近消息窗口缺少所需讨论时调用。query 必填")
    public ToolResult searchChatHistory(
        @ToolParam(description = "从任务或讨论中提取的非空检索关键字") String query,
        @ToolParam(description = "请求查询的最大命中数，默认 10、最多 50", required = false) Integer limit) {
        if (query == null || query.isBlank()) {
            return ToolResult.error(
                "CHAT_HISTORY_QUERY_REQUIRED", "search_chat_history 需要非空 query，请使用具体关键字定位历史讨论");
        }
        if (groupId == null) {
            return ToolResult.error(
                "CHAT_HISTORY_GROUP_REQUIRED", "search_chat_history 当前运行未关联项目群聊，无法检索聊天记录");
        }
        int messageLimit = Math.max(1, Math.min(limit == null ? DEFAULT_LIMIT : limit, MAX_LIMIT));
        final List<ContextMessage> messages;
        try {
            messages = history.searchChatHistory(actor, projectId, groupId, query, messageLimit);
        } catch (RuntimeException exception) {
            log.info(
                "chat history search failed projectId={} groupId={} category={}",
                projectId,
                groupId,
                exception.getClass().getSimpleName());
            return ToolResult.error(
                "CHAT_HISTORY_UNAVAILABLE",
                "search_chat_history 检索失败: " + safeMessage(exception));
        }
        ToolResult result = ToolResult.ok();
        result.put("messages", ChatToolOutput.digest(messages));
        log.info("chat history searched projectId={} groupId={}", projectId, groupId);
        return result;
    }

    @ParallelTool
    @Tool(
        name = "read_chat_message",
        description = "按消息 ID 读取当前群消息全文及 replyToId；用于展开搜索命中或读取回复的原消息，不需要沙箱")
    public ContextMessage readChatMessage(
        @ToolParam(description = "消息 ID，来自上下文、搜索结果或 replyToId") String messageId) {
        return history.readChatMessage(actor, projectId, groupId, Long.valueOf(messageId));
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null
            || throwable.getMessage() == null
            || throwable.getMessage().isBlank()) {
            return "chat history unavailable";
        }
        String firstLine =
            throwable
                .getMessage()
                .strip()
                .lines()
                .findFirst()
                .orElse("chat history unavailable");
        return firstLine.length() <= 200 ? firstLine : firstLine.substring(0, 200);
    }
}
