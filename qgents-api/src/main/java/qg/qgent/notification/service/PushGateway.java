package qg.qgent.notification.service;

import qg.qgent.notification.entity.NotificationEntity;

/** 移动端推送提供方端口。 */
public interface PushGateway {
    PushSendResult send(String deviceToken, NotificationEntity notification);
}
