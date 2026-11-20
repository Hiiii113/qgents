package qg.qgent.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.GroupAgentMapper;
import qg.qgent.chat.mapper.GroupReadStateMapper;
import qg.qgent.chat.mapper.GroupUserMapper;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.common.web.ApiException;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.support.TestIds;
import qg.qgent.user.mapper.UserGroupPreferenceMapper;

/**
 * 私聊会话与群聊共用 chat_groups 行，但群专属能力必须把它当成不存在：
 * 群资料/成员/邀请/置顶/已读、群记忆和跨群来源都读不到私聊。
 */
class DirectConversationIsolationTest {

    private final ChatGroupMapper groups = mock(ChatGroupMapper.class);
    private final GroupUserMapper groupUsers = mock(GroupUserMapper.class);
    private final GroupAgentMapper groupAgents = mock(GroupAgentMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);

    private final GroupService service =
            new GroupService(
                    groups,
                    mock(ProjectMemberMapper.class),
                    groupUsers,
                    mock(MessageMapper.class),
                    mock(GroupReadStateMapper.class),
                    mock(UserGroupPreferenceMapper.class),
                    access,
                    mock(EventService.class),
                    groupAgents,
                    mock(qg.qgent.agent.service.AgentService.class));

    private final Long projectId = TestIds.next();
    private final Long actor = TestIds.next();

    @Test
    void groupEndpointsTreatADirectConversationAsMissing() {
        Long conversationId = TestIds.next();
        when(groups.selectById(conversationId)).thenReturn(direct(conversationId));
        when(groupUsers.selectUserIds(conversationId)).thenReturn(List.of(actor));

        ApiException denied =
                catchThrowableOfType(
                        () -> service.requireChatGroup(projectId, conversationId, actor),
                        ApiException.class);

        assertThat(denied.code()).isEqualTo("GROUP_NOT_FOUND");
        assertThat(denied.status()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
    }

    @Test
    void realGroupsKeepWorkingThroughTheSameMembershipCheck() {
        Long groupId = TestIds.next();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setKind(ChatGroupEntity.KIND_GROUP);
        when(groups.selectById(groupId)).thenReturn(group);
        when(groupUsers.selectUserIds(groupId)).thenReturn(List.of(actor));

        assertThat(service.requireChatGroup(projectId, groupId, actor).getId()).isEqualTo(groupId);
    }

    @Test
    void agentsCrossGroupCandidatesNeverIncludeDirectConversations() {
        Long agentId = TestIds.next();
        Long groupId = TestIds.next();
        Long conversationId = TestIds.next();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        group.setKind(ChatGroupEntity.KIND_GROUP);
        when(groupAgents.selectGroupIds(agentId)).thenReturn(List.of(groupId, conversationId));
        when(groups.selectById(groupId)).thenReturn(group);
        when(groups.selectById(conversationId)).thenReturn(direct(conversationId));

        assertThat(service.groupsOfAgent(projectId, agentId))
                .extracting(ChatGroupEntity::getId)
                .containsExactly(groupId);
    }

    @Test
    void aDirectConversationCannotBeUsedAsACrossGroupSource() {
        Long conversationId = TestIds.next();
        Long agentId = TestIds.next();
        when(groups.selectById(conversationId)).thenReturn(direct(conversationId));
        when(groupUsers.selectUserIds(conversationId)).thenReturn(List.of(actor));

        CrossGroupAccessService crossGroup = new CrossGroupAccessService(service);

        assertThat(crossGroup.check(projectId, conversationId, actor, agentId))
                .isEqualTo(CrossGroupAccessService.Denial.REQUESTER_NOT_IN_GROUP);
    }

    private ChatGroupEntity direct(Long conversationId) {
        ChatGroupEntity conversation = new ChatGroupEntity();
        conversation.setId(conversationId);
        conversation.setProjectId(projectId);
        conversation.setKind(ChatGroupEntity.KIND_DIRECT);
        conversation.setDefaultGroup(false);
        conversation.setName("私聊");
        return conversation;
    }
}
