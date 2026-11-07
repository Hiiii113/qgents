package qg.qgent.team.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import qg.qgent.harness.entity.AgentRunEntity;
import qg.qgent.harness.mapper.AgentRunMapper;
import qg.qgent.attachment.entity.AttachmentEntity;
import qg.qgent.attachment.mapper.AttachmentMapper;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.mapper.MessageMapper;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.notification.entity.NotificationEntity;
import qg.qgent.notification.mapper.NotificationMapper;
import qg.qgent.project.entity.ProjectEntity;
import qg.qgent.project.mapper.ProjectMapper;
import qg.qgent.project.storage.ProjectDirectoryService;
import qg.qgent.realtime.entity.EventEntity;
import qg.qgent.realtime.mapper.EventMapper;
import qg.qgent.skill.entity.SkillEntity;
import qg.qgent.skill.mapper.SkillMapper;
import qg.qgent.team.mapper.TeamMapper;
import qg.qgent.workspace.entity.WorkspaceEntity;
import qg.qgent.workspace.mapper.WorkspaceMapper;

import java.util.List;

/** 按外键依赖顺序，在同一事务中删除团队及其归属数据。 */
@Service
@RequiredArgsConstructor
public class TeamDisbandService {
    private final ProjectMapper projectMapper;
    private final NotificationMapper notificationMapper;
    private final EventMapper eventMapper;
    private final ChatGroupMapper chatGroupMapper;
    private final MessageMapper messageMapper;
    private final AgentRunMapper agentRuns;
    private final WorkspaceMapper workspaceMapper;
    private final SkillMapper skillMapper;
    private final AttachmentMapper attachmentMapper;
    private final TeamMapper teamMapper;
    private final ProjectDirectoryService projectDirectories;

    /** 先清理各项目的数据，再删除团队记录。 */
    @Transactional
    public void deleteTeam(Long teamId) {
        List<Long> projectIds = projectMapper.selectList(Wrappers.<ProjectEntity>lambdaQuery()
                .eq(ProjectEntity::getTeamId, teamId)).stream().map(ProjectEntity::getId).toList();
        for (Long projectId : projectIds) {
            deleteProject(projectId);
        }
        teamMapper.deleteById(teamId);
        // 项目目录不在事务内：数据库回滚时不能已经删掉磁盘上的记忆文件
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                projectIds.forEach(projectDirectories::delete);
            }
        });
    }

    /** 按外键依赖顺序删除项目及其数据。 */
    private void deleteProject(Long projectId) {
        // 引用 chat_groups 且无级联的行须在 chat_groups 删除前清理
        notificationMapper.delete(Wrappers.<NotificationEntity>lambdaQuery()
                .eq(NotificationEntity::getProjectId, projectId));
        eventMapper.delete(Wrappers.<EventEntity>lambdaQuery().eq(EventEntity::getProjectId, projectId));

        List<Long> groupIds = chatGroupMapper.selectList(Wrappers.<ChatGroupEntity>lambdaQuery()
                        .eq(ChatGroupEntity::getProjectId, projectId)).stream().map(ChatGroupEntity::getId)
                .toList();

        agentRuns.delete(Wrappers.<AgentRunEntity>lambdaQuery().eq(AgentRunEntity::getProjectId, projectId));
        int deleted;

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

        // chat_groups 引用 project，
        // 须在 messages/events/notifications 之后删除
        chatGroupMapper.delete(Wrappers.<ChatGroupEntity>lambdaQuery()
                .eq(ChatGroupEntity::getProjectId, projectId));

        // project 级联 project_members
        projectMapper.deleteById(projectId);
    }
}
