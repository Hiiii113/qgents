package qg.qgent.memory.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import qg.qgent.common.web.ApiException;
import qg.qgent.harness.tool.ParallelTool;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.memory.service.ProjectMemoryService;

/** 让当前 AgentRun 通过触发者的项目权限读写项目 memory.md。 */
public class MemoryTools {

    private final ProjectMemoryService memories;
    private final Long actor;
    private final Long projectId;

    public MemoryTools(ProjectMemoryService memories, Long actor, Long projectId) {
        this.memories = memories;
        this.actor = actor;
        this.projectId = projectId;
    }

    /** 读取整篇项目记忆和版本号，写入时必须带上同一版本号。 */
    @ParallelTool
    @Tool(name = "read_project_memory", description = "读取项目长期记忆 memory.md 最新全文和版本号。"
            + "上下文包含的是启动时快照，准备修改或需要确认最新内容时调用。")
    public ToolResult readProjectMemory() {
        return invoke(() -> result(memories.get(actor, projectId)));
    }

    /** 整篇替换项目记忆；写入前必须先取得用户对具体修改的同意。 */
    @Tool(name = "write_project_memory", description = "整篇替换所有项目成员共享的 memory.md。"
            + "用户已明确要求具体修改时直接执行；否则先说明拟新增、修改或删除的内容并取得同意。"
            + "先调用 read_project_memory 获取最新正文和 version，保留无关内容。版本冲突时重新读取并合并；"
            + "若修改超出用户已同意的范围，先重新确认。只记录已确认、长期有用的项目约定、决策和偏好。")
    public ToolResult writeProjectMemory(
            @ToolParam(description = "memory.md 全文，整篇替换；保留未涉及的原内容") String content,
            @ToolParam(description = "read_project_memory 返回的版本号") String version) {
        if (content == null) {
            return ToolResult.error("INVALID_ARGUMENT", "content 不能为空");
        }
        return invoke(() -> result(memories.save(actor, projectId, content, version)));
    }

    private ToolResult result(ProjectMemoryResponse memory) {
        ToolResult result = ToolResult.ok();
        result.put("content", memory.getContent());
        result.put("version", memory.getVersion());
        result.put("updatedAt", memory.getUpdatedAt().toString());
        return result;
    }

    private ToolResult invoke(java.util.function.Supplier<ToolResult> action) {
        try {
            return action.get();
        } catch (ApiException e) {
            return ToolResult.error(e.code(), e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.error("MEMORY_UNAVAILABLE", "项目记忆操作失败: " + e.getClass().getSimpleName());
        }
    }
}
