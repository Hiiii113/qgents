package qg.qgent.realtime.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.realtime.websocket.RealtimeFrame;
import qg.qgent.team.mapper.TeamMemberMapper;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 把已提交的业务事实转换成实时帧：提交后按项目、团队或用户维度计算可见用户，再通过
 * {@link RealtimeHub} 推送。事件不落库，断线后由客户端重新拉取快照。
 */
@Service
public class EventService {
    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final ProjectMemberMapper projectMemberMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final ChatGroupMapper groupMapper;
    private final RealtimeHub realtimeHub;

    public EventService(
        ProjectMemberMapper projectMemberMapper,
        TeamMemberMapper teamMemberMapper,
        ChatGroupMapper groupMapper,
        RealtimeHub realtimeHub) {
        this.projectMemberMapper = projectMemberMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.groupMapper = groupMapper;
        this.realtimeHub = realtimeHub;
    }

    /**
     * 发布项目级事件：群聊事件只发给该群成员，其它事件发给全部项目成员。
     */
    public void publish(
        Long projectId,
        Long groupId,
        String eventType,
        String resourceId,
        Map<String, Object> payload) {
        afterCommit(
            () ->
                dispatch(
                    "project",
                    eventType,
                    () -> projectRecipients(projectId, groupId),
                    id(projectId),
                    id(groupId),
                    null,
                    null,
                    resourceId,
                    payload));
    }

    /**
     * 发布通知级事件：只发给接收用户自己的在线连接。
     */
    public void publishNotification(
        Long recipientUserId,
        Long notificationId,
        String eventType,
        Map<String, Object> payload) {
        if (recipientUserId == null) {
            return;
        }
        afterCommit(
            () ->
                dispatch(
                    "notification",
                    eventType,
                    () -> Set.of(recipientUserId),
                    null,
                    null,
                    null,
                    id(recipientUserId),
                    id(notificationId),
                    payload));
    }

    /**
     * 发布团队级事件：发给团队全部成员。
     */
    public void publishTeamEvent(
        Long teamId, String eventType, String resourceId, Map<String, Object> payload) {
        if (teamId == null) {
            return;
        }
        afterCommit(
            () ->
                dispatch(
                    "team",
                    eventType,
                    () -> teamRecipients(teamId),
                    null,
                    null,
                    id(teamId),
                    null,
                    resourceId,
                    payload));
    }

    /**
     * 群会话按群成员推送，默认群与不属于任何群的事件按项目成员推送。
     */
    private Set<Long> projectRecipients(Long projectId, Long groupId) {
        if (groupId != null) {
            ChatGroupEntity group = groupMapper.selectById(groupId);
            if (group != null && Boolean.FALSE.equals(group.getDefaultGroup())) {
                return Set.copyOf(groupMapper.selectUserIds(groupId));
            }
        }
        return projectMemberMapper.selectMembers(projectId).stream()
            .map(member -> member.getUserId())
            .collect(Collectors.toSet());
    }

    private Set<Long> teamRecipients(Long teamId) {
        return teamMemberMapper.selectByTeamId(teamId).stream()
            .map(member -> member.getUserId())
            .collect(Collectors.toSet());
    }

    /**
     * 业务事务提交后才推送，避免客户端收到帧后读到尚未提交的数据；不在事务中则立即推送。
     */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
    }

    /**
     * 计算可见用户并推送一帧；推送失败只记日志，不影响已经提交的业务写入。
     */
    private void dispatch(
        String scope,
        String eventType,
        Supplier<Set<Long>> recipients,
        String projectId,
        String groupId,
        String teamId,
        String recipientUserId,
        String resourceId,
        Map<String, Object> payload) {
        try {
            realtimeHub.broadcastToUsers(
                recipients.get(),
                RealtimeFrame.of(
                    eventType,
                    scope,
                    projectId,
                    groupId,
                    teamId,
                    recipientUserId,
                    resourceId,
                    payload));
        } catch (Exception e) {
            log.warn(
                "realtime fan-out failed, scope={}, eventType={}: {}",
                scope,
                eventType,
                e.getMessage());
        }
    }

    private String id(Long value) {
        return value == null ? null : value.toString();
    }
}
