package qg.qgent.realtime.service;

import java.util.Map;

/** 发布各业务领域产生的持久化实时事件。 */
public interface RealtimeEventPublisher {
    void publish(Long projectId, Long groupId, String eventType, String resourceId, Map<String, Object> payload);

    void publishNotification(Long recipientUserId, Long notificationId, String eventType,
                             Map<String, Object> payload);

    void publishTeamEvent(Long teamId, String eventType, String resourceId, Map<String, Object> payload);
}
