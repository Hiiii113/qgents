package qg.qgent.chat.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpStatus;

import qg.qgent.chat.dto.ContextMessage;
import qg.qgent.chat.dto.GroupResponse;
import qg.qgent.chat.service.ChatHistoryService;
import qg.qgent.chat.service.CrossGroupAccessService;
import qg.qgent.common.web.ApiException;
import qg.qgent.harness.tool.ParallelTool;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.memory.service.GroupMemoryService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按跨群权限读取其他群的记忆和聊天记录。
 * <p>
 * 每条读取都由 {@link CrossGroupAccessService} 判定；不满足时只抛不可访问错误，不返回任何来源内容。
 * 实际读到内容的来源群记入 {@code disclosedSources}，供回答投递前复核目的群受众。
 */
@Slf4j
public class CrossGroupTools {

    private final CrossGroupAccessService access;
    private final GroupMemoryService memories;
    private final ChatHistoryService history;
    private final Long actor;
    private final Long projectId;
    private final Long destinationGroupId;
    private final Long agentId;
    private final Set<Long> disclosedSources;

    public CrossGroupTools(CrossGroupAccessService access, GroupMemoryService memories, ChatHistoryService history,
                           Long actor, Long projectId, Long destinationGroupId, Long agentId,
                           Set<Long> disclosedSources) {
        this.access = access;
        this.memories = memories;
        this.history = history;
        this.actor = actor;
        this.projectId = projectId;
        this.destinationGroupId = destinationGroupId;
        this.agentId = agentId;
        this.disclosedSources = disclosedSources;
    }

    /** 先列出可以跨群读取的群，模型据此把用户说的群名换成群 ID。 */
    @ParallelTool
    @Tool(name = "list_readable_groups", description = "列出你可以跨群读取的其他群（ID 与名称）。"
            + "用户询问其他群的进展时先调用，再按返回的群 ID 读取该群记忆或检索聊天记录。")
    public ToolResult listReadableGroups() {
        List<Map<String, Object>> groups = new ArrayList<>();
        for (GroupResponse group : access.readableGroups(projectId, destinationGroupId, actor, agentId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", group.getId());
            item.put("title", group.getTitle());
            groups.add(item);
        }
        ToolResult result = ToolResult.ok();
        result.put("groups", groups);
        return result;
    }

    /** 读取其他群的群记忆；不在可读取范围内时只返回不可访问。 */
    @ParallelTool
    @Tool(name = "read_other_group_memory", description = "读取其他群的群聊记忆。"
            + "只有你和当前 Agent 都在该群、且该群成员都有权看到本群回答时才返回；"
            + "不可访问时如实说明无法读取，不要猜测或复述该群内容。")
    public ToolResult readOtherGroupMemory(@ToolParam(description = "目标群 ID，来自 list_readable_groups") String groupId) {
        Long source = requireReadable(groupId);
        ToolResult result = ToolResult.ok();
        result.put("group", source.toString());
        result.put("memory", memories.read(projectId, source).getContent());
        disclosedSources.add(source);
        return result;
    }

    /** 检索其他群的聊天记录；沿用当前群检索服务的查询能力。 */
    @ParallelTool
    @Tool(name = "search_other_group_chat_history", description = "在指定群的历史聊天记录中按关键字检索；"
            + "只在该群可跨群读取时返回命中摘要，全文用 read_other_group_chat_message 展开。")
    public ToolResult searchOtherGroupChatHistory(
            @ToolParam(description = "目标群 ID，来自 list_readable_groups") String groupId,
            @ToolParam(description = "从问题中提取的非空检索关键字") String query,
            @ToolParam(description = "请求查询的最大命中数，默认 10、最多 50", required = false) Integer limit) {
        Long source = requireReadable(groupId);
        List<ContextMessage> messages = history.searchChatHistory(actor, projectId, source, query, limit);
        ToolResult result = ToolResult.ok();
        result.put("group", source.toString());
        result.put("messages", ChatToolOutput.digest(messages));
        disclosedSources.add(source);
        return result;
    }

    /** 读取其他群某条消息的全文；消息必须属于该群。 */
    @ParallelTool
    @Tool(name = "read_other_group_chat_message", description = "按消息 ID 读取指定群的消息全文及 replyToId；"
            + "只在该群可跨群读取时返回，用于展开其他群的检索命中。")
    public ContextMessage readOtherGroupChatMessage(
            @ToolParam(description = "目标群 ID，来自 list_readable_groups") String groupId,
            @ToolParam(description = "消息 ID，来自该群的检索结果或 replyToId") String messageId) {
        Long source = requireReadable(groupId);
        ContextMessage message = history.readChatMessage(actor, projectId, source,
                parseId(messageId, "MESSAGE_ID_INVALID", "messageId 必须是检索结果或 replyToId 中的消息 ID"));
        disclosedSources.add(source);
        return message;
    }

    private Long requireReadable(String groupId) {
        Long source = parseId(groupId, "GROUP_ID_INVALID", "groupId 必须是 list_readable_groups 返回的群 ID");
        if (!access.readable(projectId, source, destinationGroupId, actor, agentId)) {
            log.info("cross group read denied projectId={} sourceGroupId={} destinationGroupId={}", projectId, source,
                    destinationGroupId);
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_ACCESSIBLE",
                    "目标群不可访问：需要你与此 Agent 都在该群，且该群成员有权看到本群回答");
        }
        return source;
    }

    private Long parseId(String value, String code, String message) {
        try {
            return Long.valueOf(value == null ? "" : value.trim());
        } catch (NumberFormatException invalid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, code, message);
        }
    }
}
