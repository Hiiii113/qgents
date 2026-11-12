package qg.qgent.memory.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import qg.qgent.harness.tool.ToolErrors;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.memory.service.ProjectMemoryService;

/**
 * 项目记忆写入工具，只装配给主 Agent。
 * <p>
 * 用户、项目与群来自本次运行，模型只能传正文；管理员身份与默认群由服务端核验。
 */
public class MemoryWriteTools {

    private final ProjectMemoryService memories;
    private final Long actor;
    private final Long projectId;
    private final Long groupId;

    public MemoryWriteTools(
            ProjectMemoryService memories, Long actor, Long projectId, Long groupId) {
        this.memories = memories;
        this.actor = actor;
        this.projectId = projectId;
        this.groupId = groupId;
    }

    /** 项目管理员在默认群明确确认后按片段替换；原文必须唯一出现，否则不写入。 */
    @Tool(
            name = "edit_project_memory",
            description =
                    "局部增删改项目长期记忆：传入要替换的原文片段和替换后的内容，服务端按唯一匹配替换后整篇写回。"
                            + "原文必须与 read_project_memory 的结果完全一致且只出现一次，找不到或不唯一时不写入；删除时把替换内容传空字符串。"
                            + "全篇重写或首次创建才用 write_project_memory。"
                            + "服务端核验真实发送者的项目管理员身份和当前群是否为项目默认群，失败时如实说明记忆未更新。")
    public ToolResult editProjectMemory(
            @ToolParam(description = "记忆里要被替换的原文，必须与当前正文完全一致且只出现一次") String anchor,
            @ToolParam(description = "替换后的内容；删除时传空字符串") String replacement) {
        if (anchor == null || anchor.isBlank()) {
            return ToolResult.error("INVALID_ARGUMENT", "anchor 不能为空");
        }
        if (replacement == null) {
            return ToolResult.error("INVALID_ARGUMENT", "replacement 不能为 null，删除时传空字符串");
        }
        return ToolErrors.call(
                "MEMORY_UNAVAILABLE",
                "项目记忆写入失败",
                () -> {
                    ProjectMemoryResponse saved =
                            memories.edit(actor, projectId, groupId, anchor, replacement);
                    ToolResult result = ToolResult.ok();
                    result.put("updatedAt", saved.getUpdatedAt().toString());
                    return result;
                });
    }

    /** 项目管理员在默认群明确确认后整篇写入；内容与当前一致时按幂等成功返回。 */
    @Tool(
            name = "write_project_memory",
            description =
                    "项目管理员在项目默认群明确同意后写入项目长期记忆，整篇替换 memory.md。"
                            + "先调用 read_project_memory 取最新正文，保留未被要求修改的原内容，合并后调用；"
                            + "服务端核验真实发送者的项目管理员身份和当前群是否为项目默认群，失败时如实说明记忆未更新。")
    public ToolResult writeProjectMemory(
            @ToolParam(description = "整篇 memory.md 新正文") String content) {
        if (content == null || content.isBlank()) {
            return ToolResult.error("INVALID_ARGUMENT", "content 不能为空");
        }
        return ToolErrors.call(
                "MEMORY_UNAVAILABLE",
                "项目记忆写入失败",
                () -> {
                    ProjectMemoryResponse saved =
                            memories.update(actor, projectId, groupId, content);
                    ToolResult result = ToolResult.ok();
                    result.put("updatedAt", saved.getUpdatedAt().toString());
                    return result;
                });
    }
}
