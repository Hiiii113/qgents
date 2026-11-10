package qg.qgent.agent.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import qg.qgent.agent.dto.AgentApplicationRequest;
import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.support.TestIds;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 自定义 Agent 申请：成员提交、项目内数量上限、管理员在默认群按真实审批消息激活。 */
class AgentApplicationServiceTest {

    private static final int MAX_AGENTS = 3;

    private final AgentMapper agents = mock(AgentMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final ChatGroupMapper groups = mock(ChatGroupMapper.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final AgentApplicationService service = new AgentApplicationService(agents, access, projects, groups,
            messages, MAX_AGENTS);

    @Test
    void memberApplicationIsStoredAsPendingAgentWithItsOwnPrompt() {
        Long actor = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        when(groups.selectById(group)).thenReturn(group(group, project, true));

        AgentEntity application = service.apply(actor, project, group, request("  评审助手 "));

        assertThat(application.getStatus()).isEqualTo(AgentEntity.STATUS_PENDING);
        assertThat(application.getProjectId()).isEqualTo(project);
        assertThat(application.getName()).isEqualTo("评审助手");
        assertThat(application.getDescription()).isEqualTo("负责代码评审");
        assertThat(application.getSystemPrompt()).isEqualTo("你负责评审 Pull Request");
        assertThat(application.getAvatarUrl()).isEqualTo("https://cdn.example.com/review.png");
        assertThat(application.getProposedBy()).isEqualTo(actor);
        assertThat(application.getDefaultAgent()).isFalse();
        verify(agents).insert(application);
        // 计数与插入都在项目行锁内：并发申请无法同时通过上限检查。
        var order = inOrder(projects, agents);
        order.verify(projects).selectByIdForUpdate(project);
        order.verify(agents).countCountedByProject(project);
        order.verify(agents).insert(application);
    }

    @Test
    void applicationIsRefusedOnceTheProjectAgentLimitIsReached() {
        Long actor = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        when(agents.countCountedByProject(project)).thenReturn(MAX_AGENTS);

        ApiException error = assertThatApiError(() -> service.apply(actor, project, group, request("评审助手")));

        assertThat(error.code()).isEqualTo("AGENT_LIMIT_REACHED");
        verify(agents, never()).insert(any(AgentEntity.class));
    }

    @Test
    void applicationWithoutNameOrSystemPromptIsRejected() {
        Long actor = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        when(groups.selectById(group)).thenReturn(group(group, project, true));

        assertThat(assertThatApiError(() -> service.apply(actor, project, group, request("  "))).code())
                .isEqualTo("AGENT_APPLICATION_INVALID");
        AgentApplicationRequest withoutPrompt = request("评审助手");
        withoutPrompt.setSystemPrompt(null);
        assertThat(assertThatApiError(() -> service.apply(actor, project, group, withoutPrompt)).code())
                .isEqualTo("AGENT_APPLICATION_INVALID");
        verify(agents, never()).insert(any(AgentEntity.class));
    }

    @Test
    void descriptionAndAvatarWithUnreasonableLengthAreRejected() {
        Long actor = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        AgentApplicationRequest tooLongDescription = request("评审助手");
        tooLongDescription.setDescription("描".repeat(2001));
        AgentApplicationRequest tooLongAvatar = request("评审助手");
        tooLongAvatar.setAvatarUrl("https://cdn.example.com/" + "a".repeat(2048));

        assertThat(assertThatApiError(() -> service.apply(actor, project, group, tooLongDescription)).code())
                .isEqualTo("AGENT_APPLICATION_INVALID");
        assertThat(assertThatApiError(() -> service.apply(actor, project, group, tooLongAvatar)).code())
                .isEqualTo("AGENT_APPLICATION_INVALID");
        verify(agents, never()).insert(any(AgentEntity.class));
    }

    @Test
    void applicationInARestrictedGroupIsRefused() {
        Long actor = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        when(groups.selectById(group)).thenReturn(group(group, project, false));

        ApiException error = assertThatApiError(() -> service.apply(actor, project, group, request("评审助手")));

        assertThat(error.code()).isEqualTo("AGENT_SOURCE_GROUP_RESTRICTED");
        verify(agents, never()).insert(any(AgentEntity.class));
    }

    @Test
    void projectMemberOutsideTheProjectCannotApply() {
        Long actor = TestIds.next(), project = TestIds.next();
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见"))
                .when(access).requireProjectMember(project, actor);

        assertThat(assertThatApiError(() -> service.apply(actor, project, null, request("评审助手"))).code())
                .isEqualTo("PROJECT_NOT_FOUND");
        verify(agents, never()).insert(any(AgentEntity.class));
        verify(projects, never()).selectByIdForUpdate(any());
    }

    @Test
    void enabledAgentsAreListedForGroupInvites() {
        Long actor = TestIds.next(), project = TestIds.next();
        AgentEntity defaultAgent = agent(TestIds.next(), project, AgentEntity.STATUS_ACTIVE);
        when(agents.selectActiveByProject(project)).thenReturn(List.of(defaultAgent));

        assertThat(service.enabledAgents(actor, project)).containsExactly(defaultAgent);
        verify(access).requireProjectMember(project, actor);
    }

    @Test
    void pendingApplicationsAreOnlyListedInTheDefaultGroup() {
        Long actor = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        AgentEntity pending = agent(TestIds.next(), project, AgentEntity.STATUS_PENDING);
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        when(agents.selectPendingByProject(project)).thenReturn(List.of(pending));

        assertThat(service.pendingApplications(actor, project, group)).containsExactly(pending);
        when(groups.selectById(group)).thenReturn(group(group, project, false));
        assertThat(assertThatApiError(() -> service.pendingApplications(actor, project, group)).code())
                .isEqualTo("AGENT_SOURCE_GROUP_RESTRICTED");
    }

    @Test
    void pendingApplicationsAreOnlyVisibleToProjectAdmins() {
        Long member = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限"))
                .when(access).requireProjectAdmin(project, member);

        assertThat(assertThatApiError(() -> service.pendingApplications(member, project, group)).code())
                .isEqualTo("PROJECT_ADMIN_REQUIRED");
        verify(agents, never()).selectPendingByProject(any());
    }

    @Test
    void adminApprovalOfTheAnnouncedApplicationActivatesIt() {
        Long admin = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        Long announcedMessage = TestIds.next(), approvalMessage = TestIds.next();
        AgentEntity application = pendingApplication(project, announcedMessage);
        AgentEntity activated = agent(application.getId(), project, AgentEntity.STATUS_ACTIVE);
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        when(messages.selectById(approvalMessage)).thenReturn(reply(approvalMessage, group, admin, announcedMessage));
        when(agents.selectById(application.getId())).thenReturn(application, activated);
        when(agents.activate(application.getId(), admin)).thenReturn(1);

        assertThat(service.approve(admin, project, group, application.getId(), approvalMessage)).isSameAs(activated);
        verify(access).requireProjectAdmin(project, admin);
        verify(agents).activate(application.getId(), admin);
    }

    @Test
    void approvalFollowsTheLatestAnnouncementOfTheApplication() {
        Long admin = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        Long submitReply = TestIds.next(), listingReply = TestIds.next(), approvalMessage = TestIds.next();
        // 提交时公布的回复先绑定，管理员让 Agent 列出待审批申请时重新绑定到新的公布消息。
        AgentEntity application = pendingApplication(project, listingReply);
        AgentEntity activated = agent(application.getId(), project, AgentEntity.STATUS_ACTIVE);
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        when(agents.selectById(application.getId())).thenReturn(application, activated);
        when(agents.activate(application.getId(), admin)).thenReturn(1);
        when(messages.selectById(approvalMessage))
                .thenReturn(reply(approvalMessage, group, admin, listingReply));

        assertThat(service.approve(admin, project, group, application.getId(), approvalMessage)).isSameAs(activated);

        AgentEntity stale = pendingApplication(project, listingReply);
        when(agents.selectById(stale.getId())).thenReturn(stale);
        when(messages.selectById(approvalMessage)).thenReturn(reply(approvalMessage, group, admin, submitReply));
        assertThat(assertThatApiError(() ->
                service.approve(admin, project, group, stale.getId(), approvalMessage)).code())
                .isEqualTo("AGENT_APPLICATION_NOT_FOUND");
    }

    @Test
    void plainMemberCannotApprove() {
        Long member = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限"))
                .when(access).requireProjectAdmin(project, member);

        assertThat(assertThatApiError(() -> service.approve(member, project, group, TestIds.next(), TestIds.next()))
                .code()).isEqualTo("PROJECT_ADMIN_REQUIRED");
        verify(agents, never()).activate(any(), any());
    }

    @Test
    void approvalReplyMustQuoteTheMessageThatAnnouncedThisApplication() {
        Long admin = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        Long announcedMessage = TestIds.next(), approvalMessage = TestIds.next(), otherMessage = TestIds.next();
        AgentEntity application = pendingApplication(project, announcedMessage);
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        when(messages.selectById(approvalMessage)).thenReturn(reply(approvalMessage, group, admin, otherMessage));
        when(agents.selectById(application.getId())).thenReturn(application);

        assertThat(assertThatApiError(() ->
                service.approve(admin, project, group, application.getId(), approvalMessage)).code())
                .isEqualTo("AGENT_APPLICATION_NOT_FOUND");
        verify(agents, never()).activate(any(), any());
    }

    @Test
    void approvalReplyFromAnotherUserOrGroupIsNotApproval() {
        Long admin = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        Long announcedMessage = TestIds.next(), approvalMessage = TestIds.next();
        AgentEntity application = pendingApplication(project, announcedMessage);
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        when(agents.selectById(application.getId())).thenReturn(application);
        when(messages.selectById(approvalMessage))
                .thenReturn(reply(approvalMessage, group, TestIds.next(), announcedMessage));

        assertThat(assertThatApiError(() ->
                service.approve(admin, project, group, application.getId(), approvalMessage)).code())
                .isEqualTo("AGENT_APPLICATION_NOT_FOUND");
        verify(agents, never()).activate(any(), any());
    }

    @Test
    void applicationOfAnotherProjectCannotBeApprovedHere() {
        Long admin = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        Long announcedMessage = TestIds.next(), approvalMessage = TestIds.next();
        AgentEntity foreign = pendingApplication(TestIds.next(), announcedMessage);
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        when(messages.selectById(approvalMessage)).thenReturn(reply(approvalMessage, group, admin, announcedMessage));
        when(agents.selectById(foreign.getId())).thenReturn(foreign);

        assertThat(assertThatApiError(() ->
                service.approve(admin, project, group, foreign.getId(), approvalMessage)).code())
                .isEqualTo("AGENT_APPLICATION_NOT_FOUND");
        verify(agents, never()).activate(any(), any());
    }

    @Test
    void repeatedApprovalIsRejectedWithoutASecondActivation() {
        Long admin = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        Long announcedMessage = TestIds.next(), approvalMessage = TestIds.next();
        AgentEntity application = pendingApplication(project, announcedMessage);
        application.setStatus(AgentEntity.STATUS_ACTIVE);
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        when(messages.selectById(approvalMessage)).thenReturn(reply(approvalMessage, group, admin, announcedMessage));
        when(agents.selectById(application.getId())).thenReturn(application);

        assertThat(assertThatApiError(() ->
                service.approve(admin, project, group, application.getId(), approvalMessage)).code())
                .isEqualTo("AGENT_APPLICATION_NOT_PENDING");
        verify(agents, never()).activate(any(), any());
    }

    @Test
    void concurrentApprovalLosesWhenTheRowIsNoLongerPending() {
        Long admin = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        Long announcedMessage = TestIds.next(), approvalMessage = TestIds.next();
        AgentEntity application = pendingApplication(project, announcedMessage);
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        when(messages.selectById(approvalMessage)).thenReturn(reply(approvalMessage, group, admin, announcedMessage));
        when(agents.selectById(application.getId())).thenReturn(application);
        // 另一个审批先提交：条件更新影响 0 行，本次不能再次激活。
        when(agents.activate(application.getId(), admin)).thenReturn(0);

        assertThat(assertThatApiError(() ->
                service.approve(admin, project, group, application.getId(), approvalMessage)).code())
                .isEqualTo("AGENT_APPLICATION_NOT_PENDING");
    }

    @Test
    void adminRejectionFreesTheApplicationWithoutActivatingIt() {
        Long admin = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        Long announcedMessage = TestIds.next(), approvalMessage = TestIds.next();
        AgentEntity application = pendingApplication(project, announcedMessage);
        AgentEntity rejected = agent(application.getId(), project, AgentEntity.STATUS_REJECTED);
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        when(messages.selectById(approvalMessage)).thenReturn(reply(approvalMessage, group, admin, announcedMessage));
        when(agents.selectById(application.getId())).thenReturn(application, rejected);
        when(agents.reject(application.getId(), admin)).thenReturn(1);

        assertThat(service.reject(admin, project, group, application.getId(), approvalMessage))
                .isSameAs(rejected);
        verify(agents, never()).activate(any(), any());
    }

    @Test
    void rejectedApplicationCannotBeApprovedAfterwards() {
        Long admin = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        Long announcedMessage = TestIds.next(), approvalMessage = TestIds.next();
        AgentEntity application = pendingApplication(project, announcedMessage);
        application.setStatus(AgentEntity.STATUS_REJECTED);
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        when(messages.selectById(approvalMessage)).thenReturn(reply(approvalMessage, group, admin, announcedMessage));
        when(agents.selectById(application.getId())).thenReturn(application);

        assertThat(assertThatApiError(() ->
                service.approve(admin, project, group, application.getId(), approvalMessage)).code())
                .isEqualTo("AGENT_APPLICATION_NOT_PENDING");
    }

    @Test
    void announcedApplicationsAreBoundToTheAgentReplyMessage() {
        Long project = TestIds.next(), message = TestIds.next();
        AgentEntity first = pendingApplication(project, null), second = pendingApplication(project, null);
        ArgumentCaptor<Long> bound = ArgumentCaptor.forClass(Long.class);

        service.bindAnnouncements(List.of(first.getId(), second.getId()), message);

        verify(agents, org.mockito.Mockito.times(2)).bindAnnouncement(bound.capture(), org.mockito.ArgumentMatchers.eq(message));
        assertThat(bound.getAllValues()).containsExactly(first.getId(), second.getId());
    }

    private AgentEntity pendingApplication(Long projectId, Long announcedMessageId) {
        AgentEntity application = agent(TestIds.next(), projectId, AgentEntity.STATUS_PENDING);
        application.setProposedBy(TestIds.next());
        application.setAnnouncedMessageId(announcedMessageId);
        return application;
    }

    private AgentEntity agent(Long agentId, Long projectId, String status) {
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setProjectId(projectId);
        agent.setStatus(status);
        agent.setDefaultAgent(false);
        return agent;
    }

    private AgentApplicationRequest request(String name) {
        AgentApplicationRequest request = new AgentApplicationRequest();
        request.setName(name);
        request.setDescription(" 负责代码评审 ");
        request.setSystemPrompt("你负责评审 Pull Request");
        request.setAvatarUrl("https://cdn.example.com/review.png");
        return request;
    }

    private ChatGroupEntity group(Long groupId, Long projectId, boolean defaultGroup) {
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setDefaultGroup(defaultGroup);
        return group;
    }

    private MessageEntity reply(Long messageId, Long groupId, Long author, Long replyTo) {
        MessageEntity message = new MessageEntity();
        message.setId(messageId);
        message.setGroupId(groupId);
        message.setAuthorUserId(author);
        message.setSenderType("USER");
        message.setReplyToMessageId(replyTo);
        return message;
    }

    private ApiException assertThatApiError(org.junit.jupiter.api.function.Executable action) {
        return assertThrows(ApiException.class, action);
    }
}
