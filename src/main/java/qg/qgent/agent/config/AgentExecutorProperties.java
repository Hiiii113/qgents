package qg.qgent.agent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent 异步执行线程数和排队上限。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "qgents.executor.agent")
public class AgentExecutorProperties {
    private int coreSize = 2;
    private int maxSize = 4;
    private int queueCapacity = 100;

    public void validate() {
        if (coreSize < 1 || maxSize < coreSize || queueCapacity < 0) {
            throw new IllegalArgumentException("Agent 执行器要求 coreSize>=1、maxSize>=coreSize、queueCapacity>=0");
        }
    }
}
