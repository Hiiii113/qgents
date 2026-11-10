package qg.qgent.memory.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 后台从群记忆发现项目记忆候选的周期和重试间隔。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.memory.discovery")
public class ProjectMemoryDiscoveryProperties {
    // 同一来源群两次发现之间的最短间隔（分钟），默认一天
    private int intervalMinutes = 1440;
    // 发现失败后再次尝试的最短间隔（分钟）
    private int retryDelayMinutes = 60;
    // 单次扫描最多处理的来源群数
    private int batchSize = 10;

    public void validate() {
        if (intervalMinutes < 1 || retryDelayMinutes < 1 || batchSize < 1) {
            throw new IllegalArgumentException("项目记忆发现配置要求所有间隔和批量大于 0");
        }
    }
}
