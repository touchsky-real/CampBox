package com.inonvation.campbox

import com.inonvation.campbox.data.OrderHistoryItem
import com.inonvation.campbox.ui.AppUiState
import com.inonvation.campbox.ui.UnlockFlowState
import com.inonvation.campbox.ui.withRepairedWaterOrders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WaterOrderStateTest {
    private val pending = OrderHistoryItem("mine", "", "饮水机", "-", "-", "-", emptyList(), 1L,
        usageConfirmed = false, note = "等待补查")
    private val repaired = pending.copy(orderId = "official-id", originPrice = "0.12",
        integralCost = "0.12", pointsUsedPoints = "12", usageConfirmed = true, note = null)

    @Test fun `补回账单后首页待确认与历史统计同时更新`() {
        val state = AppUiState(unlockFlowState = UnlockFlowState.Pending(pending.toUnlockResult()))
            .withRepairedWaterOrders(listOf(repaired))
        val success = assertIs<UnlockFlowState.Success>(state.unlockFlowState)
        assertEquals("0.12", success.result.originPrice)
        assertEquals("12", success.result.pointsUsedPoints)
        assertEquals(1, state.totalWaterCount)
    }

    @Test fun `补回其他账单不覆盖正在接水的卡片`() {
        val working = UnlockFlowState.Working("正在接水")
        val state = AppUiState(unlockFlowState = working).withRepairedWaterOrders(listOf(repaired))
        assertEquals(working, state.unlockFlowState)
    }

    @Test fun `关闭结果卡后后台补查不会重新弹出结果`() {
        val state = AppUiState().withRepairedWaterOrders(listOf(repaired))
        assertEquals(UnlockFlowState.Idle, state.unlockFlowState)
        assertEquals(listOf(repaired), state.orderHistory)
    }
}
