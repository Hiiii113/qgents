package qg.qgent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import qg.qgent.config.ExecutorPerformanceProperties;
import qg.qgent.config.MdcTaskDecorator;

import java.util.concurrent.Executor;

/** 聊天请求提交后异步执行 Agent，subagent 在同一执行线程中调用。 */
@Configuration
@EnableConfigurationProperties(ExecutorPerformanceProperties.class)
public class AgentExecutionConfiguration {
    @Bean(name = "agentExecutor")
    public Executor agentExecutor(ExecutorPerformanceProperties properties) {
        ExecutorPerformanceProperties.Pool pool = properties.getAgent();
        pool.validate("agent");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(pool.getCoreSize());
        executor.setMaxPoolSize(pool.getMaxSize());
        executor.setQueueCapacity(pool.getQueueCapacity());
        executor.setThreadNamePrefix("agent-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }
}
