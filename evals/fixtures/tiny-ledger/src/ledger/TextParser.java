package ledger;

import java.util.ArrayList;
import java.util.List;

/**
 * 轻量 CSV 行解析器（评测题库基线）。
 */
public final class TextParser {
    private TextParser() {}

    /**
     * 解析单行 CSV：支持双引号包裹含逗号的字段、双引号内用 "" 转义引号，并保留空字段。
     */
    public static List<String> parseCsvLine(String line) {
        if (line == null || line.isEmpty()) {
            return List.of();
        }
        // BUG: 简单 split 无法处理引号中的逗号与末尾空字段
        String[] parts = line.split(",");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            result.add(part.trim());
        }
        return result;
    }
}
