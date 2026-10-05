package com.inonvation.campbox.data

import okhttp3.Interceptor
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.security.MessageDigest

class HeaderInterceptor(
    private val tokenProvider: () -> String?,
    private val deviceIdProvider: () -> String? = { null },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val timestamp = System.currentTimeMillis().toString()
        val request = chain.request()
        val path = request.url.encodedPath
        val form = request.body as? FormBody
        val formToken = form?.let { body ->
            (0 until body.size).firstOrNull { body.name(it) == "token" }?.let(body::value)
        }
        // 表单中的 Token 是发起请求时的快照，不能被后续登录/退出覆盖。
        val token = request.header("token") ?: formToken ?: tokenProvider().orEmpty()
        val isLoginApi = path.startsWith("/common/") || path.startsWith("/user/reg")
        val channel = if (isLoginApi) ApiConfig.LOGIN_CHANNEL else ApiConfig.API_CHANNEL
        val builder = request.newBuilder()
            .header("Version", ApiConfig.VERSION)
            .header("channel", channel)
            .header("phoneBrand", ApiConfig.PHONE_BRAND)
            .header("User-Agent", ApiConfig.USER_AGENT)
            .header("Content-Type", ApiConfig.CONTENT_TYPE)
            .header("timestamp", timestamp)
            .header("Host", "userapi.qiekj.com")
            .header("Connection", "Keep-Alive")
            // scan/v2 缺少 token 参数会误报“版本过低”，保留 token 请求头以兼容该接口。
            .header("token", token)
            .header("Authorization", token)
            .header("sign", sign(timestamp, path, token, channel))
        // 官方所有请求头都带 deviceId（OAID），服务端以它做设备风控；登录也带，保证会话绑定一致
        deviceIdProvider()?.takeIf { it.isNotBlank() }?.let { builder.header("deviceId", it) }

        if (form != null) {
            val normalized = FormBody.Builder().apply {
                for (i in 0 until form.size) {
                    if (form.name(i) != "token") addEncoded(form.encodedName(i), form.encodedValue(i))
                }
                if (token.isNotEmpty()) add("token", token)
            }.build()
            val buffer = Buffer()
            normalized.writeTo(buffer)
            builder.method(request.method, buffer.readByteString().toRequestBody(ApiConfig.CONTENT_TYPE.toMediaType()))
        }
        if (path in setOf("/goods/water/unlock", "/goods/shower/unlock", "/trade/create")) {
            val category = request.header("categoryCode")
            val imei = request.header("imei")
            val lat = request.header("lat")
            val lng = request.header("lng")
            if (listOf(category, imei, lat, lng).all { it != null }) {
                val text = "appSecret=${ApiConfig.ANDROID_SECRET}&categoryCode=$category&channel=$channel" +
                    "&imei=$imei&lat=$lat&lng=$lng&timestamp=$timestamp&token=$token&version=${ApiConfig.VERSION}&$path"
                builder.header("orderRiskSign", sha256(text))
                builder.header("orderRiskTimestamp", timestamp)
            }
        }

        return chain.proceed(builder.build())
    }

    private fun sign(timestamp: String, path: String, token: String, channel: String): String {
        val raw = "appSecret=${ApiConfig.ANDROID_SECRET}&channel=$channel&timestamp=$timestamp&token=$token&version=${ApiConfig.VERSION}&$path"
        return sha256(raw)
    }

    private fun sha256(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
