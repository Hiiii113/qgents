package qg.qgent.sandbox.service;

import java.util.regex.Pattern;

/**
 * 执行反馈进入模型、用户产物或领域结果前的统一内容脱敏器。
 */
public final class ExecutionContentSanitizer {

    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+");
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)\\b(token|password|secret|api[-_]?key|authorization)\\b\\s*[:=]\\s*([^\\s,;}]*)");
    private static final Pattern WINDOWS_HOST_PATH = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:[A-Za-z]:[\\\\/])[^\\s,;\"']+");
    private static final Pattern UNIX_HOST_PATH = Pattern.compile(
            "(?<![A-Za-z0-9_])/(?:home|Users|root|tmp|var|etc|opt|srv)(?:/[^\\s,;\"']*)?");
    private static final Pattern ENVIRONMENT_ASSIGNMENT = Pattern.compile(
            "\\b[A-Z][A-Z0-9_]{2,}\\s*=\\s*[^\\s,;}\\\"]+");
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s,;\"']+");
    private static final Pattern COMMAND_VALUE = Pattern.compile(
            "(?i)\\b(command|cmd|argv|command line)\\b\\s*[:=]\\s*[^\\r\\n]+");
    private static final Pattern RAW_OUTPUT = Pattern.compile(
            "(?is)\\b(stdout|stderr|stack[ -]?trace)\\b\\s*[:=]\\s*.*");
    private static final Pattern STACK_FRAME_LINE = Pattern.compile("(?m)^\\s*at\\s+[^\\r\\n]+$");
    private static final Pattern PROCESS_COMMAND = Pattern.compile(
            "(?i)\\b(?:process|tool)\\s+(?:failed|error|exited|execution failed).*?"
                    + "(?:running|with command)\\s+[^\\r\\n]+");

    private ExecutionContentSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        String sanitized = BEARER.matcher(value).replaceAll("Bearer [redacted]");
        sanitized = SENSITIVE_VALUE.matcher(sanitized).replaceAll("$1=[redacted]");
        sanitized = WINDOWS_HOST_PATH.matcher(sanitized).replaceAll("[host path omitted]");
        return UNIX_HOST_PATH.matcher(sanitized).replaceAll("[host path omitted]");
    }

    /**
     * 供仅限后端诊断记录使用的错误详情脱敏。除通用凭据和宿主路径外，也去除环境变量赋值及
     * 外部地址，避免故障信息成为凭据、部署拓扑或 Worker 端点的旁路泄露渠道。
     */
    public static String sanitizeDiagnosticDetail(String value) {
        String sanitized = sanitize(value);
        sanitized = ENVIRONMENT_ASSIGNMENT.matcher(sanitized).replaceAll("[environment omitted]");
        sanitized = URL.matcher(sanitized).replaceAll("[endpoint omitted]");
        sanitized = COMMAND_VALUE.matcher(sanitized).replaceAll("[command omitted]");
        sanitized = PROCESS_COMMAND.matcher(sanitized).replaceAll("[command omitted]");
        sanitized = STACK_FRAME_LINE.matcher(sanitized).replaceAll("[stack frame omitted]");
        return RAW_OUTPUT.matcher(sanitized).replaceAll("[raw output omitted]");
    }

}
