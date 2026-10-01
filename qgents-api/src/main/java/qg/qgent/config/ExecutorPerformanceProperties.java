package qg.qgent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 异步执行器参数。使用有界队列，避免高峰期无界堆积耗尽内存。
 */
@ConfigurationProperties(prefix = "qgents.executor")
public class ExecutorPerformanceProperties {
    private Pool agent = new Pool(2, 4, 100);
    public Pool getAgent() {
        return agent;
    }

    public void setAgent(Pool agent) {
        this.agent = agent;
    }

    public static class Pool {
        private int coreSize;
        private int maxSize;
        private int queueCapacity;

        public Pool() {
        }

        public Pool(int coreSize, int maxSize, int queueCapacity) {
            this.coreSize = coreSize;
            this.maxSize = maxSize;
            this.queueCapacity = queueCapacity;
        }

        public int getCoreSize() {
            return coreSize;
        }

        public void setCoreSize(int coreSize) {
            this.coreSize = coreSize;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public void validate(String name) {
            if (coreSize < 1 || maxSize < coreSize || queueCapacity < 0) {
                throw new IllegalArgumentException("非法执行器配置 " + name
                        + ": coreSize>=1、maxSize>=coreSize、queueCapacity>=0");
            }
        }
    }
}
