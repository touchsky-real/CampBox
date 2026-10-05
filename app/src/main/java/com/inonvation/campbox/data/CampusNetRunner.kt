package com.inonvation.campbox.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class CampusNetResult {
    object Success : CampusNetResult()
    data class Failure(val reason: String) : CampusNetResult()
    data class Skipped(val reason: String) : CampusNetResult()
}

/** 校园网 Dr.COM 认证：socket、DNS 和本机 IP 始终来自同一条 Wi-Fi。 */
class CampusNetRunner(private val context: Context) {
    var officialPortalUrl: String = CAMPUS_PORTAL_URL
        private set
    private val connectivity get() =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun login(
        username: String,
        password: String,
        log: suspend (String) -> Unit,
        auto: Boolean = false,
    ): CampusNetResult {
        officialPortalUrl = CAMPUS_PORTAL_URL
        if (username.isBlank() || password.isBlank()) {
            return if (auto) CampusNetResult.Skipped("未配置校园网账号")
            else CampusNetResult.Failure("请先填写校园网账号和密码")
        }
        // 总时限包含 DNS、所有探测和重试，取消时会取消正在执行的 HTTP 请求。
        return withTimeoutOrNull(90_000) {
            loginOnWifi(username, password, log, auto)
        } ?: CampusNetResult.Failure("连接超时，请检查校园网 Wi-Fi 后重试")
    }

    private suspend fun loginOnWifi(
        username: String, password: String, log: suspend (String) -> Unit, auto: Boolean,
    ): CampusNetResult {
        log("检查 Wi-Fi 状态...")
        val initial = awaitWifiReady(log) ?: return if (auto) {
            CampusNetResult.Skipped("Wi-Fi 未就绪，跳过自动认证")
        } else {
            CampusNetResult.Failure("Wi-Fi 未连接或尚未获取 IPv4 地址，请检查系统 Wi-Fi 设置")
        }
        val ssid = currentSsid()
        if (ssid == null) {
            log("无法读取 Wi-Fi 名称，不影响使用 Wi-Fi 通道认证")
        } else {
            log("当前 Wi-Fi：$ssid")
            if (!ssid.contains("tyut", ignoreCase = true)) {
                if (auto) return CampusNetResult.Skipped("当前 Wi-Fi 非校园网")
                log("若当前 Wi-Fi 不是校园网，认证将无法完成")
            }
        }

        var lastFailure = "校园网网关暂未就绪"
        repeat(3) { attempt ->
            val wifi = wifiSnapshot()
            if (wifi == null || wifi.network != initial.network) {
                return CampusNetResult.Failure("Wi-Fi 连接已变化，请连接校园网后重试")
            }
            val client = wifiClient(wifi.network)
            try {
                log(if (attempt == 0) "检查 Wi-Fi 外网连通性..." else "重新检查校园网（第 ${attempt + 1}/3 次）...")
                if (checkInternet(client)) {
                    log("校园 Wi-Fi 外网已连通，无需重复认证")
                    return CampusNetResult.Success
                }
                val portal = discoverPortal(client)
                officialPortalUrl = campusPortalUrl(portal?.acIp, portal?.userIp)
                // DHCP 更新后不能继续提交旧 IP。
                if (wifiSnapshot() != wifi) {
                    lastFailure = "Wi-Fi 地址正在更新"
                } else {
                    val userIp = portal?.userIp ?: wifi.ip
                    val acIp = portal?.acIp ?: AC_IP
                    log("本机 IP：$userIp")
                    log(if (portal != null) "已获取本次网关参数：AC=$acIp" else "未获取网关重定向，使用学校默认认证参数")
                    val url = (PORTAL_BASE + LOGIN_PATH).toHttpUrl().newBuilder().apply {
                        buildParams(username, password, userIp, acIp).forEach { (k, v) -> addQueryParameter(k, v) }
                    }.build()
                    log("正在向校园网网关提交认证...")
                    val response = request(client, Request.Builder().url(url)
                        .header("Referer", "https://drcom.tyut.edu.cn/")
                        .header("User-Agent", UA).header("Accept", "*/*").build())
                    if (response.code !in 200..299) {
                        return CampusNetResult.Failure("认证网关返回 HTTP ${response.code}，请稍后重试")
                    }
                    val reply = parseCampusReply(response.body)
                    when (reply.status) {
                        CampusReplyStatus.ACCEPTED -> {
                            log("网关已接受认证，确认 Wi-Fi 外网连通性（最多 20 秒）...")
                            val online = withTimeoutOrNull(20_000) {
                                while (!checkInternet(client)) {
                                    if (wifiSnapshot() != wifi) return@withTimeoutOrNull false
                                    delay(1_000)
                                }
                                true
                            } == true
                            if (online) {
                                log("校园网认证成功，Wi-Fi 外网已连通")
                                return CampusNetResult.Success
                            }
                            // 已接受的认证不重复提交，避免挤掉会话或触发频率限制。
                            return CampusNetResult.Failure("网关已接受认证，但 Wi-Fi 外网尚未连通，请检查校园网状态")
                        }
                        CampusReplyStatus.REJECTED -> return CampusNetResult.Failure(reply.message)
                        CampusReplyStatus.RETRYABLE -> {
                            lastFailure = reply.message
                            log("网关返回：${reply.message}")
                        }
                    }
                }
            } catch (e: IOException) {
                // 异常 URL 可能包含编码后的账号密码，不写入日志。
                lastFailure = "Wi-Fi 暂时无法连接认证网关（${e.javaClass.simpleName}）"
                log(lastFailure)
            } finally {
                cleanupCampusResources(
                    { client.dispatcher.cancelAll() },
                    { client.connectionPool.evictAll() },
                    { client.dispatcher.executorService.shutdown() },
                )
            }
            if (attempt < 2) {
                log("等待校园网会话就绪，2 秒后重新获取网关参数并重试...")
                delay(2_000)
            }
        }
        return CampusNetResult.Failure("$lastFailure（已尝试 3 次），请重新连接校园网 Wi-Fi 或稍后重试")
    }

    /** 从官方网页登录返回后只检测联网，不再次提交认证。 */
    suspend fun verifyWifiInternet(): Boolean {
        val wifi = wifiSnapshot() ?: return false
        val client = wifiClient(wifi.network)
        return try {
            checkInternet(client)
        } finally {
            cleanupCampusResources(
                { client.dispatcher.cancelAll() },
                { client.connectionPool.evictAll() },
                { client.dispatcher.executorService.shutdown() },
            )
        }
    }

    fun isOnWifi(): Boolean = connectivity.allNetworks.any {
        val caps = connectivity.getNetworkCapabilities(it)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    private data class WifiSnapshot(val network: Network, val ip: String)

    /** 使用这条 Wi-Fi 的 LinkProperties，避免混入其他网卡或 VPN 的地址。 */
    private fun wifiSnapshot(): WifiSnapshot? = connectivity.allNetworks.firstNotNullOfOrNull { network ->
        val caps = connectivity.getNetworkCapabilities(network)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@firstNotNullOfOrNull null
        val ip = connectivity.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
            ?.hostAddress ?: return@firstNotNullOfOrNull null
        WifiSnapshot(network, ip)
    }

    /** 当前 Wi-Fi SSID；无定位权限、正在关联或未连 Wi-Fi 时返回 null（系统返回 "<unknown ssid>"） */
    @Suppress("DEPRECATION")
    private fun currentSsid(): String? = runCatching {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return@runCatching null
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ssid = wm.connectionInfo?.ssid ?: return@runCatching null
        if (ssid.startsWith("<")) null else ssid.removeSurrounding("\"")
    }.getOrNull()


    private suspend fun awaitWifiReady(log: suspend (String) -> Unit): WifiSnapshot? {
        wifiSnapshot()?.let { return it }
        log("等待 Wi-Fi 连接并获取 IP（最多 15 秒）...")
        return withTimeoutOrNull(15_000) {
            var wifi = wifiSnapshot()
            while (wifi == null) {
                delay(500)
                wifi = wifiSnapshot()
            }
            wifi
        }
    }

    /** 每次连接使用独立连接池，不复用蜂窝连接，也不修改整个 App 的进程网络绑定。 */
    private fun wifiClient(network: Network): OkHttpClient = OkHttpClient.Builder()
        .socketFactory(network.socketFactory)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
        })
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    /** 两个探针并行，整轮最多 3 秒，不能通过移动数据误报成功。 */
    private suspend fun checkInternet(client: OkHttpClient): Boolean = coroutineScope {
        PROBES.map { url ->
            async {
                withTimeoutOrNull(3_000) {
                    try {
                        val reply = request(client, Request.Builder().url(url).header("User-Agent", UA).build())
                        reply.code == 204 && reply.body.isEmpty()
                    } catch (_: IOException) { false }
                } == true
            }
        }.awaitAll().any { it }
    }

    /** 相对跳转和多次跳转均可发现；单个探针最多 2 秒，整个发现阶段最多 5 秒。 */
    private suspend fun discoverPortal(client: OkHttpClient): DiscoveredPortal? =
        withTimeoutOrNull(5_000) discovery@{
            for (probe in DISCOVERY_PROBES) {
                val found = withTimeoutOrNull(2_000) probe@{
                    var url = probe.toHttpUrl()
                    repeat(3) {
                        try {
                            val reply = request(client, Request.Builder().url(url).header("User-Agent", UA).build())
                            val location = reply.location ?: return@probe null
                            if (reply.code !in 300..399) return@probe null
                            url = url.resolve(location) ?: return@probe null
                            parseCampusPortal(url.toString())?.let { return@probe it }
                            if (url.isHttps) return@probe null
                        } catch (_: IOException) { return@probe null }
                    }
                    null
                }
                if (found != null) return@discovery found
            }
            null
        }

    private data class HttpReply(val code: Int, val body: String, val location: String?)

    /** 工作线程读取响应；协程超时/取消时立即取消 Call，防止一直停留在认证中。 */
    private suspend fun request(client: OkHttpClient, request: Request): HttpReply =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val reply = response.use {
                            HttpReply(it.code, it.body?.string().orEmpty().trim(), it.header("Location"))
                        }
                        continuation.resume(reply)
                    } catch (e: IOException) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }

    /** Dr.COM ePortal 编码：每字符异或 0x77 转两位十六进制 */
    private fun drcomEncode(text: String): String =
        text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format((it.toInt() and 0xff) xor 0x77) }

    private fun buildParams(username: String, password: String, userIp: String, acIp: String): List<Pair<String, String>> = listOf(
        "callback" to drcomEncode("dr1001"),
        "login_method" to drcomEncode("1"),
        "user_account" to drcomEncode(username),
        "user_password" to drcomEncode(password),
        "wlan_user_ip" to drcomEncode(userIp),
        "wlan_user_ipv6" to "",
        "wlan_user_mac" to drcomEncode("000000000000"),
        "wlan_ac_ip" to drcomEncode(acIp),
        "wlan_ac_name" to "",
        "mac_type" to drcomEncode("0"),
        "authex_enable" to "",
        "jsVersion" to drcomEncode("4.3"),
        "web" to drcomEncode("0"),
        "terminal_type" to drcomEncode("1"),
        "lang" to drcomEncode("en"),
        "user_agent" to drcomEncode(UA),
        "enable_r3" to drcomEncode("0"),
        "encrypt" to "1",
        "v" to "1077",
    )


    companion object {
        private const val PORTAL_BASE = "https://drcom.tyut.edu.cn:804"
        private const val LOGIN_PATH = "/eportal/portal/login"
        private const val AC_IP = "219.226.127.249"
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private val PROBES = listOf(
            "http://connect.rom.miui.com/generate_204",
            "http://connectivitycheck.platform.hicloud.com/generate_204",
        )
        private val DISCOVERY_PROBES = listOf(
            "http://connect.rom.miui.com/generate_204",
            "http://www.msftconnecttest.com/connecttest.txt",
            "http://www.baidu.com/",
        )
    }
}
