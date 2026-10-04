package com.inonvation.lightlife.data

import android.content.Context
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import java.security.MessageDigest
import kotlin.jvm.Volatile

class TaskCancelledException : Exception()

/**
 * 积分任务执行核心。逻辑参照社区验证可用的 pangguai.py 脚本重写：
 * 每日签到 → 首页浏览 → 任务列表 → 支付宝游戏 → 支付宝广告。
 *
 * 与旧版（v2.0.0 前）的区别：
 * - 首页浏览改为单次调用 task/queryByType（旧的 subtaskCode 子任务已失效）
 * - APP 视频（taskCode=2）已失效，不再执行
 * - 支付宝任务用 alipay 渠道签名，随机等待 16~20 秒模拟真人
 * - 单次完成任务积分为 0 时立即跳过该任务，不再空跑
 */
class PointsTaskRunner(
    private val tokenProvider: () -> String?,
    private val context: Context? = null,
    private val deviceIdProvider: () -> String? = { null },
) {
    @Volatile
    var cancelled = false
    @Volatile
    var paused = false

    private val client = HttpClientProvider.client
    private val jsonAdapter: JsonAdapter<Map<String, Any?>> = MoshiProvider.instance
        .adapter(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))
    private val prefs by lazy { context?.getSharedPreferences("points_task", Context.MODE_PRIVATE) }

    private fun today(): String = DateUtils.today()

    private fun checkCancelled() {
        if (cancelled) throw TaskCancelledException()
    }

    private suspend fun waitIfPaused(log: suspend (String) -> Unit) {
        if (paused) log("⏸ 任务已暂停，等待继续...")
        while (paused) {
            delay(1000)
            if (cancelled) throw TaskCancelledException()
        }
    }

    suspend fun run(userAgent: String, log: suspend (String) -> Unit) {
        checkCancelled()
        waitIfPaused(log)
        val token = tokenProvider()?.takeIf { it.isNotBlank() } ?: error("请先登录")

        // request() 已自动把 token 追加进 body，无需重复传
        val user = request(USER_INFO, token, userAgent, emptyMap())
        val userName = user.dataMap()["userName"]?.toString()
        log(if (userName.isNullOrBlank()) "当前账号未设置昵称" else "当前账号：$userName")

        var lastBalance = balance(token, userAgent)
        val initialBalance = lastBalance
        log("任务前积分：${lastBalance ?: "-"}")

        // ── 每日签到 ──
        checkCancelled()
        waitIfPaused(log)
        if (prefs?.getString(KEY_SIGNIN_DATE, "") == today()) {
            log("签到：今日已完成，跳过")
        } else {
            val earned = signIn(token, userAgent, log)
            delay(1500)
            lastBalance = balance(token, userAgent) ?: lastBalance
            if (earned != null) log("签到完成 (+${earned})，当前 $lastBalance")
        }

        // ── 首页浏览 ──
        checkCancelled()
        waitIfPaused(log)
        if (prefs?.getString(KEY_HOME_DATE, "") == today()) {
            log("首页浏览：今日已完成，跳过")
        } else {
            lastBalance = homePageBrowse(token, userAgent, log, lastBalance)
        }

        // ── 任务列表（普通任务）──
        checkCancelled()
        waitIfPaused(log)
        lastBalance = runTaskList(token, userAgent, log, lastBalance)

        // 说明：支付宝游戏/广告任务已失效——平台改为广告 SDK 服务端回调发放积分
        // （需真实观看广告，纯接口调用不再加分），故移除，避免空跑触发风控。
        val runEarned = (lastBalance ?: 0) - (initialBalance ?: 0)
        log("任务完成，当前积分：$lastBalance（本次 +$runEarned）")
    }

    /** 签到，返回获得的积分数（已签到返回 0） */
    private suspend fun signIn(token: String, ua: String, log: suspend (String) -> Unit): Int? {
        val before = balance(token, ua)
        val res = request(DO_SIGN_IN, token, ua, mapOf("activityId" to "600001"))
        return when (res.codeInt()) {
            0 -> {
                prefs?.edit()?.putString(KEY_SIGNIN_DATE, today())?.apply()
                val total = (res.dataMap()["totalIntegral"] as? Number)?.toInt()
                log("签到成功${total?.let { "，总积分 $it" } ?: ""}")
                balance(token, ua)?.let { after -> before?.let { after - it } } ?: 0
            }
            33001 -> {
                prefs?.edit()?.putString(KEY_SIGNIN_DATE, today())?.apply()
                log("今天已经签到过")
                0
            }
            else -> {
                log("签到失败：${res.messageText()}")
                null
            }
        }
    }

    /** 首页浏览：task/queryByType 单次调用（官方 body 不带 token，token 走 header） */
    private suspend fun homePageBrowse(token: String, ua: String, log: suspend (String) -> Unit, lastBalance: Int?): Int? {
        log("首页浏览...")
        val before = balance(token, ua)
        val res = request(QUERY_BY_TYPE, token, ua, mapOf("taskCode" to HOME_BROWSE_TASK_CODE))
        delay(1500)
        val cur = balance(token, ua) ?: before
        val diff = cur?.let { c -> before?.let { c - it } }
        if (res.codeInt() == 0 && res["data"] != null) {
            prefs?.edit()?.putString(KEY_HOME_DATE, today())?.apply()
            log("首页浏览：完成 (+${diff ?: 0})")
        } else {
            log("首页浏览：${res.messageText()}")
        }
        return cur ?: lastBalance
    }

    /**
     * 任务列表阶段。参照 pangguai.py：
     * - 跳过指定标题（浏览微博）
     * - 跳过服务端已完成的任务
     * - 单次积分为 0 时跳过该任务
     */
    private suspend fun runTaskList(
        token: String,
        ua: String,
        log: suspend (String) -> Unit,
        lastBalance: Int?,
    ): Int? {
        var curBalance = lastBalance
        log("任务列表...")
        // 官方有两种形态：HomeVm 空 body / SignInViewModel body 带 deviceId(OAID)。先空 body，
        // 被「未登录」拒绝（设备风控）时再带 deviceId 重试
        var res = request(TASK_LIST, token, ua, emptyMap())
        if (res.codeInt() != 0 && isNotLoggedIn(res)) {
            delay(800)
            res = request(TASK_LIST, token, ua, mapOf("deviceId" to deviceId()))
        }
        if (res.codeInt() != 0) {
            log("获取任务列表失败：${res.errorText()}")
            if (isNotLoggedIn(res)) {
                log("提示：任务接口校验设备身份。请先在本应用内用手机号+验证码重新登录（勿用旧 Token），再重试积分任务")
            }
            return curBalance
        }
        val items = (res.dataMap()["items"] as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()
        if (items.isEmpty()) {
            log("暂无任务")
            return curBalance
        }

        for (item in items) {
            checkCancelled()
            waitIfPaused(log)
            val taskCode = item["taskCode"]?.toString() ?: continue
            val title = item["title"]?.toString().orEmpty().ifBlank { "未命名任务" }
            val completedStatus = (item["completedStatus"] as? Number)?.toInt() ?: -1
            val limit = (item["dailyTaskLimit"] as? Number)?.toInt() ?: 1

            if (SKIP_TASK_TITLES.any { title.contains(it) }) {
                log("跳过「$title」（无实际积分）")
                continue
            }
            if (completedStatus != 0 || taskCode in NOT_FINISH_TASKS || taskCode == "2") {
                continue
            }
            if (limit <= 0) {
                log("跳过「$title」（无次数）")
                continue
            }

            log("开始执行：$title")
            var earnedTotal = 0
            var aborted = false
            for (attempt in 1..limit) {
                checkCancelled()
                waitIfPaused(log)
                val before = balance(token, ua)
                val taskRes = try {
                    completeTask(token, ua, taskCode)
                } catch (e: TaskCancelledException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("「$title」请求失败：${(e.message ?: "未知").take(60)}")
                    aborted = true
                    break
                }
                delay(1200)
                val after = balance(token, ua)
                val earned = after?.let { a -> before?.let { a - it } }

                if (isTaskFinished(taskRes)) {
                    log("「$title」任务已结束")
                    aborted = true
                    break
                }
                if (taskRes.codeInt() == CAPTCHA_REQUIRED_CODE) {
                    log("「$title」${taskRes.messageText()}")
                    aborted = true
                    break
                }
                if (taskRes.codeInt() == 0) {
                    if (earned == null || earned <= 0) {
                        log("「$title」本次无积分，跳过")
                        aborted = true
                        break
                    }
                    earnedTotal += earned
                    log("「$title」$attempt/$limit 完成 (+$earned)")
                } else {
                    log("「$title」失败：${taskRes.messageText()}")
                    aborted = true
                    break
                }
                if (attempt < limit) delay(9000L + (0..3000).random())
            }
            curBalance = balance(token, ua) ?: curBalance
            if (earnedTotal > 0) log("「$title」累计 +$earnedTotal")
            if (aborted) continue
            delay(2000)
        }
        return curBalance
    }

    /**
     * 支付宝游戏/广告等重复任务。alipay 渠道签名，每次间隔 16~20 秒。
     * 连续失败 2 次即停止，避免触发风控。
     */
    private suspend fun runRepetitiveTask(
        token: String,
        ua: String,
        log: suspend (String) -> Unit,
        lastBalance: Int?,
        taskCode: String,
        label: String,
        total: Int,
    ): Int? {
        var curBalance = lastBalance
        log("开始${label}任务...")
        var completed = 0
        var totalEarned = 0
        var consecutiveFails = 0
        val maxConsecutiveFails = 2

        for (i in 1..total) {
            checkCancelled()
            waitIfPaused(log)
            val before = balance(token, ua)
            val res = try {
                completeTask(token, ua, taskCode, channel = "alipay")
            } catch (e: TaskCancelledException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveFails++
                log("$label 网络异常：${(e.message ?: "未知").take(60)}（连续失败 $consecutiveFails/$maxConsecutiveFails）")
                if (consecutiveFails >= maxConsecutiveFails) {
                    log("$label 连续失败达上限，停止")
                    break
                }
                delay(5000)
                continue
            }
            val after = balance(token, ua)
            val earned = after?.let { a -> before?.let { a - it } }

            if (res.codeInt() in REQUEST_ERROR_CODES) {
                consecutiveFails++
                log("$label 网络错误：${res.messageText()}（连续失败 $consecutiveFails/$maxConsecutiveFails）")
                if (consecutiveFails >= maxConsecutiveFails) {
                    log("$label 连续失败达上限，停止")
                    break
                }
                delay(5000)
                continue
            }
            if (isTaskFinished(res)) {
                log("$label 任务已结束")
                break
            }
            if (res.codeInt() == 0 && res["data"] != null) {
                if (earned == null || earned <= 0) {
                    log("$label 本次无积分，无需完成，跳过")
                    break
                }
                completed++
                consecutiveFails = 0
                totalEarned += earned
                curBalance = after ?: curBalance
                log("$label $completed/$total 完成 (+$earned)，累计 +$totalEarned")
                if (completed < total) {
                    val wait = 16000L + (0..4000).random()
                    log("等待 ${wait / 1000.0} 秒继续...")
                    delay(wait)
                }
            } else {
                consecutiveFails++
                log("$label 单次失败：${res.messageText()}（连续失败 $consecutiveFails/$maxConsecutiveFails）")
                if (consecutiveFails >= maxConsecutiveFails) {
                    log("$label 连续失败达上限，停止")
                    break
                }
                delay(2000)
            }
        }
        log("$label 任务结束：完成 $completed 次，累计 +$totalEarned 积分")
        return curBalance
    }

    private suspend fun completeTask(token: String, ua: String, taskCode: String, channel: String = "android_app"): Map<String, Any?> {
        // 对齐官方风控流程：任务完成前先查验证码预检，data=true 表示被风控拦截
        val captcha = request(IS_CAPTCHA, token, ua, emptyMap(), channel)
        if ((captcha["data"] as? Boolean) == true) {
            return mapOf("code" to CAPTCHA_REQUIRED_CODE, "msg" to "需要人机验证（请在官方 App 中完成验证后重试）")
        }
        // 官方 body 只传 taskCode（+可选 subtaskCode），token 由官方拦截器补进 body
        return request(TASK_COMPLETED, token, ua, mapOf("taskCode" to taskCode), channel)
    }

    /** 服务端以 code=-1 且 msg 含「任务已结束」表示任务整体结束 */
    private fun isTaskFinished(res: Map<String, Any?>): Boolean {
        val msg = res.messageText()
        return res.codeInt() == -1 && msg.contains("任务已结束") ||
            msg.contains("已结束") || msg.contains("已达上限") || msg.contains("今日已满")
    }

    private suspend fun balance(token: String, ua: String): Int? = try {
        val res = request(USER_BALANCE, token, ua, emptyMap())
        (res.dataMap()["integral"] as? Number)?.toInt()
    } catch (e: TaskCancelledException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** 服务端以消息文本区分风控拦截（如"未实名认证"、"积分风控拦截"），无专用错误码 */
    private fun isNotLoggedIn(res: Map<String, Any?>): Boolean {
        val msg = res.messageText().lowercase()
        return msg.contains("未登录") || msg.contains("未登陆") || msg.contains("请先登录")
    }

    private suspend fun request(
        url: String,
        token: String,
        userAgent: String,
        fields: Map<String, String>,
        channel: String = "android_app",
    ): Map<String, Any?> {
        val timestamp = System.currentTimeMillis().toString()
        // 对齐官方 HeadInterceptor：token 拼进 form body 尾部
        val form = FormBody.Builder().apply {
            fields.forEach { (key, value) -> add(key, value) }
            if (token.isNotBlank()) add("token", token)
        }.build()
        val req = Request.Builder()
            .url(url)
            .post(form)
            .headers(headers(url, token, userAgent, timestamp, channel))
            .build()
        return withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code == 401 || response.code == 403) {
                        error("token")
                    }
                    error("HTTP ${response.code}: ${body.take(200)}")
                }
                runCatching { jsonAdapter.fromJson(body).orEmpty() }
                    .getOrElse { mapOf("code" to JSON_PARSE_ERROR_CODE, "msg" to "响应解析失败") }
            }
        }
    }

    private fun headers(
        url: String,
        token: String,
        userAgent: String,
        timestamp: String,
        channel: String,
    ): okhttp3.Headers {
        val sign = if (channel == "alipay") signAlipay(timestamp, url, token) else signAndroid(timestamp, url, token)
        return okhttp3.Headers.Builder()
            .add("Authorization", token)
            .add("token", token)
            .add("Version", VERSION)
            .add("channel", channel)
            .add("phoneBrand", "Redmi")
            .add("deviceId", deviceId())
            .add("timestamp", timestamp)
            .add("sign", sign)
            .add("Content-Type", ApiConfig.CONTENT_TYPE)
            .add("Host", "userapi.qiekj.com")
            .add("Connection", "Keep-Alive")
            .add("Accept-Encoding", "gzip")
            .add("User-Agent", userAgent)
            .build()
    }

    /** 稳定设备标识：与主客户端共享同一 ID（模拟官方 OAID），服务端以它做设备维度风控 */
    private fun deviceId(): String {
        deviceIdProvider()?.takeIf { it.isNotBlank() }?.let { return it }
        val cached = prefs?.getString(KEY_DEVICE_ID, null)
        if (!cached.isNullOrBlank()) return cached
        val generated = java.util.UUID.randomUUID().toString().replace("-", "")
        prefs?.edit()?.putString(KEY_DEVICE_ID, generated)?.apply()
        return generated
    }

    private fun signAndroid(timestamp: String, url: String, token: String): String = sha256(
        "appSecret=${ApiConfig.ANDROID_SECRET}&channel=android_app&timestamp=$timestamp&token=$token&version=$VERSION&${url.drop(25)}",
    )

    private fun signAlipay(timestamp: String, url: String, token: String): String = sha256(
        "appSecret=${ApiConfig.ALIPAY_SECRET}&channel=alipay&timestamp=$timestamp&token=$token&version=$VERSION&${url.drop(25)}",
    )

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun Map<String, Any?>.codeInt(): Int? = (this["code"] as? Number)?.toInt()
    private fun Map<String, Any?>.messageText(): String =
        this["msg"]?.toString() ?: this["message"]?.toString() ?: this["errMsg"]?.toString() ?: "未知结果"

    /** 带错误码的完整错误描述，用于日志定位 */
    private fun Map<String, Any?>.errorText(): String {
        val code = codeInt() ?: "无"
        val msg = messageText()
        return if (msg == "未知结果") "code=$code" else "code=$code $msg"
    }
    private fun Map<String, Any?>.dataMap(): Map<String, Any?> = this["data"] as? Map<String, Any?> ?: emptyMap()

    companion object {
        // 官方 1.142.0 的 HeadInterceptor 用 App 实际版本号参与签名，旧脚本的 1.96.1 已被服务端拒绝
        const val VERSION = ApiConfig.VERSION
        const val HOME_BROWSE_TASK_CODE = "8b475b42-df8b-4039-b4c1-f9a0174a611a"
        const val ALIPAY_VIDEO_TASK_CODE = "dc18b525-f679-47d8-805a-e331f8f3341d"
        const val ALIPAY_AD_TASK_CODE = "9"
        const val MAX_VIDEO_ATTEMPTS = 10
        const val MAX_AD_ATTEMPTS = 50
        val SKIP_TASK_TITLES = listOf("浏览微博")
        val NOT_FINISH_TASKS = setOf(
            "7328b1db-d001-4e6a-a9e6-6ae8d281ddbf",
            "e8f837b8-4317-4bf5-89ca-99f809bf9041",
            "65a4e35d-c8ae-4732-adb7-30f8788f2ea7",
            "73f9f146-4b9a-4d14-9d81-3a83f1204b74",
            "12e8c1e4-65d9-45f2-8cc1-16763e710036",
        )
        const val HTTP_ERROR_CODE = -1001
        const val TIMEOUT_ERROR_CODE = -1002
        const val REQUEST_EXCEPTION_CODE = -1003
        const val JSON_PARSE_ERROR_CODE = -1004
        val REQUEST_ERROR_CODES = setOf(HTTP_ERROR_CODE, TIMEOUT_ERROR_CODE, REQUEST_EXCEPTION_CODE, JSON_PARSE_ERROR_CODE)

        private const val BASE = "https://userapi.qiekj.com"
        val USER_INFO = "$BASE/user/info"
        val USER_BALANCE = "$BASE/user/balance"
        val DO_SIGN_IN = "$BASE/signin/doUserSignIn"
        val QUERY_BY_TYPE = "$BASE/task/queryByType"
        val TASK_LIST = "$BASE/task/list"
        val TASK_COMPLETED = "$BASE/task/completed"

        private const val KEY_SIGNIN_DATE = "signin_date"
        private const val KEY_HOME_DATE = "home_browse_date"
        private const val KEY_DEVICE_ID = "device_id"
        const val CAPTCHA_REQUIRED_CODE = -2001
        val IS_CAPTCHA = "$BASE/integralCaptcha/isCaptcha"
    }
}
