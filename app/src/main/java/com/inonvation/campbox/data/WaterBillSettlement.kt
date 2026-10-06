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
    queryExisting: suspend () -> OrderDetailData? = { null },
    knownOrderId: String = "",
    label: String = "",
    timeoutMillis: Long = 20_000,
    retryMillis: Long = 1_500,
    requestTimeoutMillis: Long = 5_000,
    stopOnTokenExpired: Boolean = false,
): Pair<String, OrderDetailData?> {
    var orderId = knownOrderId
    var detail: OrderDetailData? = null
    suspend fun findExisting(): Boolean {
        try {
            val existing = withTimeoutOrNull(requestTimeoutMillis) { queryExisting() }
            if (existing != null) {
                existing.id?.takeIf { it.isNotBlank() }?.let { orderId = it }
                if (existing.hasBillAmount()) {
                    detail = existing
                    return true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TokenExpiredException) {
            throw e
        } catch (_: Exception) {
        }
        return false
    }
    try {
        withTimeoutOrNull(timeoutMillis) {
            onStep("${label}正在按订单号查询账单")
            if (findExisting()) return@withTimeoutOrNull
            var attempt = 0
            while (orderId.isBlank() && attempt < createAttempts) {
                attempt++
                onStep("${label}正在创建后付订单")
                try {
                    orderId = withTimeoutOrNull(requestTimeoutMillis) { createOrder() }
                        ?.takeIf { it.isNotBlank() }.orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TokenExpiredException) {
                    throw e
                } catch (_: Exception) {
                }
                // 设备可能已自动结算；建单超时不能阻止读取已经生成的账单。
                if (orderId.isBlank() && findExisting()) return@withTimeoutOrNull
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
                    detail = withTimeoutOrNull(requestTimeoutMillis) { queryDetail(orderId) }
                        ?.takeIf { it.hasBillAmount() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TokenExpiredException) {
                    throw e
                } catch (_: Exception) {
                }
                if (detail == null && findExisting()) return@withTimeoutOrNull
                if (detail == null && attempt < detailAttempts) delay(retryMillis)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: TokenExpiredException) {
        if (stopOnTokenExpired) throw e
        // 开水指令已受理，保留待确认结果，重新登录后可继续补查。
    }
    return orderId to detail
}
