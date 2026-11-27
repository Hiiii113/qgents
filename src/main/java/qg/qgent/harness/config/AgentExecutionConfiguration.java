package qg.qgent.harness.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import qg.qgent.common.config.MdcTaskDecorator;

import java.util.concurrent.Executor;

/**
 * Agent 异步执行线程池。
 */
@Configuration
@EnableConfigurationProperties(AgentExecutorProperties.class)
public class AgentExecutionConfiguration {
    @Bean(name = "agentExecutor")
    public Executor agentExecutor(AgentExecutorProperties properties, MeterRegistry registry) {
        return executor(properties, "agent-", "agent", registry);
    }

    @Bean(name = "agentSubagentExecutor")
    public ThreadPoolTaskExecutor agentSubagentExecutor(
        AgentExecutorProperties properties, MeterRegistry registry) {
        return executor(properties, "agent-child-", "agent-child", registry);
    }

    private ThreadPoolTaskExecutor executor(
        AgentExecutorProperties properties,
        String prefix,
        String metricName,
        MeterRegistry registry) {
        properties.validate();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCoreSize());
        executor.setMaxPoolSize(properties.getMaxSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix(prefix);
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        ExecutorServiceMetrics.monitor(registry, executor.getThreadPoolExecutor(), metricName);
        return executor;
    }
}
