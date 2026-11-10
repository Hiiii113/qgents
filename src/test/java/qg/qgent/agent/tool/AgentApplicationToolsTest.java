package qg.qgent.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.http.HttpStatus;

import qg.qgent.agent.dto.AgentApplicationRequest;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.service.AgentApplicationService;
import qg.qgent.common.web.ApiException;
import qg.qgent.support.TestIds;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Agent 申请与审批工具：申请帮成员起草，审批只使用触发消息，服务端拒绝转为工具错误。 */
class AgentApplicationToolsTest {

    private final AgentApplicationService applications = mock(AgentApplicationService.class);
    private final Long actor = TestIds.next();
    private final Long projectId = TestIds.next();
    private final Long groupId = TestIds.next();
    private final Long approvalMessageId = TestIds.next();
    private final AgentApplicationTools tools = new AgentApplicationTools(applications, actor, projectId, groupId,
            approvalMessageId);

    @Test
    void exposesApplicationAndApprovalTools() {
        assertThat(ToolCallbacks.from(tools)).extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder("submit_agent_application", "announce_next_agent_application",
                        "approve_agent_application", "reject_agent_application");
    }

    @Test
    void submittedApplicationIsBoundToTheCurrentGroupAndWaitsForApproval() {
        AgentEntity application = pending(TestIds.next());
        when(applications.apply(eq(actor), eq(projectId), eq(groupId), any(AgentApplicationRequest.class)))
                .thenReturn(application);

        Map<String, Object> result = tools.submitAgentApplication("评审助手", "负责代码评审", "你负责评审 Pull Request",
                "");

        assertThat(result).containsEntry("ok", true).containsEntry("applicationId", application.getId().toString())
                .containsEntry("status", "PENDING");
        verify(applications).apply(eq(actor), eq(projectId), eq(groupId), org.mockito.ArgumentMatchers.argThat(
                request -> "评审助手".equals(request.getName()) && "负责代码评审".equals(request.getDescription())
                        && "你负责评审 Pull Request".equals(request.getSystemPrompt())
                        && request.getAvatarUrl() == null));
    }

    @Test
    void applicationWithoutNameIsRejectedWithoutTouchingTheService() {
        Map<String, Object> result = tools.submitAgentApplication("  ", null, "提示词", null);

        assertThat(result).containsEntry("ok", false).containsEntry("errorCode", "INVALID_ARGUMENT");
        verify(applications, never()).apply(any(), any(), any(), any());
    }

    @Test
    void oneApplicationIsAnnouncedPerReplySoTheAdminReplyNamesExactlyOne() {
        AgentEntity first = pending(TestIds.next()), second = pending(TestIds.next());
        when(applications.pendingApplications(actor, projectId, groupId)).thenReturn(List.of(first, second));

        Map<String, Object> announced = tools.announceNextAgentApplication();

        assertThat(announced).containsEntry("ok", true).containsEntry("remainingCount", "1");
        assertThat(((Map<?, ?>) announced.get("application")).get("applicationId"))
                .isEqualTo(first.getId().toString());
        Long replyMessage = TestIds.next();
        tools.bindAnnounced(replyMessage);
        verify(applications).bindAnnouncement(first.getId(), replyMessage);
        verify(applications, never()).bindAnnouncement(eq(second.getId()), any());
    }

    @Test
    void nothingIsAnnouncedWhenTheQueueIsEmpty() {
        when(applications.pendingApplications(actor, projectId, groupId)).thenReturn(List.of());

        Map<String, Object> announced = tools.announceNextAgentApplication();

        assertThat(announced).containsEntry("ok", true).containsEntry("remainingCount", "0");
        assertThat(announced).doesNotContainKey("application");
        tools.bindAnnounced(TestIds.next());
        verify(applications, never()).bindAnnouncement(any(), any());
    }

    @Test
    void theReplyBindsOnlyTheApplicationItLastAnnounced() {
        AgentEntity submitted = pending(TestIds.next()), next = pending(TestIds.next());
        when(applications.apply(eq(actor), eq(projectId), eq(groupId), any(AgentApplicationRequest.class)))
                .thenReturn(submitted);
        when(applications.pendingApplications(actor, projectId, groupId)).thenReturn(List.of(next));

        tools.submitAgentApplication("评审助手", null, "你负责评审", null);
        tools.announceNextAgentApplication();
        tools.bindAnnounced(TestIds.next());

        verify(applications, never()).bindAnnouncement(eq(submitted.getId()), any());
        verify(applications).bindAnnouncement(eq(next.getId()), any());
    }

    @Test
    void approvalUsesTheTriggerMessageAndReturnsTheActivatedAgent() {
        AgentEntity activated = pending(TestIds.next());
        activated.setStatus(AgentEntity.STATUS_ACTIVE);
        when(applications.approve(actor, projectId, groupId, approvalMessageId)).thenReturn(activated);

        Map<String, Object> result = tools.approveAgentApplication();

        assertThat(result).containsEntry("ok", true).containsEntry("status", "ACTIVE")
                .containsEntry("applicationId", activated.getId().toString());
        // 审批不接受模型给的申请 ID：服务端只按审批回复引用的消息定位申请。
        verify(applications).approve(actor, projectId, groupId, approvalMessageId);
    }

    @Test
    void approvalRejectionBecomesToolError() {
        when(applications.approve(actor, projectId, groupId, approvalMessageId))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限"));

        Map<String, Object> result = tools.approveAgentApplication();

        assertThat(result).containsEntry("ok", false).containsEntry("errorCode", "PROJECT_ADMIN_REQUIRED");
    }

    @Test
    void rejectionMarksTheApplicationAndReportsIt() {
        AgentEntity rejected = pending(TestIds.next());
        rejected.setStatus(AgentEntity.STATUS_REJECTED);
        when(applications.reject(actor, projectId, groupId, approvalMessageId)).thenReturn(rejected);

        Map<String, Object> result = tools.rejectAgentApplication();

        assertThat(result).containsEntry("ok", true).containsEntry("status", "REJECTED");
    }

    private AgentEntity pending(Long agentId) {
        AgentEntity application = new AgentEntity();
        application.setId(agentId);
        application.setProjectId(projectId);
        application.setName("评审助手");
        application.setStatus(AgentEntity.STATUS_PENDING);
        return application;
    }
}
