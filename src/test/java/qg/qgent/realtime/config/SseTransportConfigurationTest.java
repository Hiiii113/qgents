package qg.qgent.realtime.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** SSE 连接泵执行器的并发容量。 */
class SseTransportConfigurationTest {

    @Test
    void runsMoreConcurrentConnectionsThanProcessorCount() throws Exception {
        ExecutorService executor = new SseTransportConfiguration().sseEventPumpExecutor();
        CountDownLatch started = new CountDownLatch(9);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 9; i++) {
                executor.execute(() -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            assertTrue(started.await(5, TimeUnit.SECONDS), "9 条连接应同时进入轮询");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
