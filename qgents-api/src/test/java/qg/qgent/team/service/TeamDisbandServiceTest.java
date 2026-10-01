package qg.qgent.team.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.entity.AgentRunEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.agent.mapper.AgentRunMapper;
import qg.qgent.attachment.entity.AttachmentEntity;
import qg.qgent.attachment.mapper.AttachmentMapper;
import qg.qgent.chat.entity.MessageEntity;
import qg.qgent.chat.entity.RequirementGroupEntity;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.mapper.RequirementGroupMapper;
import qg.qgent.common.persistence.typehandler.UuidBinaryTypeHandler;
import qg.qgent.github.entity.GitHubInstallationEntity;
import qg.qgent.github.entity.GitHubRepositoryEntity;
import qg.qgent.github.mapper.GitHubInstallationMapper;
import qg.qgent.github.mapper.GitHubRepositoryMapper;
import qg.qgent.memory.entity.MemoryEntity;
import qg.qgent.memory.mapper.MemoryMapper;
import qg.qgent.mergerequest.entity.MergeRequestEntity;
import qg.qgent.mergerequest.mapper.MergeRequestMapper;
import qg.qgent.notification.entity.NotificationEntity;
import qg.qgent.notification.mapper.NotificationMapper;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.entity.ProjectRepositoryEntity;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.mapper.ProjectRepositoryMapper;
import qg.qgent.realtime.entity.EventEntity;
import qg.qgent.realtime.mapper.EventMapper;
import qg.qgent.skill.entity.SkillEntity;
import qg.qgent.skill.mapper.SkillMapper;
import qg.qgent.task.entity.TaskEntity;
import qg.qgent.task.mapper.TaskMapper;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.mapper.WorkspaceMapper;

import java.util.List;
import java.util.UUID;

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
        // 与生产配置 type-handlers-package: qg.qgent.common.persistence.typehandler 对齐，注册 UUID 类型处理器
        configuration.getTypeHandlerRegistry().register(UuidBinaryTypeHandler.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        for (Class<?> entity : List.of(ProjectEntity.class, NotificationEntity.class, EventEntity.class,
                RequirementGroupEntity.class, MessageEntity.class,
                MemoryEntity.class, ProjectRepositoryEntity.class, MergeRequestEntity.class, AgentRunEntity.class,
                TaskEntity.class, WorkspaceEntity.class,
                SkillEntity.class, AttachmentEntity.class, GitHubRepositoryEntity.class,
                GitHubInstallationEntity.class, AgentEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final NotificationMapper notificationMapper = mock(NotificationMapper.class);
    private final EventMapper eventMapper = mock(EventMapper.class);
    private final RequirementGroupMapper requirementGroupMapper = mock(RequirementGroupMapper.class);
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final MemoryMapper memoryMapper = mock(MemoryMapper.class);
    private final ProjectRepositoryMapper projectRepositoryMapper = mock(ProjectRepositoryMapper.class);
    private final MergeRequestMapper mergeRequestMapper = mock(MergeRequestMapper.class);
    private final AgentRunMapper agentRuns = mock(AgentRunMapper.class);
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
    private final SkillMapper skillMapper = mock(SkillMapper.class);
    private final AttachmentMapper attachmentMapper = mock(AttachmentMapper.class);
    private final GitHubRepositoryMapper githubRepositoryMapper = mock(GitHubRepositoryMapper.class);
    private final GitHubInstallationMapper githubInstallationMapper = mock(GitHubInstallationMapper.class);
    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final TeamMapper teamMapper = mock(TeamMapper.class);
    private final TeamDisbandService service = new TeamDisbandService(projectMapper, notificationMapper,
            eventMapper, requirementGroupMapper, messageMapper, memoryMapper,
            projectRepositoryMapper, mergeRequestMapper, agentRuns, taskMapper,
            workspaceMapper, skillMapper, attachmentMapper, githubRepositoryMapper,
            githubInstallationMapper, agentMapper, teamMapper);

    @Test
    void deleteTeamCleansProjectsInDependencyOrder() {
        UUID teamId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();

        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(teamId);
        when(projectMapper.selectList(any())).thenReturn(List.of(project));

        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(groupId);
        group.setProjectId(projectId);
        when(requirementGroupMapper.selectList(any())).thenReturn(List.of(group));

        ProjectRepositoryEntity repo = new ProjectRepositoryEntity();
        repo.setId(repoId);
        repo.setProjectId(projectId);
        when(projectRepositoryMapper.selectList(any())).thenReturn(List.of(repo));

        GitHubInstallationEntity installation = new GitHubInstallationEntity();
        installation.setId(UUID.randomUUID());
        installation.setTeamId(teamId);
        when(githubInstallationMapper.selectList(any())).thenReturn(List.of(installation));

        service.deleteTeam(teamId);

        // 关键依赖顺序：子表先于父表，引用行先于被引用行；agents 在项目级引用清理后最后删除。
        // github_repositories 引用 github_installations（fk_ghr_install 无级联），必须先删镜像再删安装记录。
        var order = inOrder(notificationMapper, eventMapper, messageMapper,
                memoryMapper, mergeRequestMapper, agentRuns, taskMapper,
                workspaceMapper, skillMapper, attachmentMapper, requirementGroupMapper, projectMapper,
                githubRepositoryMapper, githubInstallationMapper, agentMapper, teamMapper);
        order.verify(notificationMapper).delete(any());
        order.verify(eventMapper).delete(any());
        order.verify(memoryMapper).delete(any());
        order.verify(mergeRequestMapper).delete(any());
        order.verify(agentRuns).delete(any());
        order.verify(taskMapper).delete(any());
        order.verify(messageMapper).deleteUnreferencedMessages(any());
        order.verify(workspaceMapper).delete(any());
        order.verify(skillMapper).delete(any());
        order.verify(attachmentMapper).delete(any());
        order.verify(requirementGroupMapper).delete(any());
        order.verify(projectMapper).deleteById(projectId);
        order.verify(githubRepositoryMapper).delete(any());
        order.verify(githubInstallationMapper).delete(any());
        order.verify(agentMapper).delete(any());
        order.verify(teamMapper).deleteById(teamId);
    }

}
