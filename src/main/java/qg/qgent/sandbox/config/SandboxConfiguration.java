package qg.qgent.sandbox.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sandbox 的运行时配置和命令执行线程池。
 */
@Configuration
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxConfiguration {
    /**
     * 创建异步命令执行使用的固定大小线程池。
     */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService sandboxExecutionPool(SandboxProperties properties) {
        return Executors.newFixedThreadPool(
            properties.getExecutionThreads(),
            Thread.ofPlatform().name("sandbox-execution-", 0).factory());
    }

    /**
     * 沙箱清理专用执行器；调度线程只负责提交，等待命令退出时不占用调度池。
     */
    @Bean(name = "sandboxCleanupExecutor", destroyMethod = "shutdownNow")
    ExecutorService sandboxCleanupExecutor() {
        return Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("sandbox-cleanup-", 0).factory());
    }
}
