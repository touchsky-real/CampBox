package com.inonvation.campbox.data

import kotlin.test.Test
import kotlin.test.assertEquals

class WaterHistoryTotalsTest {
    private fun order(no: String = "mine", price: String = "0.16", points: String = "-", ticket: String = "-",
        confirmed: Boolean = true) = OrderHistoryItem(no, "id", "饮水机", price, ticket, points,
        emptyList(), 1L, usageConfirmed = confirmed)

    @Test fun `积分和优惠抵扣后累计实际花费并包含小票支付`() {
        val history = listOf(order(ticket = "0.16"), order("other", points = "0.08"))
        val totals = WaterHistoryTotals.from(history)
        assertEquals("0.24", totals.spendingText)
        assertEquals(2, totals.confirmedCount)
    }

    @Test fun `待确认订单补回一次且重复补查不重复累计`() {
        val pending = order(price = "-", confirmed = false)
        val complete = order()
        val totals = WaterHistoryTotals.from(listOf(pending)).replace(pending, complete).replace(complete, complete)
        assertEquals("0.16", totals.spendingText)
        assertEquals(1, totals.confirmedCount)
    }

    @Test fun `设备已结束但没有账单的金额暂不累计`() {
        val pending = order(price = "-")
        val totals = WaterHistoryTotals.from(listOf(pending))
        assertEquals("0.00", totals.spendingText)
        val updated = totals.replace(pending, order())
        assertEquals("0.16", updated.spendingText)
        assertEquals(1, updated.confirmedCount)
    }

    @Test fun `列表超过五十条累计金额仍保留`() {
        var totals = WaterHistoryTotals()
        var retained = emptyList<OrderHistoryItem>()
        repeat(60) {
            val next = order(it.toString())
            totals = totals.replace(null, next)
            retained = (retained + next).takeLast(50)
        }
        assertEquals(50, retained.size)
        assertEquals("9.60", totals.spendingText)
        assertEquals(60, totals.confirmedCount)
        val last = retained.last()
        totals = totals.replace(last, last.copy(integralCost = "0.08"))
        assertEquals("9.52", totals.spendingText)
    }

    @Test fun `积分完全抵扣时累计为零且不能出现负费用`() {
        assertEquals("0.00", WaterHistoryTotals.from(listOf(order(points = "0.16"))).spendingText)
        assertEquals("0.00", WaterHistoryTotals.from(listOf(order(points = "0.20"))).spendingText)
    }
}
