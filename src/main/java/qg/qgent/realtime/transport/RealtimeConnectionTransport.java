package qg.qgent.realtime.transport;

import qg.qgent.realtime.websocket.RealtimeFrame;

import java.util.Set;

/** 将已确定接收范围的实时帧发送到目标用户的在线连接。 */
public interface RealtimeConnectionTransport {
    void broadcastToUsers(Set<Long> userIds, RealtimeFrame frame);
}
