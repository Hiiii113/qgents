package ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 轻量计费工具（评测题库基线）。
 */
public final class PriceCalculator {
    private PriceCalculator() {}

    /**
     * 计算折后价（单位：分）。
     * discountPercent 取值 0..100，例如 15 表示打 85 折（减免 15%）。
     * 要求四舍五入（HALF_UP）到最接近的分。
     */
    public static long applyDiscount(long cents, int discountPercent) {
        if (cents < 0) {
            throw new IllegalArgumentException("cents must be >= 0");
        }
        if (discountPercent < 0 || discountPercent > 100) {
            throw new IllegalArgumentException("discountPercent must be in [0, 100]");
        }
        // BUG: 整数除法直接截断，未按 HALF_UP 四舍五入
        return cents * (100 - discountPercent) / 100;
    }

    /**
     * 计算含税金额（分），按 HALF_UP 四舍五入。
     */
    public static long applyTax(long cents, BigDecimal taxRate) {
        if (cents < 0 || taxRate == null || taxRate.signum() < 0) {
            throw new IllegalArgumentException("invalid tax input");
        }
        return BigDecimal.valueOf(cents)
            .multiply(BigDecimal.ONE.add(taxRate))
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact();
    }
}
