package qg.qgent.realtime.transport;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongFunction;

/** 统一管理 SSE 连接上限、事件轮询、发送、心跳和释放。 */
public interface SseConnectionTransport {
    SseEmitter open(String scope, UUID ownerId, long cursor,
                    LongFunction<List<SseEvent>> loadEvents, boolean measurePoll);

    /** 由具体事件范围查询并完成可见性判断后的 SSE 事件。 */
    record SseEvent(long sequenceNo, String type, Map<String, Object> payload, boolean visible) {
    }
}
