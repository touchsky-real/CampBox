package com.inonvation.campbox.data

import com.squareup.moshi.Moshi
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class CampusReplyStatus { ACCEPTED, RETRYABLE, REJECTED }
internal data class CampusReply(val status: CampusReplyStatus, val message: String)

private val campusJson = Moshi.Builder().build().adapter(Map::class.java)

/** 解析 JSON/JSONP，不能用字符串包含 result:1 判定（会误认 result:10）。 */
internal fun parseCampusReply(raw: String): CampusReply {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    val fields = if (start >= 0 && end > start) {
        runCatching { campusJson.fromJson(raw.substring(start, end + 1)) }.getOrNull()
    } else null
    if (fields == null) return CampusReply(CampusReplyStatus.REJECTED, "认证网关响应无法识别，请稍后重试")
    fun number(key: String): Int? = when (val value = fields[key]) {
        is Number -> value.toInt().takeIf { value.toDouble() == it.toDouble() }
        is String -> value.toIntOrNull()
        else -> null
    }
    val message = (fields["msg"] as? String).orEmpty().trim()
    return when {
        number("ret_code") == 8 || (message.contains("Portal", true) && message.contains("超时")) ->
            CampusReply(CampusReplyStatus.RETRYABLE, "Portal 协议认证超时，校园网会话暂未就绪")
        number("result") == 1 || message.contains("终端IP已在线", true) || message.contains("Portal协议认证成功") ->
            CampusReply(CampusReplyStatus.ACCEPTED, message)
        message.contains("密码") && (message.contains("错误") || message.contains("失败")) ->
            CampusReply(CampusReplyStatus.REJECTED, "账号或密码错误，请核对后重试")
        else -> CampusReply(CampusReplyStatus.REJECTED,
            if (message.isBlank()) "认证被网关拒绝，请检查账号或校园网状态" else "认证失败：${message.take(120)}")
    }
}

internal data class DiscoveredPortal(val acIp: String?, val userIp: String?)

/** HttpUrl 解码参数值，兼容 wlanacip / wlan_ac_ip 及不同大小写。 */
internal fun parseCampusPortal(location: String): DiscoveredPortal? {
    val url = location.replace("&amp;", "&").toHttpUrlOrNull() ?: return null
    fun ipParameter(key: String): String? {
        val name = url.queryParameterNames.firstOrNull {
            it.replace("_", "").equals(key, ignoreCase = true)
        } ?: return null
        return url.queryParameter(name)?.takeIf { value ->
            val parts = value.split('.')
            parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.all { it in '0'..'9' } && part.toIntOrNull() in 0..255
            } && value != "0.0.0.0"
        }
    }
    val ac = ipParameter("wlanacip")
    val user = ipParameter("wlanuserip")
    return if (ac == null && user == null) null else DiscoveredPortal(ac, user)
}
