package qg.qgent.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;

/** application.yaml 的调度池默认值：两个定时任务能同时开始，而不是串行等待。 */
class SchedulingConfigurationTest {
    private final ApplicationContextRunner context =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(SchedulingEnabledConfiguration.class)
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void configuredSchedulerRunsTwoJobsTogether() {
        context.run(app -> {
            TaskScheduler scheduler = app.getBean(TaskScheduler.class);
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            try {
                scheduler.schedule(() -> {
                    started.countDown();
                    awaitQuietly(release);
                }, Instant.now());
                scheduler.schedule(() -> {
                    started.countDown();
                    awaitQuietly(release);
                }, Instant.now());

                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
        });
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingEnabledConfiguration {
    }
}
