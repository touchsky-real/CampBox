package com.inonvation.campbox.data

import com.squareup.moshi.Json

data class ApiEnvelope<T>(
    val code: Int? = null,
    val msg: String? = null,
    val message: String? = null,
    val data: T? = null,
) {
    fun requireData(): T {
        if (code != null && code != 0 && code != 200) {
            val reason = message ?: msg ?: "请求失败"
            if (TokenExpiredException.isTokenExpired(code, reason)) throw TokenExpiredException(reason)
            throw ApiException(code, reason)
        }
        if (data != null) return data
        val rawMsg = message ?: msg ?: "接口未返回 data"
        if (TokenExpiredException.isTokenExpired(code, rawMsg)) {
            throw TokenExpiredException(rawMsg)
        }
        throw ApiException(code, rawMsg)
    }
}

data class EmptyData(
    val ignored: String? = null,
)

data class LoginData(
    val token: String? = null,
)

data class DeviceItem(
    val goodsId: String? = null,
    val goodsName: String = "",
    val id: String? = null,
)

data class BalanceData(
    val tokenCoin: String? = null,
    @Json(name = "integral") val integral: String? = null,
    val integralAmount: String? = null,
) {
    val ticketText: String
        get() = tokenCoin?.toDoubleOrNull()?.let { "%.2f".format(it / 100.0) } ?: "-"

    val pointsText: String get() = integral ?: "-"
}

data class SkuData(val skuId: String? = null)

data class ImeiData(
    val imei: String? = null,
)

data class UnlockData(
    val msgId: String? = null,
    val orderNo: String? = null,
)

data class SyncData(
    val workStatus: Int? = null,
    val identify: String? = null,
    // 官方客户端以 status 3/5 判定使用结束，6 时退出状态页；amount 为本单金额（元）。
    val status: Int? = null,
    val amount: String? = null,
)

data class AfterPayCreatingData(
    val orderId: String? = null,
)

data class IntegralLimitRule(
    val integralTotal: Int? = null,
    val minUsageCount: Int? = null,
    val isUserIntegral: Boolean? = null,
    val userIntegral: Boolean? = null,
) {
    fun unusedReason(): String? = when {
        integralTotal != null && integralTotal <= 0 -> "暂无可用积分"
        (userIntegral ?: isUserIntegral) == false && minUsageCount != null && minUsageCount > 0 ->
            "平台要求积分满 $minUsageCount 才可使用" + (integralTotal?.let { "（当前 $it）" } ?: "")
        (userIntegral ?: isUserIntegral) == false -> "暂未达到平台积分使用条件"
        else -> null
    }
}

data class OrderDetailData(
    val tradeOrderItem: List<TradeOrderItem> = emptyList(),
    val promotionList: List<PromotionItem> = emptyList(),
    val id: String? = null,
    val orderNo: String? = null,
)

data class TradeOrderItem(
    val originPrice: String? = null,
)

data class PromotionItem(
    val promotionType: Int? = null,
    val discountAmount: String? = null,
)

data class UnlockResult(
    val orderNo: String,
    val orderId: String,
    val originPrice: String,
    val ticketCost: String,
    val integralCost: String,
    val otherPromotions: List<PromotionItem>,
    val completedAt: Long,
    // 非空表示出水成功但账单环节降级（费用以官方账单为准），UI 据此显示提示而非报错
    val note: String? = null,
    val usageConfirmed: Boolean = true,
    // 兼容旧记录字段；旧版换算的积分数不再展示，抵扣金额以 integralCost 为准。
    val pointsUsedPoints: String? = null,
    val pointsUnusedReason: String? = null,
)

// 服务端业务错误（保留 code 供诊断层使用，避免丢失后显示伪造的错误码）
class ApiException(
    val code: Int?,
    message: String,
) : Exception(message)



data class OrderHistoryItem(
    val orderNo: String,
    val orderId: String,
    val goodsName: String,
    val originPrice: String,
    val ticketCost: String,
    val integralCost: String,
    val otherPromotions: List<PromotionItem>,
    val completedAt: Long,
    val usageConfirmed: Boolean = true,
    val note: String? = null,
    val pointsUsedPoints: String? = null,
    val pointsUnusedReason: String? = null,
) {
    fun toUnlockResult(): UnlockResult = UnlockResult(
        orderNo = orderNo,
        orderId = orderId,
        originPrice = originPrice,
        ticketCost = ticketCost,
        integralCost = integralCost,
        otherPromotions = otherPromotions,
        completedAt = completedAt,
        usageConfirmed = usageConfirmed,
        note = note,
        pointsUsedPoints = pointsUsedPoints,
        pointsUnusedReason = pointsUnusedReason,
    )
}

class UnlockException(
    message: String,
    val diagnosis: DiagnosisResult,
    cause: Throwable? = null,
) : Exception(message, cause)

class TokenExpiredException(
    message: String = "登录已失效，请重新登录",
    cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        fun isTokenExpired(code: Int?, message: String?): Boolean {
            if (code == 401 || code == 403) return true
            val msg = message?.lowercase() ?: return false
            if (msg.contains("未登录") || msg.contains("未登陆")) return true
            if (msg.contains("请先登录") || msg.contains("请先登陆")) return true
            if (msg.contains("token") && (msg.contains("过期") || msg.contains("无效")
                        || msg.contains("expired") || msg.contains("invalid"))) return true
            return false
        }
    }
}
