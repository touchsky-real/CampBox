package com.inonvation.campbox.data

import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class WaterUsageMonitorTest {
    @Test fun `轮询断网后恢复 不应报告接水失败`() = runTest {
        var calls = 0
        val result = monitorWaterUsage("mine", query = {
            when (++calls) {
                1 -> SyncData(2, "mine")
                2 -> throw UnknownHostException("Unable to resolve host")
                else -> SyncData(0, "mine")
            }
        }, onStep = {})
        assertEquals(WaterUsageOutcome.Completed, result)
        assertEquals(3, calls)
    }

    @Test fun `快速接完水没有采到工作中 仍可凭同一订单确认结束`() = runTest {
        assertEquals(WaterUsageOutcome.Completed,
            monitorWaterUsage("mine", query = { SyncData(0, "mine") }, onStep = {}))
    }

    @Test fun `持续 DNS 错误只查三次并返回待确认`() = runTest {
        var calls = 0
        assertIs<WaterUsageOutcome.Pending>(monitorWaterUsage("mine", query = {
            calls++
            throw UnknownHostException("Unable to resolve host")
        }, onStep = {}))
        assertEquals(3, calls)
    }

    @Test fun `其他订单不可用作本次订单的结算依据`() = runTest {
        assertIs<WaterUsageOutcome.Pending>(monitorWaterUsage("mine",
            query = { SyncData(0, "someone-else") }, onStep = {}))
    }

    @Test fun `空状态不是使用结束`() = runTest {
        assertIs<WaterUsageOutcome.Pending>(monitorWaterUsage("mine",
            query = { SyncData(null, "mine") }, onStep = {}))
    }

    @Test fun `没有订单号且从未见过出水 不伪造结束`() = runTest {
        assertIs<WaterUsageOutcome.Pending>(monitorWaterUsage("mine",
            query = { SyncData(0, null) }, onStep = {}))
    }

    @Test fun `工作中的设备达到总时限 不能声称已自动关阀`() = runTest {
        assertIs<WaterUsageOutcome.Pending>(monitorWaterUsage("mine",
            query = { SyncData(2, "mine") }, onStep = {}, timeoutMillis = 3_000))
    }

    @Test fun `单次查询挂起会超时而不是卡住整个流程`() = runTest {
        var calls = 0
        assertIs<WaterUsageOutcome.Pending>(monitorWaterUsage("mine", query = {
            calls++
            delay(60_000)
            SyncData(0, "mine")
        }, onStep = {}))
        assertEquals(3, calls)
    }

    @Test fun `取消任务必须透传`() = runTest {
        assertFailsWith<CancellationException> {
            monitorWaterUsage("mine", query = { throw CancellationException("cancelled") }, onStep = {})
        }
    }

    @Test fun `官方状态三或五视为使用结束`() = runTest {
        assertEquals(WaterUsageOutcome.Completed,
            monitorWaterUsage("mine", query = { SyncData(workStatus = 0, identify = "mine", status = 3) }, onStep = {}))
        assertEquals(WaterUsageOutcome.Completed,
            monitorWaterUsage("mine", query = { SyncData(workStatus = 0, identify = "mine", status = 5) }, onStep = {}))
    }

    @Test fun `设备异常状态不伪造结束`() = runTest {
        assertIs<WaterUsageOutcome.Pending>(monitorWaterUsage("mine",
            query = { SyncData(workStatus = 0, identify = "mine", status = 6) }, onStep = {}))
    }

    @Test fun `结束状态缺少订单号且未见出水不能认作本单完成`() = runTest {
        for (status in listOf(3, 5)) {
            assertIs<WaterUsageOutcome.Pending>(monitorWaterUsage("mine",
                query = { SyncData(workStatus = 0, status = status) }, onStep = {}))
        }
    }

    @Test fun `其他订单的官方结束状态也不能结算本单`() = runTest {
        assertIs<WaterUsageOutcome.Pending>(monitorWaterUsage("mine",
            query = { SyncData(workStatus = 0, identify = "other", status = 3) }, onStep = {}))
    }
}
