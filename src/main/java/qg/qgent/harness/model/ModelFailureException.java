package qg.qgent.harness.model;

import com.openai.errors.OpenAIServiceException;
import qg.qgent.harness.entity.FailureCode;

/**
 * 可分类的上游模型失败；非 429/5xx 的错误保持原样由调用方处理。
 */
public final class ModelFailureException extends RuntimeException {
    private final FailureCode code;

    public ModelFailureException(FailureCode code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    public FailureCode code() {
        return code;
    }

    /**
     * 把 SDK 的 HTTP 状态错误翻译成可重试分类，其他异常原样返回。
     */
    public static Throwable translate(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof OpenAIServiceException service) {
                int status = service.statusCode();
                if (status == 429) return new ModelFailureException(FailureCode.UPSTREAM_RATE_LIMITED, error);
                if (status >= 500 && status < 600)
                    return new ModelFailureException(FailureCode.UPSTREAM_SERVER_ERROR, error);
                return error;
            }
            current = current.getCause();
        }
        return error;
    }
}
