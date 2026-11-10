package qg.qgent.memory.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.common.web.ApiException;
import qg.qgent.memory.dto.ProjectMemoryResponse;
import qg.qgent.memory.entity.ProjectMemoryProposalEntity;
import qg.qgent.memory.mapper.ProjectMemoryProposalMapper;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.support.TestIds;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 项目记忆候选：默认群提出、管理员回复审批、来源与并发状态校验。 */
class ProjectMemoryProposalServiceTest {

    private static final int MAX_CHARS = 1000;

    @TempDir
    Path root;

    private final ProjectMemoryProposalMapper proposals = mock(ProjectMemoryProposalMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final ChatGroupMapper groups = mock(ChatGroupMapper.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final EventService events = mock(EventService.class);
    private ProjectMemoryService memories;
    private ProjectMemoryProposalService service;

    @BeforeEach
    void setUp() {
        memories = new ProjectMemoryService(access, new ProjectDirectoryService(root.toString(), root.toString()),
                events, MAX_CHARS);
        service = new ProjectMemoryProposalService(proposals, memories, access, groups, messages, projects);
        when(proposals.insert(any(ProjectMemoryProposalEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ProjectMemoryProposalEntity.class).setId(TestIds.next());
            return 1;
        });
    }

    @Test
    void proposeBindsCandidateToDefaultGroupRunAndCurrentVersion() {
        Long actor = TestIds.next(), project = TestIds.next(), group = TestIds.next(), run = TestIds.next();
        when(groups.selectById(group)).thenReturn(group(group, project, true));

        ProjectMemoryProposalEntity proposal = service.propose(actor, project, group, run, "# 新约定");

        assertThat(proposal.getStatus()).isEqualTo("PENDING");
        assertThat(proposal.getContent()).isEqualTo("# 新约定");
        assertThat(proposal.getBaseVersion()).isEqualTo(memories.get(actor, project).getVersion());
        assertThat(proposal.getProposedBy()).isEqualTo(actor);
        assertThat(proposal.getAgentRunId()).isEqualTo(run);
        assertThat(proposal.getGroupId()).isEqualTo(group);
        assertThat(proposal.getProjectId()).isEqualTo(project);
        verify(proposals).insert(proposal);
    }

    @Test
    void proposeRefusesRestrictedGroupContentBeforePublishingAnything() {
        Long actor = TestIds.next(), project = TestIds.next(), group = TestIds.next(), run = TestIds.next();
        when(groups.selectById(group)).thenReturn(group(group, project, false));

        ApiException error = assertThrows(ApiException.class,
                () -> service.propose(actor, project, group, run, "受限群结论"));

        assertThat(error.code()).isEqualTo("MEMORY_SOURCE_GROUP_RESTRICTED");
        verify(proposals, never()).insert(any(ProjectMemoryProposalEntity.class));
        assertThat(memories.get(actor, project).getContent()).isEmpty();
    }

    @Test
    void proposeStopsAtProjectMembershipBeforeReadingGroup() {
        Long actor = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见"))
                .when(access).requireProjectMember(project, actor);

        ApiException error = assertThrows(ApiException.class,
                () -> service.propose(actor, project, group, TestIds.next(), "内容"));

        assertThat(error.code()).isEqualTo("PROJECT_NOT_FOUND");
        verify(proposals, never()).insert(any(ProjectMemoryProposalEntity.class));
        verify(groups, never()).selectById(any());
    }

    @Test
    void proposeRejectsCandidateIdenticalToCurrentMemory() {
        Long actor = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        memories.applyApproved(project, "# 已有约定", memories.get(actor, project).getVersion());

        ApiException error = assertThrows(ApiException.class,
                () -> service.propose(actor, project, group, TestIds.next(), "# 已有约定"));

        assertThat(error.code()).isEqualTo("MEMORY_PROPOSAL_UNCHANGED");
        verify(proposals, never()).insert(any(ProjectMemoryProposalEntity.class));
    }

    @Test
    void proposeRejectsSecondCandidateForTheSameRun() {
        Long actor = TestIds.next(), project = TestIds.next(), group = TestIds.next(), run = TestIds.next();
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        // 唯一键 uk_memory_proposal_run 是并发下的真正保障，撞键时返回同一提示。
        when(proposals.insert(any(ProjectMemoryProposalEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_memory_proposal_run"));

        ApiException error = assertThrows(ApiException.class,
                () -> service.propose(actor, project, group, run, "# 新约定"));

        assertThat(error.code()).isEqualTo("MEMORY_PROPOSAL_PENDING");
    }

    @Test
    void adminApprovalReplyAppliesCandidateAndMarksItApplied() {
        Long member = TestIds.next(), admin = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        ProjectMemoryProposalEntity proposal = service.propose(member, project, group, TestIds.next(), "# 新约定");
        Long candidateMessage = TestIds.next(), approvalMessage = TestIds.next();
        proposal.setMessageId(candidateMessage);
        when(proposals.selectByMessageId(candidateMessage)).thenReturn(proposal);
        when(messages.selectById(approvalMessage))
                .thenReturn(approvalReply(approvalMessage, group, admin, candidateMessage));
        when(proposals.markApplied(any(), any(), any(), any())).thenReturn(1);

        ProjectMemoryResponse saved = service.approve(admin, project, group, approvalMessage);

        assertThat(saved.getContent()).isEqualTo("# 新约定");
        assertThat(memories.get(admin, project).getContent()).isEqualTo("# 新约定");
        verify(proposals).markApplied(proposal.getId(), admin, approvalMessage, saved.getVersion());
        // 先锁项目行，再读候选并写记忆：不同候选的并发审批在同一项目内串行。
        var order = inOrder(projects, messages, proposals);
        order.verify(projects).selectByIdForUpdate(project);
        order.verify(messages).selectById(approvalMessage);
        order.verify(proposals).selectByMessageId(candidateMessage);
    }

    @Test
    void plainMemberCannotApproveAndMemoryStaysUnchanged() {
        Long member = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限"))
                .when(access).requireProjectAdmin(project, member);

        ApiException error = assertThrows(ApiException.class,
                () -> service.approve(member, project, group, TestIds.next()));

        assertThat(error.code()).isEqualTo("PROJECT_ADMIN_REQUIRED");
        assertThat(memories.get(member, project).getContent()).isEmpty();
        verify(projects, never()).selectByIdForUpdate(any());
        verify(proposals, never()).markApplied(any(), any(), any(), any());
    }

    @Test
    void replyWithoutCandidateMessageIsNotApproval() {
        Long admin = TestIds.next(), project = TestIds.next(), group = TestIds.next(), approvalMessage = TestIds.next();
        when(messages.selectById(approvalMessage))
                .thenReturn(approvalReply(approvalMessage, group, admin, null));

        ApiException error = assertThrows(ApiException.class,
                () -> service.approve(admin, project, group, approvalMessage));

        assertThat(error.code()).isEqualTo("MEMORY_PROPOSAL_NOT_FOUND");
        assertThat(memories.get(admin, project).getContent()).isEmpty();
        verify(proposals, never()).markApplied(any(), any(), any(), any());
    }

    @Test
    void replyToANonCandidateMessageIsNotApproval() {
        Long admin = TestIds.next(), project = TestIds.next(), group = TestIds.next(), approvalMessage = TestIds.next();
        when(messages.selectById(approvalMessage))
                .thenReturn(approvalReply(approvalMessage, group, admin, TestIds.next()));
        when(proposals.selectByMessageId(any())).thenReturn(null);

        ApiException error = assertThrows(ApiException.class,
                () -> service.approve(admin, project, group, approvalMessage));

        assertThat(error.code()).isEqualTo("MEMORY_PROPOSAL_NOT_FOUND");
        verify(proposals, never()).markApplied(any(), any(), any(), any());
    }

    @Test
    void candidateFromAnotherGroupCannotBeApprovedHere() {
        Long admin = TestIds.next(), project = TestIds.next(), group = TestIds.next(), otherGroup = TestIds.next();
        Long candidateMessage = TestIds.next(), approvalMessage = TestIds.next();
        ProjectMemoryProposalEntity proposal = pendingProposal(project, otherGroup, "# 别的群的结论");
        when(proposals.selectByMessageId(candidateMessage)).thenReturn(proposal);
        when(messages.selectById(approvalMessage))
                .thenReturn(approvalReply(approvalMessage, group, admin, candidateMessage));

        ApiException error = assertThrows(ApiException.class,
                () -> service.approve(admin, project, group, approvalMessage));

        assertThat(error.code()).isEqualTo("MEMORY_PROPOSAL_NOT_FOUND");
        assertThat(memories.get(admin, project).getContent()).isEmpty();
        verify(proposals, never()).markApplied(any(), any(), any(), any());
    }

    @Test
    void repeatedApprovalIsRejectedWithoutSecondWrite() {
        Long admin = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        Long candidateMessage = TestIds.next(), approvalMessage = TestIds.next();
        ProjectMemoryProposalEntity proposal = pendingProposal(project, group, "# 新约定");
        proposal.setStatus("APPLIED");
        when(proposals.selectByMessageId(candidateMessage)).thenReturn(proposal);
        when(messages.selectById(approvalMessage))
                .thenReturn(approvalReply(approvalMessage, group, admin, candidateMessage));

        ApiException error = assertThrows(ApiException.class,
                () -> service.approve(admin, project, group, approvalMessage));

        assertThat(error.code()).isEqualTo("MEMORY_PROPOSAL_NOT_PENDING");
        assertThat(memories.get(admin, project).getContent()).isEmpty();
        verify(proposals, never()).markApplied(any(), any(), any(), any());
    }

    @Test
    void approvalRejectedWhenMemoryChangedAfterCandidate() {
        Long member = TestIds.next(), admin = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        ProjectMemoryProposalEntity proposal = service.propose(member, project, group, TestIds.next(), "# 新约定");
        Long candidateMessage = TestIds.next(), approvalMessage = TestIds.next();
        proposal.setMessageId(candidateMessage);
        // 候选提出后另一条已审批的更新先落地：旧候选不能覆盖新内容。
        memories.applyApproved(project, "# 后写入的约定", memories.get(admin, project).getVersion());
        when(proposals.selectByMessageId(candidateMessage)).thenReturn(proposal);
        when(messages.selectById(approvalMessage))
                .thenReturn(approvalReply(approvalMessage, group, admin, candidateMessage));

        ApiException error = assertThrows(ApiException.class,
                () -> service.approve(admin, project, group, approvalMessage));

        assertThat(error.code()).isEqualTo("MEMORY_VERSION_CONFLICT");
        assertThat(memories.get(admin, project).getContent()).isEqualTo("# 后写入的约定");
        verify(proposals, never()).markApplied(any(), any(), any(), any());
    }

    @Test
    void approvalFailureOnDiskKeepsProposalPending() throws IOException {
        Long member = TestIds.next(), admin = TestIds.next(), project = TestIds.next(), group = TestIds.next();
        when(groups.selectById(group)).thenReturn(group(group, project, true));
        ProjectMemoryProposalEntity proposal = service.propose(member, project, group, TestIds.next(), "# 新约定");
        Long candidateMessage = TestIds.next(), approvalMessage = TestIds.next();
        proposal.setMessageId(candidateMessage);
        when(proposals.selectByMessageId(candidateMessage)).thenReturn(proposal);
        when(messages.selectById(approvalMessage))
                .thenReturn(approvalReply(approvalMessage, group, admin, candidateMessage));
        Path memoryFile = root.resolve(project.toString()).resolve("memory.md");
        Files.delete(memoryFile);
        Files.createDirectory(memoryFile);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.approve(admin, project, group, approvalMessage));

        assertThat(error).isInstanceOf(IllegalStateException.class);
        verify(proposals, never()).markApplied(any(), any(), any(), any());
    }

    @Test
    void bindsCandidateToTheAgentReplyMessageOfItsRun() {
        Long run = TestIds.next(), message = TestIds.next();

        service.bindMessage(run, message);

        verify(proposals).bindMessage(run, message);
    }

    private ProjectMemoryProposalEntity pendingProposal(Long projectId, Long groupId, String content) {
        ProjectMemoryProposalEntity proposal = new ProjectMemoryProposalEntity();
        proposal.setId(TestIds.next());
        proposal.setProjectId(projectId);
        proposal.setGroupId(groupId);
        proposal.setContent(content);
        proposal.setBaseVersion("v");
        proposal.setStatus("PENDING");
        return proposal;
    }

    private ChatGroupEntity group(Long groupId, Long projectId, boolean defaultGroup) {
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setDefaultGroup(defaultGroup);
        return group;
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
}
