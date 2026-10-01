package qg.qgent.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import qg.qgent.common.config.MdcTaskDecorator;

import java.util.concurrent.Executor;

/** 聊天请求提交后异步执行 Agent，subagent 在同一执行线程中调用。 */
@Configuration
@EnableConfigurationProperties(AgentExecutorProperties.class)
public class AgentExecutionConfiguration {
    @Bean(name = "agentExecutor")
    public Executor agentExecutor(AgentExecutorProperties properties) {
        properties.validate();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCoreSize());
        executor.setMaxPoolSize(properties.getMaxSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("agent-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }
}
