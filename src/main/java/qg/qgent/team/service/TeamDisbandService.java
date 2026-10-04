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
import java.util.UUID;

/** 按外键依赖顺序，在同一事务中删除团队及其归属数据。 */
@Service
@RequiredArgsConstructor
public class TeamDisbandService {
    private final ProjectMapper projectMapper;
    private final NotificationMapper notificationMapper;
    private final EventMapper eventMapper;
    private final ChatGroupMapper chatGroupMapper;
    private final MessageMapper messageMapper;
    private final MemoryMapper memoryMapper;
    private final AgentRunMapper agentRuns;
    private final WorkspaceMapper workspaceMapper;
    private final SkillMapper skillMapper;
    private final AttachmentMapper attachmentMapper;
    private final AgentMapper agentMapper;
    private final TeamMapper teamMapper;

    /** 先清理各项目的数据，再删除团队 Agent 和团队记录。 */
    @Transactional
    public void deleteTeam(UUID teamId) {
        List<UUID> projectIds = projectMapper.selectList(Wrappers.<ProjectEntity>lambdaQuery()
                .eq(ProjectEntity::getTeamId, teamId)).stream().map(ProjectEntity::getId).toList();
        for (UUID projectId : projectIds) {
            deleteProject(projectId);
        }
        agentMapper.delete(Wrappers.<AgentEntity>lambdaQuery().eq(AgentEntity::getTeamId, teamId));
        teamMapper.deleteById(teamId);
    }

    /** 按外键依赖顺序删除项目及其数据。 */
    private void deleteProject(UUID projectId) {
        // 引用 chat_groups 且无级联的行须在 chat_groups 删除前清理
        notificationMapper.delete(Wrappers.<NotificationEntity>lambdaQuery()
                .eq(NotificationEntity::getProjectId, projectId));
        eventMapper.delete(Wrappers.<EventEntity>lambdaQuery().eq(EventEntity::getProjectId, projectId));

        List<UUID> groupIds = chatGroupMapper.selectList(Wrappers.<ChatGroupEntity>lambdaQuery()
                        .eq(ChatGroupEntity::getProjectId, projectId)).stream().map(ChatGroupEntity::getId)
                .toList();
        memoryMapper.delete(Wrappers.<MemoryEntity>lambdaQuery().eq(MemoryEntity::getProjectId, projectId));

        agentRuns.delete(Wrappers.<AgentRunEntity>lambdaQuery().eq(AgentRunEntity::getProjectId, projectId));
        int deleted;

        // 消息引用 Agent，需要在团队 Agent 与群聊之前删除。
        // messages 自引用外键 fk_msg_reply（reply_to_message_id -> id）且无级联、回复链可多级，
        // 同样须逐层删除不再被引用的叶子消息直至清空。
        if (!groupIds.isEmpty()) {
            do {
                deleted = messageMapper.deleteUnreferencedMessages(groupIds);
            } while (deleted > 0);
        }
        // workspaces 引用 project
        workspaceMapper.delete(Wrappers.<WorkspaceEntity>lambdaQuery().eq(WorkspaceEntity::getProjectId, projectId));

        skillMapper.delete(Wrappers.<SkillEntity>lambdaQuery().eq(SkillEntity::getProjectId, projectId));
        attachmentMapper.delete(Wrappers.<AttachmentEntity>lambdaQuery().eq(AttachmentEntity::getProjectId, projectId));

        // chat_groups 引用 project；group_agents 由 chat_groups 外键级联，
        // 须在 messages/events/notifications 之后删除
        chatGroupMapper.delete(Wrappers.<ChatGroupEntity>lambdaQuery()
                .eq(ChatGroupEntity::getProjectId, projectId));

        // project 级联 project_members
        projectMapper.deleteById(projectId);
    }
}
