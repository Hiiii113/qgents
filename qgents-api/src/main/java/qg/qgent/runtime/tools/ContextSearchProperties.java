package qg.qgent.runtime.tools;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 当前群聊天检索工具预算配置：持有每次 AgentRun 内 {@link ChatHistorySearchTool} 调用次数上限。
 * <p>
 * 由 {@code app.agent.context-search.max-per-run} 控制，默认 10；测试可直接 new 一个固定值实例。
 */
@Component
public class ContextSearchProperties {

    /**
     * 每次 AgentRun 检索工具调用次数上限。
     */
    private final int maxPerRun;

    public ContextSearchProperties(@Value("${app.agent.context-search.max-per-run:10}") int maxPerRun) {
        this.maxPerRun = Math.max(0, maxPerRun);
    }

    /**
     * 每次 AgentRun 检索工具调用次数上限。
     */
    public int getMaxPerRun() {
        return maxPerRun;
    }
}
