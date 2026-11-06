package qg.qgent.chat.service;

import qg.qgent.support.TestIds;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.chat.dto.GroupCreateRequest;
import qg.qgent.chat.dto.GroupInviteRequest;
import qg.qgent.common.web.ApiException;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.event.*;
import qg.qgent.chat.mapper.*;
import qg.qgent.project.event.ProjectCreatedEvent;
import qg.qgent.project.mapper.*;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.user.mapper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectChatTest {
    private final ChatGroupMapper groups = mock(ChatGroupMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final GroupUserMapper users = mock(GroupUserMapper.class);
    private final GroupService service = new GroupService(groups, mock(ProjectMemberMapper.class),
            users,
            mock(MessageMapper.class), mock(GroupReadStateMapper.class), mock(UserGroupPreferenceMapper.class),
            access, mock(EventService.class));

    @org.junit.jupiter.api.BeforeEach
    void assignGeneratedGroupIds() {
        when(groups.insert(any(ChatGroupEntity.class))).thenAnswer(call -> {
            call.getArgument(0, ChatGroupEntity.class).setId(TestIds.next());
            return 1;
        });
    }

    @Test
    void projectCreationCreatesOneDefaultChatAndRepeatedEnsureReusesIt() {
        Long projectId = TestIds.next();
        Long actor = TestIds.next();
        ChatGroupEntity[] stored = new ChatGroupEntity[1];
        when(groups.insert(any(ChatGroupEntity.class))).thenAnswer(call -> {
            stored[0] = call.getArgument(0);
            stored[0].setId(TestIds.next());
            return 1;
        });
        when(groups.selectById(any())).thenAnswer(call -> stored[0]);
        service.onProjectCreated(new ProjectCreatedEvent(projectId, "项目", actor));
        when(groups.selectOne(any())).thenReturn(stored[0]);
        assertThat(service.ensureProjectGroup(projectId, actor, "项目").getId()).isEqualTo(stored[0].getId().toString());
        verify(groups).insert(any(ChatGroupEntity.class));
        assertThat(stored[0].getProjectId()).isEqualTo(projectId);
    }

    @Test
    void projectMemberCanAccessChatWithoutSeparateGroupMembership() {
        Long projectId = TestIds.next(), groupId = TestIds.next(), actor = TestIds.next();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        when(groups.selectById(groupId)).thenReturn(group);
        service.requireGroupMember(projectId, groupId, actor);
        verify(access).requireProjectMember(projectId, actor);
    }

    @Test
    void newGroupIncludesCreatorAndSelectedUsersAndAgents() {
        Long projectId = TestIds.next(), actor = TestIds.next(), teammate = TestIds.next();
        Long agentId = qg.qgent.chat.QgentIdentity.ID;
        GroupCreateRequest body = new GroupCreateRequest(); body.setTitle("  方案讨论  ");
        body.setUserIds(List.of(teammate)); body.setAgentIds(List.of(agentId));
        var response = service.create(actor, projectId, body);
        ArgumentCaptor<ChatGroupEntity> saved = ArgumentCaptor.forClass(ChatGroupEntity.class);
        verify(groups).insert(saved.capture());
        assertThat(saved.getValue().getDefaultGroup()).isFalse();
        assertThat(response.getTitle()).isEqualTo("方案讨论");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(response).has("description")).isFalse();
        verify(users).insertUser(saved.getValue().getId(), actor);
        verify(users).insertUser(saved.getValue().getId(), teammate);
        assertThat(saved.getValue().getQgentEnabled()).isTrue();
        verify(access).requireProjectMember(projectId, teammate);
    }

    @Test
    void newGroupRequiresInvitedMembershipAndIsHiddenFromOthers() {
        Long projectId = TestIds.next(), groupId = TestIds.next(), actor = TestIds.next();
        ChatGroupEntity group = new ChatGroupEntity(); group.setId(groupId); group.setProjectId(projectId); group.setDefaultGroup(false);
        when(groups.selectById(groupId)).thenReturn(group);
        when(groups.listByProject(projectId)).thenReturn(List.of(group));
        when(users.selectUserIds(groupId)).thenReturn(List.of(TestIds.next()));
        assertThatThrownBy(() -> service.requireGroupMember(projectId, groupId, actor)).isInstanceOf(ApiException.class);
        assertThat(service.list(actor, projectId)).isEmpty();
        when(users.selectUserIds(groupId)).thenReturn(List.of(actor));
        service.requireGroupMember(projectId, groupId, actor);
    }

    @Test
    void inviteAddsMembersToExistingGroupWithoutChangingItsIdentity() {
        Long projectId = TestIds.next(), groupId = TestIds.next(), actor = TestIds.next(), teammate = TestIds.next();
        ChatGroupEntity group = new ChatGroupEntity(); group.setId(groupId); group.setProjectId(projectId); group.setDefaultGroup(false);
        when(groups.selectById(groupId)).thenReturn(group);
        when(users.selectUserIds(groupId)).thenReturn(List.of(actor));
        GroupInviteRequest body = new GroupInviteRequest(); body.setUserIds(List.of(teammate));
        service.invite(actor, projectId, groupId, body);
        verify(users).insertUser(groupId, teammate);
        verify(groups, never()).insert(any(ChatGroupEntity.class));
    }

    @Test
    void rejectsArbitraryAssistantBeforeSavingGroup() {
        Long projectId = TestIds.next(), actor = TestIds.next(), agentId = TestIds.next();
        GroupCreateRequest body = new GroupCreateRequest(); body.setTitle("讨论"); body.setAgentIds(List.of(agentId));
        assertThatThrownBy(() -> service.create(actor, projectId, body)).isInstanceOf(ApiException.class);
        verify(groups, never()).insert(any(ChatGroupEntity.class));
    }
}
