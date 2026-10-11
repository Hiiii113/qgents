package qg.qgent.realtime.service;

import java.util.Map;
import java.util.UUID;

/** 发布各业务领域产生的持久化实时事件。 */
public interface RealtimeEventPublisher {
    void publish(UUID projectId, UUID groupId, String eventType, String resourceId, Map<String, Object> payload);

    void publishNotification(UUID recipientUserId, UUID notificationId, String eventType,
                             Map<String, Object> payload);

    void publishTeamEvent(UUID teamId, String eventType, String resourceId, Map<String, Object> payload);
}
