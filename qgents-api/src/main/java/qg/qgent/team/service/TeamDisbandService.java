package qg.qgent.team.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import qg.qgent.agent.entity.AgentEntity;
import qg.qgent.agent.entity.AgentRunEntity;
import qg.qgent.agent.mapper.AgentMapper;
import qg.qgent.agent.mapper.AgentRunMapper;
import qg.qgent.attachment.entity.AttachmentEntity;
import qg.qgent.attachment.mapper.AttachmentMapper;
import qg.qgent.chat.entity.RequirementGroupEntity;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.mapper.RequirementGroupMapper;
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

/**
 * 团队解散时的级联删除执行体。所有删除在单事务内完成，按子表先于父表的
 * 依赖顺序手工清理（相关外键在库中大多未声明 ON DELETE CASCADE）。
 * <p>
 * 只负责数据清理，权限校验（Team Owner）与状态检查由 {@link TeamService#disband} 完成。
 */
@Service
@RequiredArgsConstructor
public class TeamDisbandService {
    private final ProjectMapper projectMapper;
    private final NotificationMapper notificationMapper;
    private final EventMapper eventMapper;
    private final RequirementGroupMapper requirementGroupMapper;
    private final MessageMapper messageMapper;
    private final MemoryMapper memoryMapper;
    private final ProjectRepositoryMapper projectRepositoryMapper;
    private final MergeRequestMapper mergeRequestMapper;
    private final AgentRunMapper agentRuns;
    private final TaskMapper taskMapper;
    private final WorkspaceMapper workspaceMapper;
    private final SkillMapper skillMapper;
    private final AttachmentMapper attachmentMapper;
    private final GitHubRepositoryMapper githubRepositoryMapper;
    private final GitHubInstallationMapper githubInstallationMapper;
    private final AgentMapper agentMapper;
    private final TeamMapper teamMapper;

    /**
     * 删除团队及其全部归属数据：先逐项目清理项目下所有子表，再删 GitHub 仓库镜像
     * 与 GitHub 安装、Agent，最后删团队本体（team_members/team_invitations 由外键
     * ON DELETE CASCADE 移除）。本方法在调用方事务内执行（REQUIRED 传播），任一步失败整体回滚。
     *
     * @param teamId 待解散的团队 ID
     */
    @Transactional
    public void deleteTeam(UUID teamId) {
        List<UUID> projectIds = projectMapper.selectList(Wrappers.<ProjectEntity>lambdaQuery()
                .eq(ProjectEntity::getTeamId, teamId)).stream().map(ProjectEntity::getId).toList();
        for (UUID projectId : projectIds) {
            deleteProject(projectId);
        }
        // github_repositories 是团队级 GitHub 仓库镜像，通过 installation_id 引用
        // github_installations（fk_ghr_install 无级联），必须先删镜像再删安装记录；
        // 此时各项目已删除，project_repositories 经 projects 级联清除，其 repository_id
        // 对 github_repositories 的引用（fk_pr_repo）已解除。
        List<UUID> installationIds = githubInstallationMapper.selectList(Wrappers.<GitHubInstallationEntity>lambdaQuery()
                .eq(GitHubInstallationEntity::getTeamId, teamId)).stream().map(GitHubInstallationEntity::getId).toList();
        if (!installationIds.isEmpty()) {
            githubRepositoryMapper.delete(Wrappers.<GitHubRepositoryEntity>lambdaQuery()
                    .in(GitHubRepositoryEntity::getInstallationId, installationIds));
        }
        githubInstallationMapper.delete(Wrappers.<GitHubInstallationEntity>lambdaQuery()
                .eq(GitHubInstallationEntity::getTeamId, teamId));
        agentMapper.delete(Wrappers.<AgentEntity>lambdaQuery().eq(AgentEntity::getTeamId, teamId));
        teamMapper.deleteById(teamId);
    }

    /**
     * 删除单个项目及其全部归属数据。子表先于父表，引用关系严格按外键约束排序；
     * 依赖库内级联的（project_members、project_repositories、workspace_repositories、
     * requirement_group_repositories、group_agents、memory_message_sources、
     * merge_request 子表、agent_run 子表等）不在此重复删除。
     */
    private void deleteProject(UUID projectId) {
        // 引用 requirement_groups 且无级联的行须在 requirement_groups 删除前清理
        notificationMapper.delete(Wrappers.<NotificationEntity>lambdaQuery()
                .eq(NotificationEntity::getProjectId, projectId));
        eventMapper.delete(Wrappers.<EventEntity>lambdaQuery().eq(EventEntity::getProjectId, projectId));

        List<UUID> groupIds = requirementGroupMapper.selectList(Wrappers.<RequirementGroupEntity>lambdaQuery()
                        .eq(RequirementGroupEntity::getProjectId, projectId)).stream().map(RequirementGroupEntity::getId)
                .toList();
        // memory_message_sources 由 memories 外键级联删除
        memoryMapper.delete(Wrappers.<MemoryEntity>lambdaQuery().eq(MemoryEntity::getProjectId, projectId));

        // merge_requests 引用 project_repositories/tasks/workspaces，须在三者之前删除
        List<UUID> repoIds = projectRepositoryMapper.selectList(Wrappers.<ProjectRepositoryEntity>lambdaQuery()
                        .eq(ProjectRepositoryEntity::getProjectId, projectId)).stream().map(ProjectRepositoryEntity::getId)
                .toList();
        if (!repoIds.isEmpty()) {
            mergeRequestMapper.delete(Wrappers.<MergeRequestEntity>lambdaQuery()
                    .in(MergeRequestEntity::getProjectRepositoryId, repoIds));
        }

        agentRuns.delete(Wrappers.<AgentRunEntity>lambdaQuery().eq(AgentRunEntity::getProjectId, projectId));
        int deleted;
        taskMapper.delete(Wrappers.<TaskEntity>lambdaQuery().eq(TaskEntity::getProjectId, projectId));

        // messages 引用 agents（agents 在团队级 deleteTeam 最后删除）；tasks.trigger_message_id
        // 经 fk_task_message 引用 messages，须先删 tasks 再删 messages；亦须在 requirement_groups 之前。
        // messages 自引用外键 fk_msg_reply（reply_to_message_id -> id）且无级联、回复链可多级，
        // 同样须逐层删除不再被引用的叶子消息直至清空。
        if (!groupIds.isEmpty()) {
            do {
                deleted = messageMapper.deleteUnreferencedMessages(groupIds);
            } while (deleted > 0);
        }
        // workspaces 引用 project；workspace_repositories 由 workspaces 外键级联
        workspaceMapper.delete(Wrappers.<WorkspaceEntity>lambdaQuery().eq(WorkspaceEntity::getProjectId, projectId));

        skillMapper.delete(Wrappers.<SkillEntity>lambdaQuery().eq(SkillEntity::getProjectId, projectId));
        attachmentMapper.delete(Wrappers.<AttachmentEntity>lambdaQuery().eq(AttachmentEntity::getProjectId, projectId));

        // requirement_groups 引用 project；rgr/group_agents 由 requirement_groups 外键级联，
        // 须在 messages/events/notifications/tasks 之后删除
        requirementGroupMapper.delete(Wrappers.<RequirementGroupEntity>lambdaQuery()
                .eq(RequirementGroupEntity::getProjectId, projectId));

        // project 级联 project_members/project_repositories
        projectMapper.deleteById(projectId);
    }
}
