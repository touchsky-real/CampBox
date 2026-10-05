package com.inonvation.campbox.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface

/** 校园网认证结果：成功为 Success，失败时 reason 给出人话原因 */
sealed class CampusNetResult {
    object Success : CampusNetResult()
    data class Failure(val reason: String) : CampusNetResult()
}

/**
 * 校园网 Dr.COM ePortal 自动认证。
 * 协议对齐 campus_login.py：账密异或 0x77 编码为十六进制，GET 提交网关。
 * 手机端差异：本机 IP 从 wlan0 接口直接读取（无需解析 ipconfig）。
 */
class CampusNetRunner(private val context: Context) {

    val client: OkHttpClient = HttpClientProvider.client

    /** 纯探测客户端：不跟随重定向、短超时，用于 204 探针 */
    private val probeClient: OkHttpClient = HttpClientProvider.client.newBuilder()
        .followRedirects(false)
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * 执行登录流程。
     * @param username 学号/上网账号
     * @param password 上网密码（明文，仅编码后发往校园网网关）
     * @param log 进度日志回调
     * @return 成功与否由 UI 层展示
     */
    suspend fun login(username: String, password: String, log: suspend (String) -> Unit): CampusNetResult {
        if (username.isBlank() || password.isBlank()) {
            log("请先输入账号和密码")
            return CampusNetResult.Failure("未保存校园网账号密码，请先在校园网卡片中填写")
        }
        if (!isOnWifi()) {
            log("未连接 Wi-Fi")
            return CampusNetResult.Failure("手机未连接 Wi-Fi，请先连接校园网 Wi-Fi 再试")
        }
        log("检查网络状态...")
        if (checkInternet()) {
            log("当前网络已可上网，无需认证")
            return CampusNetResult.Success
        }

        val userIp = getWlanIp()
        if (userIp == null) {
            log("未获取到本机 IP")
            return CampusNetResult.Failure("未获取到本机校园网 IP，请确认已连接校园网 Wi-Fi（当前可能连的是其他网络或热点）")
        }
        log("本机 IP：$userIp")

        log("正在向校园网网关提交认证...")
        val params = buildParams(username, password, userIp)
        val url = LOGIN_HOST.toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder()
            .url(url)
            .header("Referer", "https://drcom.tyut.edu.cn/")
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .build()

        val rawResult = withContext(Dispatchers.IO) {
            runCatching { client.newCall(req).execute().use { it.body?.string().orEmpty().trim() } }
        }
        val raw = rawResult.getOrElse { e ->
            log("认证请求失败：${(e.message ?: "未知错误").take(80)}")
            return CampusNetResult.Failure("无法连接认证网关（${(e.message ?: "未知错误").take(50)}），很可能当前连的不是校园网 Wi-Fi")
        }
        log("网关返回：${raw.take(120)}")

        when {
            raw.contains("终端IP已在线") -> {
                log("设备已在线")
                return CampusNetResult.Success
            }
            raw.contains("\"result\":1") || raw.contains("Portal协议认证成功") -> {
                log("认证已接受，等待网关放行...")
            }
            raw.contains("密码") && (raw.contains("错误") || raw.contains("失败")) -> {
                log("认证失败：账号或密码错误")
                return CampusNetResult.Failure("账号或密码错误，请核对后重试")
            }
            raw.isNotEmpty() -> {
                // 其他返回内容原样展示，便于排查
            }
        }

        // 网关放行需要几秒到十几秒，单次探测太早会误报失败：持续重试 ~20 秒
        repeat(10) { attempt ->
            kotlinx.coroutines.delay(2000)
            if (checkInternet()) {
                log("校园网认证成功，外网已连通")
                return CampusNetResult.Success
            }
            if (attempt == 3) log("网关仍在放行中，继续等待...")
        }
        log("等待超时：认证已提交但外网未连通")
        return CampusNetResult.Failure("认证已提交，但等待 20 秒后外网仍未连通，可稍后重试或检查校园网状态")
    }

    /**
     * 当前是否连着 Wi-Fi（区分「没连 Wi-Fi」和「连了但认证失败」）。
     * 不能用 activeNetworkInfo：校园网未认证时 Wi-Fi 无外网，系统会把「当前活动网络」
     * 判成移动数据，导致误报「未连接 Wi-Fi」；必须遍历所有网络找 Wi-Fi 传输通道，
     * 且不要求 NET_CAPABILITY_VALIDATED（认证前本来就没外网）。
     */
    fun isOnWifi(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }.getOrDefault(false)

    /** 204 探针检测外网连通性 */
    suspend fun checkInternet(): Boolean = withContext(Dispatchers.IO) {
        PROBES.any { probe ->
            runCatching {
                val req = Request.Builder().url(probe).header("User-Agent", UA).build()
                probeClient.newCall(req).execute().use { it.code == 204 }
            }.getOrDefault(false)
        }
    }

    /** 从无线网卡（wlan0）读取 IPv4，避开 VPN 虚拟网卡 */
    private fun getWlanIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .filter { it.name.startsWith("wlan") || it.name.startsWith("eth") }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .firstOrNull { ip ->
                ip != null && !ip.startsWith("198.18.") && !ip.startsWith("127.") && !ip.startsWith("169.254.")
            }
    }.getOrNull()

    /** Dr.COM ePortal 编码：每字符异或 0x77 转两位十六进制 */
    private fun drcomEncode(text: String): String =
        text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() xor 0x77) }

    private fun buildParams(username: String, password: String, userIp: String): List<Pair<String, String>> = listOf(
        "callback" to drcomEncode("dr1001"),
        "login_method" to drcomEncode("1"),
        "user_account" to drcomEncode(username),
        "user_password" to drcomEncode(password),
        "wlan_user_ip" to drcomEncode(userIp),
        "wlan_user_ipv6" to "",
        "wlan_user_mac" to drcomEncode("000000000000"),
        "wlan_ac_ip" to drcomEncode(AC_IP),
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
        private const val LOGIN_HOST = "https://drcom.tyut.edu.cn:804/eportal/portal/login"
        private const val AC_IP = "219.226.127.249"
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private val PROBES = listOf(
            "http://connect.rom.miui.com/generate_204",
            "http://www.qualcomm.com/generate_204",
        )
    }
}
