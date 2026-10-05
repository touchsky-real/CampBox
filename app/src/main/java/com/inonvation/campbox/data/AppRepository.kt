package com.inonvation.campbox.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class NotLoggedInException(message: String = "请先登录") : Exception(message)

class AppRepository(
    private val tokenStore: TokenStore,
    private val orderHistoryStore: OrderHistoryStore,
    private val locationProvider: WaterLocationProvider? = null,
    deviceIdProvider: () -> String? = { null },
) {
    private val api: DeviceApi

    init {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = HttpClientProvider.client.newBuilder()
            .addInterceptor(HeaderInterceptor({ tokenStore.readToken() }, deviceIdProvider))
            .addInterceptor(logging)
            .retryOnConnectionFailure(false)
            .build()
        api = Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(MoshiProvider.instance))
            .build()
            .create(DeviceApi::class.java)
    }

    fun localToken(): String? = tokenStore.readToken()
    fun saveToken(token: String) = tokenStore.saveToken(token)
    fun clearToken() = tokenStore.clear()
    fun readPhone(): String? = tokenStore.readPhone()
    fun savePhone(phone: String) = tokenStore.savePhone(phone)
    fun clearOrderHistory() = orderHistoryStore.clearAll()

    fun orderHistory(): List<OrderHistoryItem> = orderHistoryStore.list()

    suspend fun sendCode(phone: String) {
        api.sendCode(phone = phone).throwIfFailed()
    }

    suspend fun login(phone: String, code: String): String {
        val token = api.login(phone = phone, verify = code).requireData().token
            ?: error("登录成功但未返回 token")
        tokenStore.saveToken(token)
        return token
    }

    suspend fun queryBalance(): BalanceData {
        val token = requireToken()
        val resp = api.queryBalance(token)
        resp.throwIfFailed()
        return resp.requireData()
    }

    suspend fun latestDevices(): List<DeviceItem> {
        val token = requireToken()
        val resp = api.getLatestUsed(token = token)
        resp.throwIfFailed()
        return resp.data ?: emptyList()
    }

    /** 扫码只识别设备；确认设备后仍由现有开水流程处理位置、积分和订单。 */
    suspend fun resolveWaterCode(raw: String): DeviceItem {
        val code = parseWaterDeviceCode(raw) ?: error("未识别到胖乖设备码，请扫描饮水机上的二维码")
        val token = requireToken()
        val scan = try {
            api.scanDevice(mapOf(code.type to code.value), token).requireData()
        } catch (e: ApiException) {
            throw WaterScanException("识别二维码（goods/scan/v2）", e.code, e.message ?: "识别失败")
        }
        val id = scan.id?.takeIf { it.isNotBlank() } ?: error("平台未返回设备编号，请重新扫描")
        if (!scan.categoryCode.isNullOrBlank() && scan.categoryCode != "04") {
            error("这不是胖乖饮水机二维码，请扫描饮水机机身上的设备码")
        }
        val details = try {
            api.waterDeviceDetails(id, token).requireData()
        } catch (e: ApiException) {
            throw WaterScanException("获取设备详情（goods/normal/details）", e.code, e.message ?: "详情查询失败")
        }
        return scannedWaterDevice(scan, details)
    }

    suspend fun unlockDevice(
        device: DeviceItem,
        usePoints: Boolean = true,
        onStarted: suspend () -> Unit = {},
        onStep: suspend (String) -> Unit,
    ): UnlockResult {
        val token = requireToken()
        val goodsId = device.goodsId ?: device.id ?: error("设备缺少 goodsId")

        var currentStep = "准备解锁"
        var pointsEnabled = usePoints
        try {
            onStep("正在获取 SKU")
            currentStep = "获取 SKU"
            val skuId = api.goodsid2sku(goodsId = goodsId, token = token).requireData().firstOrNull()?.skuId
                ?: error("未获取到 skuId")

            onStep("正在检测设备状态")
            currentStep = "设备预检"
            // 预检失败不阻断流程
            try {
                api.syncWater(skuId = skuId, token = token).throwIfFailed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TokenExpiredException) {
                throw e
            } catch (_: Exception) {
                // 只忽略预检失败，取消和登录失效必须继续上抛。
            }

            onStep("正在获取 IMEI")
            currentStep = "获取 IMEI"
            val imei = api.getImei(goodsId = goodsId, token = token).requireData().imei
                ?: error("未获取到 imei")

            onStep("正在开通后付")
            currentStep = "开通后付"
            api.addUserAfterPayChannel(token = token).throwIfFailed()

            onStep("正在检查位置风控")
            currentStep = "位置风控检查"
            api.isCheckLocation(imei = imei, token = token).throwIfFailed()

            // 官方 App 只在勾选积分时查风控，被拦截也只是取消勾选；对齐为降级继续，不阻断开水
            if (pointsEnabled) {
                onStep("正在检查积分")
                currentStep = "积分风控检查"
                try {
                    api.useIntergral(token).throwIfFailed()
                } catch (e: Exception) {
                    if (!e.isPointsRiskError()) throw e
                    pointsEnabled = false
                    onStep("积分暂不可用（未实名认证），本次不使用积分")
                }
            }

            onStep("正在启动解锁")
            currentStep = "获取定位"
            val location = locationProvider?.currentLocation()
                ?: throw LocationUnavailableException("当前设备不支持定位")
            val headers = mapOf(
                "imei" to imei,
                "categoryCode" to WATER_CATEGORY_CODE,
                "lat" to location.latitude.toString(),
                "lng" to location.longitude.toString(),
            )

            currentStep = "启动解锁"
            val unlock = try {
                api.unlockWater(headers = headers, skuId = skuId, promotions = promotions(pointsEnabled), token = token).requireData()
            } catch (e: Exception) {
                // 服务端在解锁环节才拦截实名/积分风控时，对齐官方关闭积分开关的行为重试一次
                if (pointsEnabled && e.isPointsRiskError()) {
                    api.unlockWater(headers = headers, skuId = skuId, promotions = promotions(false), token = token).requireData()
                } else {
                    throw e
                }
            }

        val orderNo = unlock.orderNo?.takeIf { it.isNotBlank() } ?: error("未获取到订单号")
        // 接到订单号立即落盘，即使 App 被关闭，也保留一条明确标记为待确认的记录。
        savePendingWater(device, orderNo, "开水指令已受理，使用与账单状态待确认")
        onStarted()
        onStep("开水指令已受理，正在确认设备状态")
        currentStep = "设备状态轮询"
        val usage = try {
            monitorWaterUsage(orderNo, query = {
                api.syncWater(skuId = skuId, token = token).requireData()
            }, onStep = onStep)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WaterUsageOutcome.Pending(
                if (e is TokenExpiredException) "登录已失效，请重新登录后在胖乖生活核对本次订单"
                else "暂时无法确认设备状态，请在胖乖生活核对本次订单",
            )
        }
        if (usage is WaterUsageOutcome.Pending) {
            return savePendingWater(device, orderNo, usage.reason)
        }
        // 始终结算自己的订单，不能用设备最后一次返回的其他订单号替换。
        val finalOrderNo = orderNo

        // 水已出完，账单环节失败不应判为开水失败——降级为待确认订单，避免吓人的「未知错误」
        var orderId = ""
        val detail = try {
            withTimeoutOrNull(20_000) {
                onStep("正在创建后付订单")
                currentStep = "创建后付订单"
                orderId = api.createAfterPay(orderNo = finalOrderNo, token = token).requireData().orderId
                    ?: error("未获取到 orderId")
                onStep("正在查询订单详情")
                currentStep = "查询订单详情"
                api.orderDetail(orderId = orderId, token = token).requireData()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (detail == null) {
            val result = UnlockResult(
                orderNo = finalOrderNo, orderId = orderId,
                originPrice = "-", ticketCost = "-", integralCost = "-",
                otherPromotions = emptyList(), completedAt = System.currentTimeMillis(),
                note = "设备已结束使用，账单暂未确认，实际费用以胖乖生活账单为准",
            )
            saveWaterResult(device, result)
            return result
        }
        val ticketCost = detail.promotionList.firstOrNull { it.promotionType == 4 }?.discountAmount ?: "-"
        val integralCost = detail.promotionList.firstOrNull { it.promotionType == 8 }?.discountAmount ?: "-"
        val otherPromotions = detail.promotionList
            .filter { it.promotionType != 4 && it.promotionType != 8 }
            .map {
                PromotionItem(
                    promotionType = it.promotionType,
                    discountAmount = it.discountAmount,
                )
            }

        val result = UnlockResult(
            orderNo = finalOrderNo,
            orderId = orderId,
            originPrice = detail.tradeOrderItem.firstOrNull()?.originPrice ?: "-",
            ticketCost = ticketCost,
            integralCost = integralCost,
            otherPromotions = otherPromotions,
            completedAt = System.currentTimeMillis(),
        )
        saveWaterResult(device, result)
        return result
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: TokenExpiredException) {
            throw e
        } catch (e: NotLoggedInException) {
            throw e
        } catch (e: UnlockException) {
            throw e
        } catch (e: Exception) {
            throwDiagnosed(e, currentStep)
        }
    }



    private fun savePendingWater(device: DeviceItem, orderNo: String, reason: String): UnlockResult {
        val result = UnlockResult(
            orderNo = orderNo, orderId = "", originPrice = "-", ticketCost = "-", integralCost = "-",
            otherPromotions = emptyList(), completedAt = System.currentTimeMillis(),
            note = reason, usageConfirmed = false,
        )
        saveWaterResult(device, result)
        return result
    }

    private fun saveWaterResult(device: DeviceItem, result: UnlockResult) {
        orderHistoryStore.add(OrderHistoryItem(
            orderNo = result.orderNo, orderId = result.orderId,
            goodsName = device.goodsName.ifBlank { "未命名设备" },
            originPrice = result.originPrice, ticketCost = result.ticketCost,
            integralCost = result.integralCost, otherPromotions = result.otherPromotions,
            completedAt = result.completedAt, usageConfirmed = result.usageConfirmed, note = result.note,
        ))
    }

    // wrap 抛出的 IllegalStateException 丢失步骤上下文，未包装异常时用 currentStep 兜底
    private fun throwDiagnosed(original: Throwable, step: String): Nothing {
        if (original is LocationUnavailableException) {
            val diagnosis = DiagnosisResult(
                primaryReason = original.message ?: "定位不可用",
                rawError = original.message ?: "",
                step = step,
                suggestions = listOf("检查手机定位服务是否开启", "确认已授予本应用定位权限"),
            )
            throw UnlockException(diagnosis.primaryReason, diagnosis, original)
        }
        val code = (original as? ApiException)?.code
            ?: if (original.message?.matches(Regex("HTTP \\d+.*")) == true) {
                original.message?.substringAfter("HTTP ")?.substringBefore(":")?.trim()?.toIntOrNull()
            } else null
        val diagnosis = DeviceErrorDiagnosis.diagnose(code, original.message, step)
        throw UnlockException(diagnosis.primaryReason, diagnosis, original)
    }

    // 服务端以消息文本区分风控拦截（如"未实名认证"、"积分风控拦截"），无专用错误码
    private fun Throwable.isPointsRiskError(): Boolean {
        if (this is TokenExpiredException || this is NotLoggedInException) return false
        if (this is LocationUnavailableException) return false
        val msg = message ?: return false
        return msg.contains("实名") || msg.contains("认证") || msg.contains("风控") ||
            msg.contains("积分") && (msg.contains("拦截") || msg.contains("风险"))
    }

    private fun requireToken(): String = tokenStore.readToken()?.takeIf { it.isNotBlank() }
        ?: throw NotLoggedInException()

    private fun ApiEnvelope<*>.throwIfFailed() {
        if (code != null && code != 0 && code != 200) {
            val errorMsg = message ?: msg ?: "请求失败"
            if (TokenExpiredException.isTokenExpired(code, errorMsg)) {
                throw TokenExpiredException(errorMsg)
            }
            throw ApiException(code, errorMsg)
        }
    }

    suspend fun validateToken(token: String = requireToken()) {
        val resp = api.queryBalance(token)
        resp.requireData()
    }

    private companion object {
        const val WATER_CATEGORY_CODE = "04"
        const val PROMOTIONS_WITH_POINTS =
            """[{"assetId":"0","oldPromotionId":"","orgId":"0","promotionId":"0","promotionType":"-6"},{"assetId":"0","oldPromotionId":"","orgId":"0","promotionId":"0","promotionType":"-7"},{"assetId":"0","oldPromotionId":"0","orgId":"0","promotionId":"0","promotionType":"8"}]"""

        // 官方不勾积分时追加 promotionType "-8"（显式关闭积分抵扣），并按需给出积分项
        fun promotions(usePoints: Boolean): String =
            if (usePoints) PROMOTIONS_WITH_POINTS else PROMOTIONS_WITHOUT_POINTS
        const val PROMOTIONS_WITHOUT_POINTS =
            """[{"assetId":"0","oldPromotionId":"","orgId":"0","promotionId":"0","promotionType":"-6"},{"assetId":"0","oldPromotionId":"","orgId":"0","promotionId":"0","promotionType":"-7"},{"assetId":"0","oldPromotionId":"0","orgId":"0","promotionId":"0","promotionType":"-8"}]"""
    }
}
