package ledger;

import java.math.BigDecimal;

/**
 * 仓库内可见的冒烟测试（编译运行不到 1 秒）。
 */
public final class SmokeTest {
    public static void main(String[] args) {
        if (PriceCalculator.applyDiscount(1000, 10) != 900) {
            throw new AssertionError("expected 900");
        }
        if (PriceCalculator.applyTax(100, new BigDecimal("0.08")) != 108) {
            throw new AssertionError("expected 108");
        }
        if (!TextParser.parseCsvLine("a,b,c").equals(java.util.List.of("a", "b", "c"))) {
            throw new AssertionError("expected [a, b, c]");
        }
        System.out.println("SMOKE_OK");
    }
}
