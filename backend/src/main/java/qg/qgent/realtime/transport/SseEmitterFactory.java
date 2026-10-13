package qg.qgent.realtime.transport;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 创建一个 SSE 连接对应的 Spring emitter。 */
@FunctionalInterface
public interface SseEmitterFactory {
    SseEmitter create(long timeoutMillis);
}
