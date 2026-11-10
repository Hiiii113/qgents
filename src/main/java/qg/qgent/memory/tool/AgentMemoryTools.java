package qg.qgent.memory.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import qg.qgent.harness.tool.ParallelTool;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.memory.dto.AgentMemoryView;
import qg.qgent.memory.service.AgentMemoryService;

/**
 * 让当前 AgentRun 维护它自己的项目内私有记忆。
 * <p>
 * 项目、Agent 与群都由运行绑定，工具参数无法指定其他 Agent、项目或群。
 */
public class AgentMemoryTools {

    private final AgentMemoryService memories;
    private final Long projectId;
    private final Long agentId;
    private final Long groupId;

    public AgentMemoryTools(AgentMemoryService memories, Long projectId, Long agentId, Long groupId) {
        this.memories = memories;
        this.projectId = projectId;
        this.agentId = agentId;
        this.groupId = groupId;
    }

    /** 读取自己的私有记忆：当前群可改写的部分与来自其他群的只读部分分开返回。 */
    @ParallelTool
    @Tool(name = "read_agent_memory", description = "读取你自己的私有记忆。content 是当前群可改写的部分；"
            + "inherited 是来源群成员覆盖当前群、只能读取的部分。写回 content 前先读取，保留未涉及的原内容。")
    public ToolResult readAgentMemory() {
        AgentMemoryView view = memories.view(projectId, agentId, groupId);
        ToolResult result = ToolResult.ok();
        result.put("content", view.content());
        result.put("inherited", view.inherited());
        return result;
    }

    /** 覆盖自己的私有记忆；只记录分工和用户对该 Agent 的长期要求。 */
    @Tool(name = "write_agent_memory", description = "覆盖你自己私有记忆中当前群可改写的部分。"
            + "只记录用户对你的长期要求、分工和偏好；不要记录某个群内的具体内容、他人隐私或临时任务进展。"
            + "用户已明确要求时直接执行，否则先说明拟增删改的内容并取得同意；写入成功后再说已更新。")
    public ToolResult writeAgentMemory(
            @ToolParam(description = "当前群可改写的私有记忆全文；保留未涉及的原内容") String content) {
        if (content == null) {
            return ToolResult.error("INVALID_ARGUMENT", "content 不能为空");
        }
        memories.save(projectId, agentId, groupId, content);
        ToolResult result = ToolResult.ok();
        result.put("content", content);
        return result;
    }
}
