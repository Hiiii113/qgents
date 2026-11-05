package qg.qgent.team.service;

import qg.qgent.support.TestIds;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.harness.mapper.AgentRunMapper;
import qg.qgent.attachment.entity.AttachmentEntity;
import qg.qgent.attachment.mapper.AttachmentMapper;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.memory.entity.MemoryEntity;
import qg.qgent.memory.mapper.MemoryMapper;
import qg.qgent.notification.entity.NotificationEntity;
import qg.qgent.notification.mapper.NotificationMapper;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.realtime.entity.EventEntity;
import qg.qgent.realtime.mapper.EventMapper;
import qg.qgent.skill.entity.SkillEntity;
import qg.qgent.skill.mapper.SkillMapper;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.mapper.WorkspaceMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamDisbandServiceTest {

    /**
     * 纯单元测试未启动 MyBatis/Spring，MyBatis-Plus 的 lambda 包装器在裸 JVM 下
     * 无法通过懒初始化拿到实体列缓存；这里显式注册全部涉及实体的 TableInfo，
     * 与生产环境由 MyBatis 启动时注册的行为保持一致，保证包装器构造确定性。
     */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        for (Class<?> entity : List.of(ProjectEntity.class, NotificationEntity.class, EventEntity.class,
                ChatGroupEntity.class, MessageEntity.class,
                MemoryEntity.class, AgentRunEntity.class,
                WorkspaceEntity.class,
                SkillEntity.class, AttachmentEntity.class,
                ChatGroupEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final NotificationMapper notificationMapper = mock(NotificationMapper.class);
    private final EventMapper eventMapper = mock(EventMapper.class);
    private final ChatGroupMapper chatGroupMapper = mock(ChatGroupMapper.class);
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final MemoryMapper memoryMapper = mock(MemoryMapper.class);
    private final AgentRunMapper agentRuns = mock(AgentRunMapper.class);
    private final WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
    private final SkillMapper skillMapper = mock(SkillMapper.class);
    private final AttachmentMapper attachmentMapper = mock(AttachmentMapper.class);
    private final TeamMapper teamMapper = mock(TeamMapper.class);
    private final TeamDisbandService service = new TeamDisbandService(projectMapper, notificationMapper,
            eventMapper, chatGroupMapper, messageMapper, memoryMapper,
            agentRuns,
            workspaceMapper, skillMapper, attachmentMapper,
            teamMapper);

    @Test
    void deleteTeamCleansProjectsInDependencyOrder() {
        Long teamId = TestIds.next();
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();

        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(teamId);
        when(projectMapper.selectList(any())).thenReturn(List.of(project));

        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        when(chatGroupMapper.selectList(any())).thenReturn(List.of(group));

        service.deleteTeam(teamId);

        // 关键依赖顺序：子表先于父表，引用行先于被引用行；agents 在项目级引用清理后最后删除。
        var order = inOrder(notificationMapper, eventMapper, messageMapper,
                memoryMapper, agentRuns,
                workspaceMapper, skillMapper, attachmentMapper, chatGroupMapper, projectMapper,
                teamMapper);
        order.verify(notificationMapper).delete(any());
        order.verify(eventMapper).delete(any());
        order.verify(memoryMapper).delete(any());
        order.verify(agentRuns).delete(any());
        order.verify(messageMapper).deleteUnreferencedMessages(any());
        order.verify(workspaceMapper).delete(any());
        order.verify(skillMapper).delete(any());
        order.verify(attachmentMapper).delete(any());
        order.verify(chatGroupMapper).delete(any());
        order.verify(projectMapper).deleteById(projectId);
        order.verify(teamMapper).deleteById(teamId);
    }

}
