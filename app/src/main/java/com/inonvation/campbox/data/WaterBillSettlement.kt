package com.inonvation.campbox.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal fun OrderDetailData.hasBillAmount(): Boolean =
    tradeOrderItem.firstOrNull()?.originPrice?.toBigDecimalOrNull()?.signum()?.let { it >= 0 } == true

/** 已受理订单的账单补查失败不能抹掉使用状态；超时后仍保留已取得的订单 ID。 */
internal suspend fun settleWaterBill(
    createOrder: suspend () -> String?,
    queryDetail: suspend (String) -> OrderDetailData,
    onStep: suspend (String) -> Unit,
    createAttempts: Int,
    detailAttempts: Int,
    label: String = "",
    timeoutMillis: Long = 20_000,
    retryMillis: Long = 3_000,
): Pair<String, OrderDetailData?> {
    var orderId = ""
    var detail: OrderDetailData? = null
    try {
        withTimeoutOrNull(timeoutMillis) {
            var attempt = 0
            while (orderId.isBlank() && attempt < createAttempts) {
                attempt++
                onStep("${label}正在创建后付订单")
                try {
                    orderId = createOrder()?.takeIf { it.isNotBlank() }.orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TokenExpiredException) {
                    throw e
                } catch (_: Exception) {
                }
                if (orderId.isBlank() && attempt < createAttempts) {
                    onStep("${label}账单还未生成，稍后重试（$attempt/$createAttempts）")
                    delay(retryMillis)
                }
            }
            attempt = 0
            while (orderId.isNotBlank() && detail == null && attempt < detailAttempts) {
                attempt++
                onStep("${label}正在查询订单详情")
                try {
                    detail = queryDetail(orderId).takeIf { it.hasBillAmount() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TokenExpiredException) {
                    throw e
                } catch (_: Exception) {
                }
                if (detail == null && attempt < detailAttempts) delay(retryMillis)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: TokenExpiredException) {
        // 开水指令已受理，保留待确认结果，重新登录后可继续补查。
    }
    return orderId to detail
}
