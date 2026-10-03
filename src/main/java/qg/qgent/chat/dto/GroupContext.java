package qg.qgent.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 使用的群聊上下文。
 * <p>
 * 将项目群聊的历史消息、群描述、已发布 Skill 目录与已批准 Memory 组装为 Agent 输入上下文，
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

    @Schema(description = "群聊描述")
    private String description;
    @Schema(description = "近期群聊消息")
    private List<ContextMessage> conversation;

    @Schema(description = "项目已发布 Skill 目录，仅包含 ID 与名称")
    private List<ContextSkill> skills;

    @Schema(description = "项目已批准的 Memory")
    private List<ContextMemory> memories;

}
