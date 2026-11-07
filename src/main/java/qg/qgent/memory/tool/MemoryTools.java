package qg.qgent.memory.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import qg.qgent.common.web.ApiException;
import qg.qgent.harness.tool.ParallelTool;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.memory.service.ProjectMemoryService;

import java.util.LinkedHashMap;
import java.util.Map;

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
    @Tool(name = "read_project_memory", description = "读取项目长期记忆 memory.md 全文和版本号。"
            + "上下文已包含当前内容；只有准备修改或确认最新内容时才调用。")
    public Map<String, Object> readProjectMemory() {
        return invoke(() -> result(memories.get(actor, projectId)));
    }

    /** 整篇替换项目记忆；写入前必须先取得用户对具体修改的同意。 */
    @Tool(name = "write_project_memory", description = "整篇替换项目 memory.md 的正文。"
            + "调用前必须先用普通文字说明拟新增、修改或删除的内容并询问用户，收到针对该修改的明确同意后再写入；"
            + "未经同意不要写入，也不要反复询问。version 使用 read_project_memory 返回的版本号，"
            + "版本冲突时重新读取并说明冲突。只记录已确认、可长期复用的项目事实。")
    public Map<String, Object> writeProjectMemory(
            @ToolParam(description = "memory.md 全文，整篇替换；保留未涉及的原内容") String content,
            @ToolParam(description = "read_project_memory 返回的版本号") String version) {
        if (content == null) {
            return error("content 不能为空");
        }
        return invoke(() -> result(memories.save(actor, projectId, content, version)));
    }

    private Map<String, Object> result(ProjectMemoryResponse memory) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("content", memory.getContent());
        result.put("version", memory.getVersion());
        result.put("updatedAt", memory.getUpdatedAt().toString());
        return result;
    }

    private Map<String, Object> invoke(java.util.function.Supplier<Map<String, Object>> action) {
        try {
            return action.get();
        } catch (ApiException e) {
            return error(e.getMessage());
        } catch (RuntimeException e) {
            return error("项目记忆操作失败: " + e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("error", message);
        return result;
    }
}
