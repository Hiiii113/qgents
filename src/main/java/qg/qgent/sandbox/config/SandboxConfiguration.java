package qg.qgent.sandbox.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sandbox 的时钟、运行时配置和命令执行线程池。 */
@Configuration
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxConfiguration {
    /** 提供统一 UTC 时钟，便于测试替换和时间语义一致。 */
    @Bean
    Clock sandboxClock() {
        return Clock.systemUTC();
    }

    /** 创建结构化工具异步执行使用的固定大小线程池。 */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService sandboxExecutionPool(SandboxProperties properties) {
        return Executors.newFixedThreadPool(properties.getExecutionThreads(),
                Thread.ofPlatform().name("sandbox-execution-", 0).factory());
    }
}
