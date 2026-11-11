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
 * 用户、项目与群来自本次运行，模型只能传正文和版本；管理员身份、默认群与版本由服务端核验。
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

    /** 项目管理员在默认群明确确认后整篇写入；内容与当前一致时按幂等成功返回。 */
    @Tool(
            name = "write_project_memory",
            description =
                    "项目管理员在项目默认群明确同意后写入项目长期记忆，整篇替换 memory.md。"
                            + "先调用 read_project_memory 取最新正文，保留未被要求修改的原内容，合并后带上它的 version 调用；"
                            + "服务端核验真实发送者的项目管理员身份、当前群是否为项目默认群和版本，失败时如实说明记忆未更新。")
    public ToolResult writeProjectMemory(
            @ToolParam(description = "整篇 memory.md 新正文") String content,
            @ToolParam(description = "read_project_memory 返回的版本号") String baseVersion) {
        if (content == null || content.isBlank()) {
            return ToolResult.error("INVALID_ARGUMENT", "content 不能为空");
        }
        if (baseVersion == null || baseVersion.isBlank()) {
            return ToolResult.error("INVALID_ARGUMENT", "baseVersion 不能为空");
        }
        return ToolErrors.call(
                "MEMORY_UNAVAILABLE",
                "项目记忆写入失败",
                () -> {
                    ProjectMemoryResponse saved =
                            memories.update(actor, projectId, groupId, content, baseVersion);
                    ToolResult result = ToolResult.ok();
                    result.put("version", saved.getVersion());
                    result.put("updatedAt", saved.getUpdatedAt().toString());
                    return result;
                });
    }
}
