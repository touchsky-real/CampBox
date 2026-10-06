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
    while (true) {
        val status = try {
            withTimeoutOrNull(5_000) { query() }
        } catch (_: IOException) { null }
        if (status == null) {
            failures++
            onStep("状态查询暂未返回，正在重试（第 $failures 次）")
        } else {
            failures = 0
            val identifier = status.identify?.takeIf { it.isNotBlank() }
            if (identifier != null && identifier != orderNo) {
                return@withTimeoutOrNull WaterUsageOutcome.Pending("设备返回了其他订单，当前订单请在胖乖生活中核对")
            }
            val belongsToOrder = everWorked || identifier == orderNo
            val ended = status.status == 3 || status.status == 5
            when {
                // 官方客户端以 status 3/5 判定使用结束；6 时退出状态页，仍需独立核对账单。
                ended && belongsToOrder ->
                    return@withTimeoutOrNull WaterUsageOutcome.Completed
                status.status == 6 -> return@withTimeoutOrNull WaterUsageOutcome.Pending(
                    "设备暂未提供本次使用状态，请确认已停止出水；账单将继续补查",
                )
                !ended && status.workStatus == 2 -> {
                    everWorked = true
                    onStep("设备工作中，正在等待完成")
                }
                status.status == null && status.workStatus != null && belongsToOrder ->
                    return@withTimeoutOrNull WaterUsageOutcome.Completed
                else -> {
                    onStep("开水指令已受理，等待设备更新状态")
                }
            }
        }
        delay(1_500)
    }
    @Suppress("UNREACHABLE_CODE")
    WaterUsageOutcome.Pending("设备状态待确认")
} ?: WaterUsageOutcome.Pending("暂未确认设备结束状态，正在补查本次账单；请确认饮水机已停止出水")
