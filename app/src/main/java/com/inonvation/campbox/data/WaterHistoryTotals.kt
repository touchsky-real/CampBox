package com.inonvation.campbox.data

import java.math.BigDecimal
import java.math.RoundingMode

/** 从本机现存订单迁移，之后按订单变更增减；列表裁剪到 50 条不会丢失累计值。 */
data class WaterHistoryTotals(
    val confirmedCount: Int = 0,
    val spending: BigDecimal = BigDecimal.ZERO,
) {
    val spendingText: String get() = spending.setScale(2, RoundingMode.HALF_UP).toPlainString()

    fun replace(previous: OrderHistoryItem?, next: OrderHistoryItem): WaterHistoryTotals = copy(
        confirmedCount = confirmedCount - (if (previous?.usageConfirmed == true) 1 else 0) +
            (if (next.usageConfirmed) 1 else 0),
        spending = spending.subtract(previous.expense()).add(next.expense()),
    )

    companion object {
        fun from(history: List<OrderHistoryItem>): WaterHistoryTotals =
            history.fold(WaterHistoryTotals()) { totals, item -> totals.replace(null, item) }
    }
}

private fun OrderHistoryItem?.expense(): BigDecimal {
    if (this == null || !usageConfirmed) return BigDecimal.ZERO
    // 小票是支付方式，花掉余额同样属于实际花费，不能当优惠再减掉。
    return calculateWaterSpending(toUnlockResult()).toBigDecimalOrNull() ?: BigDecimal.ZERO
}
