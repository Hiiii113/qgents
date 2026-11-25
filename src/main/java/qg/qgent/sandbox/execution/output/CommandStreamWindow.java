package qg.qgent.sandbox.execution.output;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次输出窗口读取：从字节游标开始的有界文本。
 *
 * @param text       本窗口内的文本
 * @param nextOffset 下一次读取应使用的字节游标
 * @param truncated  是否还有未读取的字节
 */
public record CommandStreamWindow(String text, long nextOffset, boolean truncated) {

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("text", text);
        value.put("nextOffset", nextOffset);
        value.put("truncated", truncated);
        return value;
    }
}
