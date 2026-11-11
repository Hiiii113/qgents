package qg.qgent.agent.tool;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import qg.qgent.agent.dto.AgentApplicationRequest;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.service.AgentApplicationService;
import qg.qgent.harness.tool.ToolErrors;
import qg.qgent.harness.tool.ToolResult;

/**
 * 自定义 Agent 的申请与群聊审批工具，只装配给主 Agent。
 * <p>
 * 申请由当前项目、当前群和触发消息绑定；每次回复只公布一条申请，审批时服务端按「审批回复引用的 Agent 消息」
 * 定位那条申请，模型既不能选择申请，也不能批准没有被公布过的申请。
 */
public class AgentApplicationTools {

    private final AgentApplicationService applications;
    private final Long actor;
    private final Long projectId;
    private final Long groupId;
    private final Long approvalMessageId;
    // 本次运行公布的那一条申请；回复只有一条，运行结束时把它绑定到这条回复，管理员才能回复该消息审批。
    // 同一次运行既提交又公布时以最新一次为准，否则一条回复会对应多条申请。
    private Long announcedApplicationId;

    public AgentApplicationTools(
            AgentApplicationService applications,
            Long actor,
            Long projectId,
            Long groupId,
            Long approvalMessageId) {
        this.applications = applications;
        this.actor = actor;
        this.projectId = projectId;
        this.groupId = groupId;
        this.approvalMessageId = approvalMessageId;
    }

    /** 帮成员起草并向项目管理员提交自定义 Agent 申请；提交后仍待审批，不能使用。 */
    @Tool(
            name = "submit_agent_application",
            description =
                    "帮当前用户向项目管理员申请创建自定义 Agent。"
                            + "先根据对话整理名称、职责描述和系统提示词，向用户确认后再提交；头像 URL 可省略。"
                            + "提交后用普通文本把名称、职责和系统提示词摘要告诉用户，说明申请待项目管理员审批，"
                            + "通过后才能邀请入群；管理员可以直接回复这条回复并 @你 审批。")
    public ToolResult submitAgentApplication(
            @ToolParam(description = "Agent 名称，最多 120 字符") String name,
            @ToolParam(description = "Agent 职责描述，可省略", required = false) String description,
            @ToolParam(description = "Agent 系统提示词，说明它的身份、职责和协作方式") String systemPrompt,
            @ToolParam(description = "可选头像 URL；没有就省略", required = false) String avatarUrl) {
        if (name == null || name.isBlank() || systemPrompt == null || systemPrompt.isBlank()) {
            return ToolResult.error("INVALID_ARGUMENT", "name 和 systemPrompt 不能为空");
        }
        return call(() -> {
            AgentApplicationRequest request = new AgentApplicationRequest();
            request.setName(name);
            request.setDescription(description);
            request.setSystemPrompt(systemPrompt);
            request.setAvatarUrl(avatarUrl == null || avatarUrl.isBlank() ? null : avatarUrl);
            AgentEntity application = applications.apply(actor, projectId, groupId, request);
            announcedApplicationId = application.getId();
            ToolResult result = ToolResult.ok();
            result.put("applicationId", application.getId().toString());
            result.put("status", application.getStatus());
            return result;
        });
    }

    /**
     * 公布下一条待审批申请并用普通文本请管理员审批。
     * <p>
     * 一次只公布一条：回复只有一条，多列一条就会让管理员的一条回复对应多条申请。
     */
    @Tool(
            name = "announce_next_agent_application",
            description =
                    "公布本项目待审批申请中的下一条自定义 Agent 申请。"
                            + "用普通文本把这条申请的申请 ID、名称、职责和系统提示词摘要告诉项目管理员，"
                            + "并说明管理员回复本条消息并 @你 即可审批；不要声称已经生效。"
                            + "remainingCount 大于 0 表示还有待审批申请，管理员处理完这一条后再调用一次公布下一条。")
    public ToolResult announceNextAgentApplication() {
        return call(() -> {
            List<AgentEntity> pending = applications.pendingApplications(actor, projectId, groupId);
            ToolResult result = ToolResult.ok();
            result.put("remainingCount", String.valueOf(pending.size()));
            if (pending.isEmpty()) {
                return result;
            }
            AgentEntity next = pending.getFirst();
            announcedApplicationId = next.getId();
            result.put("application", summary(next));
            result.put("remainingCount", String.valueOf(pending.size() - 1));
            return result;
        });
    }

    /** 管理员回复公布消息并 @Agent 后审批通过；回复对象、管理员身份与申请状态由服务端核验。 */
    @Tool(
            name = "approve_agent_application",
            description =
                    "项目管理员回复待审批申请的公布消息并 @你 时审批通过。"
                            + "只在这条回复确实引用了公布该申请的消息时调用；服务端按回复对象定位具体申请，"
                            + "并核验真实发送者的项目管理员身份与申请状态，失败时如实说明审批未生效。")
    public ToolResult approveAgentApplication() {
        return call(() -> {
            AgentEntity agent = applications.approve(actor, projectId, groupId, approvalMessageId);
            ToolResult result = ToolResult.ok();
            result.put("applicationId", agent.getId().toString());
            result.put("status", agent.getStatus());
            return result;
        });
    }

    /** 管理员拒绝申请；被拒绝的申请不能激活，也不再占用项目 Agent 数量上限。 */
    @Tool(
            name = "reject_agent_application",
            description = "项目管理员回复待审批申请的公布消息并 @你 时拒绝该申请。" + "服务端同样按回复对象定位具体申请，并核验真实管理员与申请状态。")
    public ToolResult rejectAgentApplication() {
        return call(() -> {
            AgentEntity agent = applications.reject(actor, projectId, groupId, approvalMessageId);
            ToolResult result = ToolResult.ok();
            result.put("applicationId", agent.getId().toString());
            result.put("status", agent.getStatus());
            return result;
        });
    }

    /** 把本次运行公布的那一条申请绑定到它的 Agent 回复消息；没有公布申请时什么都不做。 */
    public void bindAnnounced(Long messageId) {
        if (announcedApplicationId != null) {
            applications.bindAnnouncement(announcedApplicationId, messageId);
        }
    }

    private static Map<String, String> summary(AgentEntity application) {
        return Map.of(
                "applicationId",
                application.getId().toString(),
                "name",
                application.getName(),
                "description",
                application.getDescription() == null ? "" : application.getDescription(),
                "systemPrompt",
                application.getSystemPrompt() == null ? "" : application.getSystemPrompt());
    }

    private ToolResult call(Supplier<ToolResult> action) {
        return ToolErrors.call("AGENT_APPLICATION_UNAVAILABLE", "Agent 申请操作失败", action);
    }
}
