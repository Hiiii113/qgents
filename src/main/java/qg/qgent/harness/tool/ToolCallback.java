package qg.qgent.harness.tool;

import java.util.function.Function;

/**
 * 注册后的单个工具：模型可见的元数据加按 JSON 参数调用的入口。
 * {@code inputSchema}、{@code call} 的入参与结果都是 JSON 文本。
 */
public record ToolCallback(
    String name,
    String description,
    String inputSchema,
    boolean parallel,
    Function<String, String> invocation) {

    public String call(String arguments) {
        return invocation.apply(arguments);
    }
}
