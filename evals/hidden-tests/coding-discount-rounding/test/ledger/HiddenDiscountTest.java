package ledger;

import java.math.BigDecimal;

/**
 * 隐藏验收测试：覆盖四舍五入边界、大额防溢出与回归检查。
 */
public final class HiddenDiscountTest {
    public static void main(String[] args) {
        assertEquals(900L, PriceCalculator.applyDiscount(1000, 10), "1000 * 0.9");
        // 333 * 0.85 = 283.05 -> 283; 333 * 0.75 = 249.75 -> 250 (整数除法会错算成 249)
        assertEquals(250L, PriceCalculator.applyDiscount(333, 25), "333 * 0.75 HALF_UP");
        // 199 * 0.85 = 169.15 -> 169; 199 * 0.65 = 129.35 -> 129; 101 * 0.85 = 85.85 -> 86
        assertEquals(86L, PriceCalculator.applyDiscount(101, 15), "101 * 0.85 HALF_UP");
        // 精确 .5 向上进位：1 * 0.50 = 0.5 -> 1
        assertEquals(1L, PriceCalculator.applyDiscount(1, 50), "1 * 0.5 HALF_UP");
        assertEquals(0L, PriceCalculator.applyDiscount(100, 100), "100% off");
        assertEquals(100L, PriceCalculator.applyDiscount(100, 0), "0% off");
        // 回归：applyTax 不能被破坏
        assertEquals(108L, PriceCalculator.applyTax(100, new BigDecimal("0.08")), "tax regression");
        System.out.println("HIDDEN_DISCOUNT_OK");
    }

    private static void assertEquals(long expected, long actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }
}
