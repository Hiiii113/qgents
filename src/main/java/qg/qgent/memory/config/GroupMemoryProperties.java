package qg.qgent.memory.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 群记忆提炼的触发、重试和批量阈值。
 * <p>
 * 触发由三档规则组成，满足任一即提炼：常规触发（新消息数 + 静默时长）、
 * 长闲置下限兜底（少量新消息但久未提炼）、活跃上限兜底（消息很多时不等静默）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.memory.group")
public class GroupMemoryProperties {
    // 常规触发：自上次提炼以来至少新增的消息条数
    private int minMessages = 20;
    // 常规触发：群内最后一次消息后需要静默的分钟数
    private int idleMinutes = 15;
    // 长闲置下限：只剩少量新消息时，静默超过该分钟数也要提炼
    private int staleMinMessages = 3;
    // 长闲置下限：触发长闲置兜底所需的静默分钟数，默认 4 小时
    private int staleIdleMinutes = 240;
    // 活跃上限兜底：新消息达到该条数时不再等静默，直接提炼
    private int forceMessages = 80;
    // 失败后再次尝试的最短间隔（分钟）
    private int retryDelayMinutes = 5;
    // 连续失败次数上限；观察到新消息后重新计数
    private int maxAttempts = 3;
    // 单次提炼最多送入的消息条数
    private int maxMessagesPerRun = 200;
    // 单次扫描每页读取的群数
    private int scanPageSize = 20;

    public void validate() {
        if (minMessages < 1
            || idleMinutes < 1
            || staleMinMessages < 1
            || staleIdleMinutes < 1
            || forceMessages < 1
            || retryDelayMinutes < 1
            || maxAttempts < 1
            || maxMessagesPerRun < 1
            || scanPageSize < 1) {
            throw new IllegalArgumentException("群记忆提炼配置要求所有阈值大于 0");
        }
        if (staleMinMessages > minMessages
            || staleIdleMinutes < idleMinutes
            || forceMessages < minMessages) {
            throw new IllegalArgumentException(
                "群记忆提炼三档阈值必须满足：长闲置消息下限不高于常规门槛，长闲置时长不低于常规静默时长，活跃上限不低于常规门槛");
        }
    }
}
