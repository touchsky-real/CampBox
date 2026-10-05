package com.inonvation.campbox.data

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal sealed class WaterUsageOutcome {
    data object Completed : WaterUsageOutcome()
    data class Pending(val reason: String) : WaterUsageOutcome()
}

/** 只查询已受理订单，不重发开水指令。超时/断网表示无法确认，不能证明出水失败或已关阀。 */
internal suspend fun monitorWaterUsage(
    orderNo: String,
    query: suspend () -> SyncData,
    onStep: suspend (String) -> Unit,
    timeoutMillis: Long = 165_000,
): WaterUsageOutcome = withTimeoutOrNull(timeoutMillis) {
    var everWorked = false
    var failures = 0
    var unknownStates = 0
    while (true) {
        val status = try {
            withTimeoutOrNull(5_000) { query() }
        } catch (_: IOException) { null }
        if (status == null) {
            failures++
            if (failures >= 3) return@withTimeoutOrNull WaterUsageOutcome.Pending(
                "网络暂时不可用，无法确认设备是否结束；若已接完水，请稍后在胖乖生活核对账单",
            )
            onStep("网络暂时中断，正在重新查询设备状态（$failures/3）")
        } else {
            failures = 0
            val identifier = status.identify?.takeIf { it.isNotBlank() }
            if (identifier != null && identifier != orderNo) {
                return@withTimeoutOrNull WaterUsageOutcome.Pending("设备返回了其他订单，当前订单请在胖乖生活中核对")
            }
            when {
                status.workStatus == 2 -> {
                    everWorked = true
                    unknownStates = 0
                    onStep("设备工作中，正在等待完成")
                }
                status.workStatus != null && (everWorked || identifier == orderNo) ->
                    return@withTimeoutOrNull WaterUsageOutcome.Completed
                else -> {
                    unknownStates++
                    if (unknownStates >= 6) return@withTimeoutOrNull WaterUsageOutcome.Pending(
                        "尚未收到本次订单的明确状态；若已接完水，请稍后在胖乖生活核对账单",
                    )
                    onStep("开水指令已受理，等待设备更新状态")
                }
            }
        }
        delay(1_000)
    }
    @Suppress("UNREACHABLE_CODE")
    WaterUsageOutcome.Pending("设备状态待确认")
} ?: WaterUsageOutcome.Pending("设备状态确认超时，请检查饮水机是否停止出水，并在胖乖生活核对订单")
