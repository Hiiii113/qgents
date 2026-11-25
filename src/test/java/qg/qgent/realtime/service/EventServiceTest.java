package qg.qgent.realtime.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import qg.qgent.chat.entity.ChatGroupEntity;
import qg.qgent.chat.mapper.ChatGroupMapper;
import qg.qgent.chat.mapper.view.GroupMemberRow;
import qg.qgent.project.mapper.ProjectMemberMapper;
import qg.qgent.realtime.websocket.RealtimeFrame;
import qg.qgent.support.TestIds;
import qg.qgent.team.entity.TeamMemberEntity;
import qg.qgent.team.mapper.TeamMemberMapper;

/** WebSocket fan-out at the realtime domain boundary. */
class EventServiceTest {

    @Test
    void projectEventGoesToTheGroupMembersOnly() {
        Fixture fixture = new Fixture();
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        Long invitedUser = TestIds.next();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setDefaultGroup(false);
        when(fixture.groups.selectById(groupId)).thenReturn(group);
        when(fixture.groups.selectUserIds(groupId)).thenReturn(List.of(invitedUser));

        fixture.service()
                .publish(
                        projectId,
                        groupId,
                        "group.created",
                        groupId.toString(),
                        Map.of("name", "planning"));

        ArgumentCaptor<Set<Long>> recipients = recipientCaptor();
        ArgumentCaptor<RealtimeFrame> frame = ArgumentCaptor.forClass(RealtimeFrame.class);
        verify(fixture.realtime).broadcastToUsers(recipients.capture(), frame.capture());
        assertEquals(Set.of(invitedUser), recipients.getValue());
        assertEquals("project", frame.getValue().scope());
        assertEquals(projectId.toString(), frame.getValue().projectId());
        assertEquals(groupId.toString(), frame.getValue().groupId());
        assertEquals("group.created", frame.getValue().type());
        assertEquals(groupId.toString(), frame.getValue().resourceId());
        assertEquals("planning", frame.getValue().payload().get("name"));
        verify(fixture.projectMembers, never()).selectMembers(any());
    }

    @Test
    void projectEventOnTheDefaultGroupGoesToAllProjectMembers() {
        Fixture fixture = new Fixture();
        Long projectId = TestIds.next();
        Long groupId = TestIds.next();
        Long first = TestIds.next();
        Long second = TestIds.next();
        ChatGroupEntity group = new ChatGroupEntity();
        group.setId(groupId);
        group.setDefaultGroup(true);
        when(fixture.groups.selectById(groupId)).thenReturn(group);
        when(fixture.projectMembers.selectMembers(projectId))
                .thenReturn(List.of(projectMember(first), projectMember(second)));

        fixture.service()
                .publish(
                        projectId,
                        groupId,
                        "message.created",
                        "message-1",
                        Map.of("messageId", "message-1"));

        ArgumentCaptor<Set<Long>> recipients = recipientCaptor();
        verify(fixture.realtime).broadcastToUsers(recipients.capture(), any(RealtimeFrame.class));
        assertEquals(Set.of(first, second), recipients.getValue());
        verify(fixture.groups, never()).selectUserIds(any());
    }

    @Test
    void projectEventWithoutGroupGoesToAllProjectMembers() {
        Fixture fixture = new Fixture();
        Long projectId = TestIds.next();
        Long member = TestIds.next();
        when(fixture.projectMembers.selectMembers(projectId))
                .thenReturn(List.of(projectMember(member)));

        fixture.service()
                .publish(projectId, null, "project.updated", "project-1", Map.of("name", "A"));

        ArgumentCaptor<RealtimeFrame> frame = ArgumentCaptor.forClass(RealtimeFrame.class);
        verify(fixture.realtime)
                .broadcastToUsers(eq(Set.of(member)), frame.capture());
        assertEquals("project", frame.getValue().scope());
        assertEquals("project-1", frame.getValue().resourceId());
        assertNull(frame.getValue().groupId());
    }

    @Test
    void eventsWaitForBusinessCommitAndKeepInvocationOrder() {
        Fixture fixture = new Fixture();
        Long projectId = TestIds.next();
        fixture.startBusinessTransaction();
        try {
            EventService service = fixture.service();
            service.publish(projectId, null, "project.updated", "project-1", Map.of("name", "A"));
            service.publish(
                    projectId, null, "project.member.added", "member-1", Map.of("userId", "u1"));

            verifyNoInteractions(fixture.realtime);
            assertEquals(2, TransactionSynchronizationManager.getSynchronizations().size());

            fixture.commitBusinessTransaction();

            ArgumentCaptor<RealtimeFrame> frames = ArgumentCaptor.forClass(RealtimeFrame.class);
            verify(fixture.realtime, org.mockito.Mockito.times(2))
                    .broadcastToUsers(any(), frames.capture());
            assertEquals(
                    List.of("project.updated", "project.member.added"),
                    frames.getAllValues().stream().map(RealtimeFrame::type).toList());
        } finally {
            fixture.clearBusinessTransaction();
        }
    }

    @Test
    void notificationAndTeamFramesAreDeliveredAfterTheirTransactionCommits() {
        Fixture fixture = new Fixture();
        Long userId = TestIds.next();
        Long notificationId = TestIds.next();
        Long teamId = TestIds.next();
        Long teammate = TestIds.next();
        when(fixture.teamMembers.selectByTeamId(teamId))
                .thenReturn(List.of(teamMember(userId), teamMember(teammate)));
        fixture.startBusinessTransaction();
        try {
            EventService service = fixture.service();
            service.publishNotification(
                    userId, notificationId, "notification.created", Map.of("title", "Hello"));
            service.publishTeamEvent(
                    teamId, "activity.created", "resource-1", Map.of("ok", true));

            verifyNoInteractions(fixture.realtime);

            fixture.commitBusinessTransaction();

            ArgumentCaptor<Set<Long>> recipients = recipientCaptor();
            ArgumentCaptor<RealtimeFrame> frames = ArgumentCaptor.forClass(RealtimeFrame.class);
            verify(fixture.realtime, org.mockito.Mockito.times(2))
                    .broadcastToUsers(recipients.capture(), frames.capture());
            assertEquals(
                    List.of(Set.of(userId), Set.of(userId, teammate)), recipients.getAllValues());
            assertEquals(
                    List.of("notification", "team"),
                    frames.getAllValues().stream().map(RealtimeFrame::scope).toList());
            assertEquals(userId.toString(), frames.getAllValues().getFirst().recipientUserId());
            assertEquals(notificationId.toString(), frames.getAllValues().getFirst().resourceId());
            assertEquals(teamId.toString(), frames.getAllValues().get(1).teamId());
            assertEquals("resource-1", frames.getAllValues().get(1).resourceId());
        } finally {
            fixture.clearBusinessTransaction();
        }
    }

    @Test
    void nullRecipientsPublishNothing() {
        Fixture fixture = new Fixture();

        fixture.service().publishNotification(null, TestIds.next(), "notification.created", Map.of());
        fixture.service().publishTeamEvent(null, "activity.created", "resource-1", Map.of());

        verifyNoInteractions(fixture.realtime);
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
    }

    @Test
    void recipientLookupFailureDoesNotBreakTheCaller() {
        Fixture fixture = new Fixture();
        Long projectId = TestIds.next();
        when(fixture.projectMembers.selectMembers(projectId))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertDoesNotThrow(
                () ->
                        fixture.service()
                                .publish(
                                        projectId,
                                        null,
                                        "project.updated",
                                        "project-1",
                                        Map.of("name", "A")));

        verifyNoInteractions(fixture.realtime);
    }

    @Test
    void frameReachesTheRecipientsOpenWebSocketSession() throws Exception {
        ProjectMemberMapper projectMembers = mock(ProjectMemberMapper.class);
        Long projectId = TestIds.next();
        Long userId = TestIds.next();
        when(projectMembers.selectMembers(projectId))
                .thenReturn(List.of(projectMember(userId)));
        RealtimeHub hub = new RealtimeHub(new ObjectMapper().findAndRegisterModules());
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        hub.register(userId, session);
        EventService service =
                new EventService(
                        projectMembers,
                        mock(TeamMemberMapper.class),
                        mock(ChatGroupMapper.class),
                        hub);

        service.publish(projectId, null, "message.created", "message-1", Map.of("messageId", "m-1"));

        ArgumentCaptor<TextMessage> sent = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(sent.capture());
        JsonNode json = new ObjectMapper().readTree(sent.getValue().getPayload());
        assertEquals("message.created", json.get("type").asText());
        assertEquals("project", json.get("scope").asText());
        assertEquals(projectId.toString(), json.get("projectId").asText());
        assertEquals("message-1", json.get("resourceId").asText());
        assertEquals("m-1", json.get("payload").get("messageId").asText());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Set<Long>> recipientCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Set.class);
    }

    private static GroupMemberRow projectMember(Long userId) {
        GroupMemberRow member = new GroupMemberRow();
        member.setUserId(userId);
        return member;
    }

    private static TeamMemberEntity teamMember(Long userId) {
        TeamMemberEntity member = new TeamMemberEntity();
        member.setUserId(userId);
        return member;
    }

    private static final class Fixture {
        private final ProjectMemberMapper projectMembers = mock(ProjectMemberMapper.class);
        private final TeamMemberMapper teamMembers = mock(TeamMemberMapper.class);
        private final ChatGroupMapper groups = mock(ChatGroupMapper.class);
        private final RealtimeHub realtime = mock(RealtimeHub.class);

        private EventService service() {
            return new EventService(projectMembers, teamMembers, groups, realtime);
        }

        private void startBusinessTransaction() {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
        }

        private void commitBusinessTransaction() {
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            synchronizations.forEach(TransactionSynchronization::afterCommit);
            synchronizations.forEach(
                    sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        }

        private void clearBusinessTransaction() {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(sync ->
                                sync.afterCompletion(
                                        TransactionSynchronization.STATUS_ROLLED_BACK));
                TransactionSynchronizationManager.clearSynchronization();
            }
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
