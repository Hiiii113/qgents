package qg.qgent.memory.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;

import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.service.AgentService;
import qg.qgent.chat.dto.MessageResponse;
import qg.qgent.chat.dto.MessageSendRequest;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.service.GroupService;
import qg.qgent.chat.service.MessageService;
import qg.qgent.common.web.ApiException;
import qg.qgent.memory.config.ProjectMemoryDiscoveryProperties;
import qg.qgent.memory.entity.GroupMemoryStateEntity;
import qg.qgent.memory.entity.ProjectMemoryProposalEntity;
import qg.qgent.memory.mapper.GroupMemoryStateMapper;
import qg.qgent.memory.mapper.ProjectMemoryProposalMapper;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.support.TestIds;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 项目记忆候选发现：只从默认群记忆提炼、认可审批生效、重复扫描与失败重试。 */
class ProjectMemoryDiscoveryServiceTest {

    private static final int MAX_CHARS = 2000;
    private static final int INTERVAL_MINUTES = 1440;
    private static final int RETRY_DELAY_MINUTES = 60;
    private static final int BATCH_SIZE = 10;

    @TempDir
    Path root;

    private final GroupMemoryStateMapper states = mock(GroupMemoryStateMapper.class);
    private final ChatGroupMapper groups = mock(ChatGroupMapper.class);
    private final ProjectMemoryProposalMapper proposalMapper = mock(ProjectMemoryProposalMapper.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final MessageService chat = mock(MessageService.class);
    private final AgentService agents = mock(AgentService.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final ChatModel model = mock(ChatModel.class);

    private GroupMemoryService groupMemories;
    private ProjectMemoryService projectMemories;
    private ProjectMemoryProposalService proposals;
    private ProjectMemoryDiscoveryService service;

    @BeforeEach
    void setUp() {
        ProjectDirectoryService directories = new ProjectDirectoryService(root.toString(), root.toString());
        groupMemories = new GroupMemoryService(access, mock(GroupService.class), directories, MAX_CHARS);
        projectMemories = new ProjectMemoryService(access, directories, mock(EventService.class), MAX_CHARS);
        proposals = new ProjectMemoryProposalService(proposalMapper, projectMemories, access, groups, messages,
                projects, chat, agents);
        service = new ProjectMemoryDiscoveryService(states, groups, groupMemories, projectMemories, proposals, model,
                properties(), MAX_CHARS);
        when(proposalMapper.insert(any(ProjectMemoryProposalEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ProjectMemoryProposalEntity.class).setId(TestIds.next());
            return 1;
        });
        // Mockito 对 Long 返回 0 而不是 null；显式声明默认没有待审批候选
        when(proposalMapper.selectPendingId(any())).thenReturn(null);
    }

    @Test
    void discoversCandidateFromDefaultGroupMemoryAndAdminApprovalUpdatesProjectMemory() {
        Long project = TestIds.next(), group = TestIds.next(), agent = TestIds.next(), admin = TestIds.next();
        groupMemories.write(project, group, "- 议题：登录方式\n- 结论：先做邮箱登录");
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        when(states.selectById(group)).thenReturn(state(project, group));
        when(agents.defaultAgent(project)).thenReturn(agent(agent));
        Long candidateMessage = TestIds.next();
        when(chat.sendAsAgent(any(), any(), any())).thenReturn(message(candidateMessage, group));
        when(model.call(any(Prompt.class))).thenReturn(reply("# 项目记忆\n- 登录方式：先用邮箱登录"));
        claims(group);

        service.discover(project, group);

        // 群记忆是唯一的提炼来源：模型拿到的是群记忆正文和当前项目记忆，而不是原始聊天
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions()).anySatisfy(instruction ->
                assertThat(instruction.getText()).contains("先做邮箱登录"));
        // 默认 Agent 在默认群以普通文本提出候选，候选正文就是待写入的整篇项目记忆
        ArgumentCaptor<MessageSendRequest> sent = ArgumentCaptor.forClass(MessageSendRequest.class);
        verify(chat).sendAsAgent(eq(agent), eq(group), sent.capture());
        assertThat(sent.getValue().getType()).isEqualTo("TEXT");
        assertThat(sent.getValue().getContent().get("text").toString())
                .contains("项目管理员", "- 登录方式：先用邮箱登录");
        ProjectMemoryProposalEntity proposal = insertedProposal();
        assertThat(proposal.getMessageId()).isEqualTo(candidateMessage);
        assertThat(proposal.getStatus()).isEqualTo("PENDING");
        assertThat(proposal.getSourceMemoryVersion()).isEqualTo(MemoryText.version("- 议题：登录方式\n- 结论：先做邮箱登录"));
        assertThat(proposal.getBaseVersion()).isEqualTo(projectMemories.read(project).getVersion());
        // 候选本身不生效，等管理员回复候选消息并 @Agent
        assertThat(projectMemories.read(project).getContent()).isEmpty();
        Long approvalMessage = TestIds.next();
        when(messages.selectById(approvalMessage)).thenReturn(approvalReply(approvalMessage, group, admin, candidateMessage));
        when(proposalMapper.selectByMessageId(candidateMessage)).thenReturn(proposal);
        when(proposalMapper.markApplied(any(), any(), any(), any())).thenReturn(1);

        assertThat(proposals.approve(admin, project, group, approvalMessage).getContent())
                .isEqualTo("# 项目记忆\n- 登录方式：先用邮箱登录");

        assertThat(projectMemories.get(admin, project).getContent()).isEqualTo("# 项目记忆\n- 登录方式：先用邮箱登录");
        verify(states).finishDiscovery(eq(group), any(), any(), any());
    }

    @Test
    void plainMemberCannotApproveDiscoveredCandidate() {
        Long project = TestIds.next(), group = TestIds.next(), member = TestIds.next();
        ProjectMemoryProposalEntity proposal = discoveredProposal(project, group);
        Long candidateMessage = proposal.getMessageId(), approvalMessage = TestIds.next();
        when(proposalMapper.selectByMessageId(candidateMessage)).thenReturn(proposal);
        when(messages.selectById(approvalMessage)).thenReturn(approvalReply(approvalMessage, group, member, candidateMessage));
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限"))
                .when(access).requireProjectAdmin(project, member);

        assertThatThrownBy(() -> proposals.approve(member, project, group, approvalMessage))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "PROJECT_ADMIN_REQUIRED");
        assertThat(projectMemories.read(project).getContent()).isEmpty();
        verify(proposalMapper, never()).markApplied(any(), any(), any(), any());
    }

    @Test
    void restrictedGroupContentNeverReachesTheModelOrDefaultGroup() {
        Long project = TestIds.next(), group = TestIds.next();
        groupMemories.write(project, group, "- 受限结论：调整了报价");
        when(groups.selectById(group)).thenReturn(group(group, project, false));
        claims(group);

        service.discover(project, group);

        verifyNoInteractions(model);
        verify(chat, never()).sendAsAgent(any(), any(), any());
        verify(proposalMapper, never()).insert(any(ProjectMemoryProposalEntity.class));
        assertThat(projectMemories.read(project).getContent()).isEmpty();
    }

    @Test
    void sameGroupMemoryVersionIsNotDiscoveredTwice() {
        Long project = TestIds.next(), group = TestIds.next();
        groupMemories.write(project, group, "- 结论：先做邮箱登录");
        GroupMemoryStateEntity state = state(project, group);
        state.setDiscoveredVersion(MemoryText.version("- 结论：先做邮箱登录"));
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        when(states.selectById(group)).thenReturn(state);
        claims(group);

        service.discover(project, group);

        verifyNoInteractions(model);
        verify(chat, never()).sendAsAgent(any(), any(), any());
        verify(states).finishDiscovery(eq(group), any(), any(), any());
    }

    @Test
    void pendingCandidateBlocksAnotherDiscoveryWithoutMovingTheVersion() {
        Long project = TestIds.next(), group = TestIds.next();
        groupMemories.write(project, group, "- 结论：先做邮箱登录");
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        when(states.selectById(group)).thenReturn(state(project, group));
        claims(group);
        when(proposalMapper.selectPendingId(project)).thenReturn(TestIds.next());

        service.discover(project, group);

        verifyNoInteractions(model);
        verify(chat, never()).sendAsAgent(any(), any(), any());
        // 版本不推进：候选处理完后还要判断这次群记忆
        verify(states, never()).finishDiscovery(any(), any(), any(), any());
    }

    @Test
    void nothingToDiscoverRecordsTheVersionWithoutPosting() {
        Long project = TestIds.next(), group = TestIds.next();
        groupMemories.write(project, group, "- 闲聊：明天吃火锅");
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        when(states.selectById(group)).thenReturn(state(project, group));
        claims(group);
        when(model.call(any(Prompt.class))).thenReturn(reply("NONE"));

        service.discover(project, group);

        verify(chat, never()).sendAsAgent(any(), any(), any());
        verify(states).finishDiscovery(eq(group), eq(MemoryText.version("- 闲聊：明天吃火锅")), any(), any());
    }

    @Test
    void failureIsRecordedForRetryAndNothingIsPublished() {
        Long project = TestIds.next(), group = TestIds.next();
        groupMemories.write(project, group, "- 结论：先做邮箱登录");
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        when(states.selectById(group)).thenReturn(state(project, group));
        claims(group);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("模型调用超时"));

        service.discover(project, group);

        verify(chat, never()).sendAsAgent(any(), any(), any());
        verify(proposalMapper, never()).insert(any(ProjectMemoryProposalEntity.class));
        verify(states).recordDiscoveryFailure(eq(group), contains("模型调用超时"), any(), any());
        verify(states, never()).finishDiscovery(any(), any(), any(), any());
        assertThat(projectMemories.read(project).getContent()).isEmpty();
    }

    @Test
    void skipsWhenTheSlotIsNotClaimable() {
        Long project = TestIds.next(), group = TestIds.next();
        when(states.claimDiscovery(eq(group), any(), any())).thenReturn(0);

        service.discover(project, group);

        verify(states, never()).selectById(any());
        verifyNoInteractions(model);
        verify(chat, never()).sendAsAgent(any(), any(), any());
    }

    @Test
    void scanDiscoversEveryDueSourceGroup() {
        Long project = TestIds.next(), group = TestIds.next();
        when(states.selectDiscoveryCandidates(any(), eq(BATCH_SIZE)))
                .thenReturn(List.of(state(project, group)));
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        when(states.selectById(group)).thenReturn(state(project, group));
        claims(group);
        when(agents.defaultAgent(project)).thenReturn(agent(TestIds.next()));

        service.scan();

        verify(states).claimDiscovery(eq(group), any(), any());
    }

    private void claims(Long group) {
        when(states.claimDiscovery(eq(group), any(), any())).thenReturn(1);
        when(states.finishDiscovery(eq(group), any(), any(), any())).thenReturn(1);
    }

    private ProjectMemoryProposalEntity insertedProposal() {
        ArgumentCaptor<ProjectMemoryProposalEntity> captor = ArgumentCaptor.forClass(ProjectMemoryProposalEntity.class);
        verify(proposalMapper).insert(captor.capture());
        return captor.getValue();
    }

    private ProjectMemoryProposalEntity discoveredProposal(Long projectId, Long groupId) {
        ProjectMemoryProposalEntity proposal = new ProjectMemoryProposalEntity();
        proposal.setId(TestIds.next());
        proposal.setProjectId(projectId);
        proposal.setGroupId(groupId);
        proposal.setMessageId(TestIds.next());
        proposal.setContent("# 项目记忆");
        proposal.setBaseVersion(projectMemories.read(projectId).getVersion());
        proposal.setStatus("PENDING");
        return proposal;
    }

    private ProjectMemoryDiscoveryProperties properties() {
        ProjectMemoryDiscoveryProperties properties = new ProjectMemoryDiscoveryProperties();
        properties.setIntervalMinutes(INTERVAL_MINUTES);
        properties.setRetryDelayMinutes(RETRY_DELAY_MINUTES);
        properties.setBatchSize(BATCH_SIZE);
        return properties;
    }

    private GroupMemoryStateEntity state(Long projectId, Long groupId) {
        GroupMemoryStateEntity state = new GroupMemoryStateEntity();
        state.setProjectId(projectId);
        state.setGroupId(groupId);
        state.setDiscoveryAttemptCount(0);
        return state;
    }

    private ChatGroupEntity group(Long groupId, Long projectId, boolean defaultGroup) {
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setDefaultGroup(defaultGroup);
        return group;
    }

    private AgentEntity agent(Long agentId) {
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setDefaultAgent(true);
        return agent;
    }

    private MessageResponse message(Long messageId, Long groupId) {
        MessageResponse message = new MessageResponse();
        message.setId(messageId.toString());
        message.setGroupId(groupId.toString());
        return message;
    }

    private MessageEntity approvalReply(Long messageId, Long groupId, Long author, Long replyTo) {
        MessageEntity message = new MessageEntity();
        message.setId(messageId);
        message.setGroupId(groupId);
        message.setAuthorUserId(author);
        message.setSenderType("USER");
        message.setReplyToMessageId(replyTo);
        return message;
    }

    private ChatResponse reply(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
