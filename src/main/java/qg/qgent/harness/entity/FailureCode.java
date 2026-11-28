package qg.qgent.harness.entity;

/**
 * agent_runs.failure_code 的取值；只区分失败来源，不改变 RunStatus。
 */
public enum FailureCode {
    /** 上游模型接口返回 HTTP 429。 */
    UPSTREAM_RATE_LIMITED,
    /** 上游模型接口返回 5xx。 */
    UPSTREAM_SERVER_ERROR,
    /** 单轮模型调用超过时限。 */
    UPSTREAM_TIMEOUT,
    /** Agent 循环或工具执行失败，与上游状态码无关。 */
    AGENT_FAILURE;

    /**
     * 只有上游失败可以重试；是否真正重试还取决于本轮是否已输出正文。
     */
    public boolean upstreamRetryable() {
        return switch (this) {
            case UPSTREAM_RATE_LIMITED, UPSTREAM_SERVER_ERROR, UPSTREAM_TIMEOUT -> true;
            case AGENT_FAILURE -> false;
        };
    }
}
