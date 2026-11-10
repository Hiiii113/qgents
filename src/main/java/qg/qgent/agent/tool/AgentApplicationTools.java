package qg.qgent.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import qg.qgent.agent.dto.AgentApplicationRequest;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.service.AgentApplicationService;
import qg.qgent.harness.tool.ToolErrors;
import qg.qgent.harness.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 自定义 Agent 的申请与群聊审批工具，只装配给主 Agent。
 * <p>
 * 申请由当前项目、当前群和触发消息绑定；审批时服务端按「审批回复引用的 Agent 消息」核验具体申请，
 * 模型无法用参数批准没有被公布过的申请。
 */
public class AgentApplicationTools {

    private final AgentApplicationService applications;
    private final Long actor;
    private final Long projectId;
    private final Long groupId;
    private final Long approvalMessageId;
    // 本次运行公布过的申请；运行结束时把回复消息绑定到它们，管理员才能回复该消息审批。
    private final Set<Long> announced = ConcurrentHashMap.newKeySet();

    public AgentApplicationTools(AgentApplicationService applications, Long actor, Long projectId, Long groupId,
                                 Long approvalMessageId) {
        this.applications = applications;
        this.actor = actor;
        this.projectId = projectId;
        this.groupId = groupId;
        this.approvalMessageId = approvalMessageId;
    }

    /** 帮成员起草并向项目管理员提交自定义 Agent 申请；提交后仍待审批，不能使用。 */
    @Tool(name = "submit_agent_application", description = "帮当前用户向项目管理员申请创建自定义 Agent。"
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
            announced.add(application.getId());
            ToolResult result = ToolResult.ok();
            result.put("applicationId", application.getId().toString());
            result.put("status", application.getStatus());
            return result;
        });
    }

    /** 列出待审批申请并用普通文本向管理员公布；管理员回复这条回复并 @Agent 即可审批。 */
    @Tool(name = "list_pending_agent_applications", description = "列出本项目待审批的自定义 Agent 申请。"
            + "用普通文本把每条的申请 ID、名称、职责和系统提示词摘要告诉项目管理员，"
            + "并说明管理员回复本条消息并 @你 即可审批；不要声称已经生效。")
    public ToolResult listPendingAgentApplications() {
        return call(() -> {
            List<Map<String, String>> rows = new ArrayList<>();
            for (AgentEntity application : applications.pendingApplications(actor, projectId, groupId)) {
                announced.add(application.getId());
                rows.add(summary(application));
            }
            ToolResult result = ToolResult.ok();
            result.put("applications", rows);
            return result;
        });
    }

    /** 管理员回复公布消息并 @Agent 后审批通过；回复对象、管理员身份与申请状态由服务端核验。 */
    @Tool(name = "approve_agent_application", description = "项目管理员回复待审批申请的公布消息并 @你 时审批通过。"
            + "只在这条回复确实引用了公布该申请的消息时调用；服务端会核验真实发送者的项目管理员身份、"
            + "具体申请与申请状态，失败时如实说明审批未生效。")
    public ToolResult approveAgentApplication(
            @ToolParam(description = "list_pending_agent_applications 返回的申请 ID") String applicationId) {
        return applicationId(applicationId).map(id -> call(() -> {
            AgentEntity agent = applications.approve(actor, projectId, groupId, id, approvalMessageId);
            ToolResult result = ToolResult.ok();
            result.put("applicationId", agent.getId().toString());
            result.put("status", agent.getStatus());
            return result;
        })).orElseGet(() -> ToolResult.error("INVALID_ARGUMENT", "applicationId 必须是数字 ID"));
    }

    /** 管理员拒绝申请；被拒绝的申请不能激活，也不再占用项目 Agent 数量上限。 */
    @Tool(name = "reject_agent_application", description = "项目管理员回复待审批申请的公布消息并 @你 时拒绝该申请。"
            + "服务端同样核验真实管理员、具体申请与申请状态。")
    public ToolResult rejectAgentApplication(
            @ToolParam(description = "list_pending_agent_applications 返回的申请 ID") String applicationId) {
        return applicationId(applicationId).map(id -> call(() -> {
            AgentEntity agent = applications.reject(actor, projectId, groupId, id, approvalMessageId);
            ToolResult result = ToolResult.ok();
            result.put("applicationId", agent.getId().toString());
            result.put("status", agent.getStatus());
            return result;
        })).orElseGet(() -> ToolResult.error("INVALID_ARGUMENT", "applicationId 必须是数字 ID"));
    }

    /** 把本次运行公布过的申请绑定到它的 Agent 回复消息；没有公布任何申请时什么都不做。 */
    public void bindAnnounced(Long messageId) {
        if (!announced.isEmpty()) {
            applications.bindAnnouncements(List.copyOf(announced), messageId);
        }
    }

    private Optional<Long> applicationId(String value) {
        try {
            return Optional.of(Long.valueOf(value == null ? "" : value.trim()));
        } catch (NumberFormatException notId) {
            return Optional.empty();
        }
    }

    private static Map<String, String> summary(AgentEntity application) {
        return Map.of("applicationId", application.getId().toString(), "name", application.getName(),
                "description", application.getDescription() == null ? "" : application.getDescription(),
                "systemPrompt", application.getSystemPrompt() == null ? "" : application.getSystemPrompt());
    }

    private ToolResult call(Supplier<ToolResult> action) {
        return ToolErrors.call("AGENT_APPLICATION_UNAVAILABLE", "Agent 申请操作失败", action);
    }
}
