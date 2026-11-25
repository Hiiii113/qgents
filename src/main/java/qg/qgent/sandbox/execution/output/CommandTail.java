package qg.qgent.sandbox.execution.output;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 命令结束时返回给模型的输出尾部。
 *
 * @param text      有界尾部文本
 * @param truncated 是否丢弃了更早的输出
 */
public record CommandTail(String text, boolean truncated) {

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("text", text);
        value.put("truncated", truncated);
        return value;
    }
}
