package com.inonvation.campbox.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class WaterBillSettlementTest {
    private val ready = OrderDetailData(tradeOrderItem = listOf(TradeOrderItem("0.50")))

    @Test fun `查询详情超时仍保留已生成的订单号`() = runTest {
        val (id, detail) = settleWaterBill(
            createOrder = { "order-id" }, queryDetail = { delay(60_000); ready },
            onStep = {}, createAttempts = 1, detailAttempts = 1,
        )
        assertEquals("order-id", id)
        assertNull(detail)
        assertEquals(20_000L, testScheduler.currentTime)
    }

    @Test fun `登录失效保留待确认结果且不继续重试`() = runTest {
        var calls = 0
        val (id, detail) = settleWaterBill(
            createOrder = { "order-id" },
            queryDetail = { calls++; throw TokenExpiredException() },
            onStep = {}, createAttempts = 4, detailAttempts = 2,
        )
        assertEquals("order-id", id)
        assertNull(detail)
        assertEquals(1, calls)
    }

    @Test fun `建单阶段登录失效也不应将开水判为失败`() = runTest {
        val bill = settleWaterBill(
            createOrder = { throw TokenExpiredException() },
            queryDetail = { error("不应查询详情") },
            onStep = {}, createAttempts = 4, detailAttempts = 2,
        )
        assertEquals("", bill.first)
        assertNull(bill.second)
    }

    @Test fun `空账单会重试直到金额出现`() = runTest {
        var calls = 0
        val bill = settleWaterBill(
            createOrder = { "order-id" },
            queryDetail = { if (++calls == 1) OrderDetailData() else ready },
            onStep = {}, createAttempts = 1, detailAttempts = 2,
        )
        assertEquals(ready, bill.second)
        assertEquals(2, calls)
    }

    @Test fun `没有有效金额的响应不能确认为成功账单`() = runTest {
        for (amount in listOf(null, "", "-", "unknown", "-1")) {
            val bill = settleWaterBill(
                createOrder = { "order-id" },
                queryDetail = { OrderDetailData(tradeOrderItem = listOf(TradeOrderItem(amount))) },
                onStep = {}, createAttempts = 1, detailAttempts = 1,
            )
            assertNull(bill.second)
        }
    }

    @Test fun `零元账单是有效账单`() = runTest {
        val free = OrderDetailData(tradeOrderItem = listOf(TradeOrderItem("0.00")))
        val bill = settleWaterBill(
            createOrder = { "order-id" }, queryDetail = { free },
            onStep = {}, createAttempts = 1, detailAttempts = 1,
        )
        assertEquals(free, bill.second)
    }

    @Test fun `网络失败重试后可恢复且只对已建订单查详情`() = runTest {
        var calls = 0
        val bill = settleWaterBill(
            createOrder = { if (++calls == 1) throw IOException() else "order-id" },
            queryDetail = { id -> assertEquals("order-id", id); ready },
            onStep = {}, createAttempts = 4, detailAttempts = 2,
        )
        assertEquals(2, calls)
        assertEquals(ready, bill.second)
    }

    @Test fun `补查总超时覆盖建单等待`() = runTest {
        val bill = settleWaterBill(
            createOrder = { delay(30_000); "order-id" }, queryDetail = { ready },
            onStep = {}, createAttempts = 4, detailAttempts = 2,
        )
        assertEquals("", bill.first)
        assertNull(bill.second)
        assertEquals(20_000L, testScheduler.currentTime)
    }

    @Test fun `取消补查仍向上传递取消信号`() = runTest {
        assertFailsWith<CancellationException> {
            settleWaterBill(
                createOrder = { "order-id" }, queryDetail = { throw CancellationException() },
                onStep = {}, createAttempts = 4, detailAttempts = 2,
            )
        }
    }
}
