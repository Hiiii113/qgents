package qg.qgent.memory.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import qg.qgent.common.web.ApiException;
import qg.qgent.harness.tool.ToolResult;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.memory.entity.ProjectMemoryProposalEntity;
import qg.qgent.memory.service.ProjectMemoryProposalService;

/**
 * 项目记忆候选与群聊审批工具，只装配给主 Agent。
 * <p>
 * 审批工具不带候选参数：候选由「审批回复引用的 Agent 消息」在服务端定位，
 * 因此模型无法通过伪造参数批准任意候选。
 */
public class MemoryApprovalTools {

    private final ProjectMemoryProposalService proposals;
    private final Long actor;
    private final Long projectId;
    private final Long groupId;
    private final Long runId;
    private final Long approvalMessageId;

    public MemoryApprovalTools(ProjectMemoryProposalService proposals, Long actor, Long projectId, Long groupId,
                               Long runId, Long approvalMessageId) {
        this.proposals = proposals;
        this.actor = actor;
        this.projectId = projectId;
        this.groupId = groupId;
        this.runId = runId;
        this.approvalMessageId = approvalMessageId;
    }

    /** 记录用户提出的项目记忆更新候选；候选当前不生效，等管理员审批。 */
    @Tool(name = "propose_project_memory", description = "提出项目记忆更新候选，当前不生效，需要项目管理员在群聊中审批。"
            + "先调用 read_project_memory 获取最新正文，产出整篇新内容并保留未涉及的原内容；"
            + "然后用普通文本向用户说明候选的新增、修改或删除，不要声称记忆已更新。"
            + "只能基于当前群已知信息提出，不要写入其他群的受限讨论。")
    public ToolResult proposeProjectMemory(
            @ToolParam(description = "候选 memory.md 全文，整篇替换；保留未涉及的原内容") String content) {
        if (content == null || content.isBlank()) {
            return ToolResult.error("INVALID_ARGUMENT", "content 不能为空");
        }
        return invoke(() -> {
            ProjectMemoryProposalEntity proposal = proposals.propose(actor, projectId, groupId, runId, content);
            ToolResult result = ToolResult.ok();
            result.put("proposalId", proposal.getId().toString());
            result.put("status", proposal.getStatus());
            return result;
        });
    }

    /** 管理员回复候选消息并 @Agent 后生效；回复对象、管理员身份与候选状态由服务端核验。 */
    @Tool(name = "approve_project_memory", description = "项目管理员回复项目记忆候选消息并 @你 时审批生效。"
            + "只在这条回复确实引用了你提出的候选消息时调用；服务端会核验真实发送者的项目管理员身份、"
            + "候选消息与候选状态，失败时如实说明审批未生效。")
    public ToolResult approveProjectMemory() {
        return invoke(() -> {
            ProjectMemoryResponse memory = proposals.approve(actor, projectId, groupId, approvalMessageId);
            ToolResult result = ToolResult.ok();
            result.put("content", memory.getContent());
            result.put("version", memory.getVersion());
            result.put("updatedAt", memory.getUpdatedAt().toString());
            return result;
        });
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
