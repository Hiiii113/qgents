package qg.qgent.chat.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.event.*;
import qg.qgent.chat.mapper.*;
import qg.qgent.project.event.ProjectCreatedEvent;
import qg.qgent.project.mapper.*;
import qg.qgent.project.service.ProjectAccessService;
import qg.qgent.realtime.service.EventService;
import qg.qgent.user.mapper.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectChatTest {
    private final ChatGroupMapper groups = mock(ChatGroupMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final GroupService service = new GroupService(groups, mock(ProjectMemberMapper.class),
            mock(ProjectMapper.class), mock(GroupAgentMapper.class), mock(AgentMapper.class), mock(UserMapper.class),
            mock(MessageMapper.class), mock(GroupReadStateMapper.class), mock(UserGroupPreferenceMapper.class),
            access, mock(EventService.class));

    @Test
    void projectCreationCreatesOneDefaultChatAndRepeatedEnsureReusesIt() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        ChatGroupEntity[] stored = new ChatGroupEntity[1];
        when(groups.insert(any(ChatGroupEntity.class))).thenAnswer(call -> {
            stored[0] = call.getArgument(0);
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
        UUID projectId = UUID.randomUUID(), groupId = UUID.randomUUID(), actor = UUID.randomUUID();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        when(groups.selectById(groupId)).thenReturn(group);
        service.requireGroupMember(projectId, groupId, actor);
        verify(access).requireProjectMember(projectId, actor);
    }
}
