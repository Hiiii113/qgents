package qg.qgent.memory.tool;

import qg.qgent.harness.tool.ParallelTool;
import qg.qgent.harness.tool.Tool;
import qg.qgent.harness.tool.ToolErrors;
import qg.qgent.harness.tool.ToolParam;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.memory.service.GroupMemoryService;
import qg.qgent.memory.service.GroupMemorySnapshot;

/**
 * 让当前 AgentRun 在群内直接读取和修正群记忆；群成员身份由服务端核验。
 * <p>
 * 后台提炼可能同时进行，服务端用群级锁和 revision 保证 Agent 的修改不会被旧提炼结果覆盖。
 */
public class GroupMemoryTools {

    private final GroupMemoryService memories;
    private final Long actor;
    private final Long projectId;
    private final Long groupId;

    public GroupMemoryTools(
        GroupMemoryService memories, Long actor, Long projectId, Long groupId) {
        this.memories = memories;
        this.actor = actor;
        this.projectId = projectId;
        this.groupId = groupId;
    }

    /**
     * 读取整篇群记忆；修改前据此拿到最新正文和 revision。
     */
    @ParallelTool
    @Tool(
        name = "read_group_memory",
        description =
            "读取当前群记忆 memory.md 的最新全文、revision 和最后修改时间。"
                + "上下文里的是启动时快照，准备修改或需要确认最新内容时先调用。")
    public ToolResult readGroupMemory() {
        return ToolErrors.call(
            "GROUP_MEMORY_UNAVAILABLE",
            "群记忆读取失败",
            () -> {
                memories.requireMember(actor, projectId, groupId);
                GroupMemorySnapshot memory = memories.readWithRevision(projectId, groupId);
                ToolResult result = ToolResult.ok();
                result.put("content", memory.content());
                result.put("revision", memory.revision());
                result.put(
                    "updatedAt",
                    memory.updatedAt() == null ? null : memory.updatedAt().toString());
                return result;
            });
    }

    /**
     * 按片段增删改群记忆；原文必须唯一出现，否则不写入。
     */
    @Tool(
        name = "edit_group_memory",
        description =
            "局部增删改当前群记忆：传入要替换的原文片段和替换后的内容，服务端按唯一匹配替换后整篇写回。"
                + "原文必须与 read_group_memory 的结果完全一致且只出现一次，找不到或不唯一时不写入；删除时把替换内容传空字符串。"
                + "整篇重写或首次创建才用 write_group_memory。"
                + "只记录该群的决策与规范、成员要求与当前进行中的事项，过时或被推翻的内容要一起改掉，不要只增不减。"
                + "服务端核验真实发送者是否属于本群，失败时如实说明群记忆未更新。")
    public ToolResult editGroupMemory(
        @ToolParam(description = "群记忆里要被替换的原文，必须与当前正文完全一致且只出现一次") String anchor,
        @ToolParam(description = "替换后的内容；删除时传空字符串") String replacement) {
        if (anchor == null || anchor.isBlank()) {
            return ToolResult.error("INVALID_ARGUMENT", "anchor 不能为空");
        }
        if (replacement == null) {
            return ToolResult.error("INVALID_ARGUMENT", "replacement 不能为 null，删除时传空字符串");
        }
        return ToolErrors.call(
            "GROUP_MEMORY_UNAVAILABLE",
            "群记忆写入失败",
            () -> {
                memories.requireMember(actor, projectId, groupId);
                ToolResult result = ToolResult.ok();
                result.put(
                    "revision", memories.edit(projectId, groupId, anchor, replacement));
                return result;
            });
    }

    /**
     * 整篇重写群记忆；用户明确要求按新结构整理时使用。
     */
    @Tool(
        name = "write_group_memory",
        description =
            "整篇重写当前群记忆 memory.md；只用于首次创建或全量整理，局部修改请用 edit_group_memory。"
                + "正文固定使用三个二级分区：## 群内决策与规范（每条带 [YYYY-MM-DD]，新决策直接覆盖旧决策）、"
                + "## 成员要求与偏好（每条带 [YYYY-MM-DD]，标明是谁的要求或偏好）、"
                + "## 当前进行中（每条带 [YYYY-MM-DD]，已完成或过期的事项必须删除）。"
                + "每次整理都是压缩：过时、被推翻或已完成的内容要删除，不得保留流水账。"
                + "服务端核验真实发送者是否属于本群，失败时如实说明群记忆未更新。")
    public ToolResult writeGroupMemory(
        @ToolParam(description = "整篇群记忆新正文") String content) {
        if (content == null || content.isBlank()) {
            return ToolResult.error("INVALID_ARGUMENT", "content 不能为空");
        }
        return ToolErrors.call(
            "GROUP_MEMORY_UNAVAILABLE",
            "群记忆写入失败",
            () -> {
                memories.requireMember(actor, projectId, groupId);
                ToolResult result = ToolResult.ok();
                result.put("revision", memories.writeByAgent(projectId, groupId, content));
                return result;
            });
    }
}
