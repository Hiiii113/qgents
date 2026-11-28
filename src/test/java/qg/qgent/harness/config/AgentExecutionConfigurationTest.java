package qg.qgent.harness.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** application.yaml 的 Agent 执行器默认值：4 个同时提交的任务能一起启动，而不是先排队。 */
class AgentExecutionConfigurationTest {

    @Test
    void fourTasksStartTogetherOnBothPoolsWithConfiguredDefaults() throws Exception {
        AgentExecutorProperties properties = yamlDefaults();
        AgentExecutionConfiguration configuration = new AgentExecutionConfiguration();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolTaskExecutor agent =
                (ThreadPoolTaskExecutor) configuration.agentExecutor(properties, registry);
        ThreadPoolTaskExecutor subagent = configuration.agentSubagentExecutor(properties, registry);
        try {
            assertFourTasksStartTogether(agent);
            assertFourTasksStartTogether(subagent);
            assertThat(registry.find("executor.active").tag("name", "agent").gauge()).isNotNull();
            assertThat(registry.find("executor.active").tag("name", "agent-child").gauge())
                    .isNotNull();
        } finally {
            agent.shutdown();
            subagent.shutdown();
        }
    }

    private AgentExecutorProperties yamlDefaults() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader()
                        .load("application", new ClassPathResource("application.yaml"));
        return new Binder(
                        ConfigurationPropertySources.from(sources),
                        new PropertySourcesPlaceholdersResolver(sources))
                .bind("qgents.executor.agent", Bindable.of(AgentExecutorProperties.class))
                .get();
    }

    private void assertFourTasksStartTogether(ThreadPoolTaskExecutor executor)
            throws InterruptedException {
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        for (int task = 0; task < 4; task++) {
            executor.execute(
                    () -> {
                        started.countDown();
                        awaitQuietly(release);
                    });
        }
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            // 无论断言成败都放行，正在占槽的任务才能退出。
            release.countDown();
        }
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
