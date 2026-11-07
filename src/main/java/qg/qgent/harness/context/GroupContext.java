package qg.qgent.harness.context;

import qg.qgent.chat.dto.ContextMessage;
import qg.qgent.skill.dto.ContextSkill;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 使用的群聊上下文。
 * <p>
 * 将项目群聊的历史消息、已发布 Skill 目录与项目 memory.md 组装为 Agent 输入上下文，
 * 供 Agent loop 作为 prompt 输入。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupContext {

    @Schema(description = "项目群聊 ID")
    private String groupId;

    @Schema(description = "所属项目 ID")
    private String projectId;

    @Schema(description = "项目群聊标题")
    private String title;

    @Schema(description = "近期群聊消息")
    private List<ContextMessage> conversation;

    @Schema(description = "项目已发布 Skill 目录，包含 ID、名称和用途描述")
    private List<ContextSkill> skills;

    @Schema(description = "项目长期记忆 memory.md 全文")
    private String projectMemory;

}
