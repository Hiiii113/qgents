package qg.qgent.realtime.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import qg.qgent.common.config.MdcTaskDecorator;
import qg.qgent.realtime.transport.SseEmitterFactory;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** 配置 SSE 连接泵的有界线程池、emitter 工厂和时钟。 */
@Configuration(proxyBeanMethods = false)
public class SseTransportConfiguration {

    @Bean(name = "sseEventPumpExecutor", destroyMethod = "shutdown")
    public ExecutorService sseEventPumpExecutor() {
        MdcTaskDecorator mdcDecorator = new MdcTaskDecorator();
        AtomicInteger sequence = new AtomicInteger(1);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(mdcDecorator.decorate(runnable), "sse-event-pump-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(8, factory);
    }

    @Bean
    public SseEmitterFactory sseEmitterFactory() {
        return SseEmitter::new;
    }

    @Bean(name = "realtimeSseClock")
    public Clock realtimeSseClock() {
        return Clock.systemUTC();
    }
}
