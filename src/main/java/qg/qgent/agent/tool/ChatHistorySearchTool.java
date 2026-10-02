package qg.qgent.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import qg.qgent.chat.dto.ContextMessage;
import qg.qgent.chat.service.ContextService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 当前项目群聊的历史聊天检索工具。
 * <p>
 * 本工具不接触 Skill 或 Memory；每个实例仅服务一条 AgentRun，调用预算不跨运行继承。
 */
@Slf4j
public class ChatHistorySearchTool {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;
    private static final int MAX_RETURNED_MESSAGES = 10;
    private static final int MAX_MESSAGE_CHARS = 200;

    private final ContextService contextService;
    private final UUID actor;
    private final UUID projectId;
    private final UUID groupId;
    private final int maxSearches;
    private final AtomicInteger searchesUsed = new AtomicInteger();

    public ChatHistorySearchTool(ContextService contextService, UUID actor, UUID projectId, UUID groupId,
                                 int maxSearches) {
        this.contextService = contextService;
        this.actor = actor;
        this.projectId = projectId;
        this.groupId = groupId;
        this.maxSearches = Math.max(0, maxSearches);
    }

    /** 按关键词检索当前群聊的历史消息。 */
    @ParallelTool
    @Tool(name = "search_chat_history", description = "仅在当前项目群聊的历史聊天记录中按关键字检索；"
            + "当最近消息窗口缺少所需讨论时调用。query 必填，每个 AgentRun 的调用次数有限")
    public Map<String, Object> searchChatHistory(
            @ToolParam(description = "从任务或讨论中提取的非空检索关键字") String query,
            @ToolParam(description = "请求查询的最大命中数，默认 10、最多 50", required = false) Integer limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return error(result, "search_chat_history 需要非空 query，请使用具体关键字定位历史讨论");
        }
        if (groupId == null) {
            return error(result, "search_chat_history 当前运行未关联项目群聊，无法检索聊天记录");
        }
        if (searchesUsed.getAndUpdate(used -> Math.min(used + 1, maxSearches)) >= maxSearches) {
            return error(result, "search_chat_history 检索预算已用尽（已调用 " + searchesUsed.get() + "/" + maxSearches
                    + " 次），请基于已有上下文继续");
        }
        int messageLimit = Math.max(1, Math.min(limit == null ? DEFAULT_LIMIT : limit, MAX_LIMIT));
        final List<ContextMessage> messages;
        try {
            messages = contextService.searchChatHistory(actor, projectId, groupId, query, messageLimit);
        } catch (RuntimeException exception) {
            log.info("chat history search failed projectId={} groupId={} category={}", projectId, groupId,
                    exception.getClass().getSimpleName());
            return error(result, "search_chat_history 检索失败: " + safeMessage(exception));
        }
        result.put("ok", true);
        result.put("budget", "used " + searchesUsed.get() + "/" + maxSearches);
        result.put("messages", formatMessages(messages));
        log.info("chat history searched projectId={} groupId={} returned={} used={}", projectId, groupId,
                Math.min(messages.size(), MAX_RETURNED_MESSAGES), searchesUsed.get());
        return result;
    }

    private List<Map<String, Object>> formatMessages(List<ContextMessage> messages) {
        List<Map<String, Object>> formatted = new ArrayList<>();
        for (ContextMessage message : messages) {
            if (formatted.size() >= MAX_RETURNED_MESSAGES) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", message.getId());
            item.put("sequence", message.getSequence());
            item.put("senderType", message.getSenderType());
            item.put("text", excerpt(message.getText()));
            formatted.add(item);
        }
        return formatted;
    }

    @ParallelTool
    @Tool(name = "read_chat_message", description = "按消息 ID 读取当前群消息全文及 replyToId；用于展开搜索命中或读取回复的原消息，不需要沙箱")
    public ContextMessage readChatMessage(@ToolParam(description = "消息 UUID，来自上下文、搜索结果或 replyToId") String messageId) {
        return contextService.readChatMessage(actor, projectId, groupId, UUID.fromString(messageId));
    }

    private String excerpt(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String singleLine = text.replace('\n', ' ').replace('\r', ' ').strip();
        return singleLine.length() <= MAX_MESSAGE_CHARS ? singleLine
                : singleLine.substring(0, MAX_MESSAGE_CHARS) + "...";
    }

    private Map<String, Object> error(Map<String, Object> result, String message) {
        result.put("ok", false);
        result.put("error", message);
        return result;
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "chat history unavailable";
        }
        String firstLine = throwable.getMessage().strip().lines().findFirst().orElse("chat history unavailable");
        return firstLine.length() <= 200 ? firstLine : firstLine.substring(0, 200);
    }
}
