package ledger;

import java.util.List;

/**
 * 隐藏验收测试：覆盖引号内逗号、转义双引号与末尾空字段。
 */
public final class HiddenCsvParserTest {
    public static void main(String[] args) {
        assertEquals(
            List.of("a", "b,c", "d"),
            TextParser.parseCsvLine("a,\"b,c\",d"),
            "quoted comma");
        assertEquals(
            List.of("say \"hi\"", "ok"),
            TextParser.parseCsvLine("\"say \"\"hi\"\"\",ok"),
            "escaped quote");
        assertEquals(
            List.of("x", "", ""),
            TextParser.parseCsvLine("x,,"),
            "trailing empty fields");
        assertEquals(
            List.of(),
            TextParser.parseCsvLine(""),
            "empty line");
        System.out.println("HIDDEN_CSV_OK");
    }

    private static void assertEquals(List<String> expected, List<String> actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }
}
