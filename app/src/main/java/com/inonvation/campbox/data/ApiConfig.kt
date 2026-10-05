package com.inonvation.campbox.data

object ApiConfig {
    const val BASE_URL = "https://userapi.qiekj.com/"
    const val VERSION = "1.142.0"
    // 来自参考 APK 的 AndroidManifest，版本码不是由版本名拼接得到。
    const val VERSION_CODE = 279
    const val LOGIN_CHANNEL = "android_app"
    const val API_CHANNEL = "android_app"
    const val PHONE_BRAND = "Redmi"
    // 对齐参考 APK 的 HeadInterceptor.buildUserAgent()，扫码同样需要完整客户端版本标识。
    const val USER_AGENT =
        "QEUser/$VERSION (com.qiekj.user; build:$VERSION_CODE; Android 14; userChannel:android_app; version:$VERSION) OkHttp/4.12.0"
    const val POINTS_USER_AGENT = USER_AGENT

    const val CONTENT_TYPE = "application/x-www-form-urlencoded;charset=UTF-8"
    const val ANDROID_SECRET = "nFU9pbG8YQoAe1kFh+E7eyrdlSLglwEJeA0wwHB1j5o="
    const val ALIPAY_SECRET = "Ew+ZSuppXZoA9YzBHgHmRvzt0Bw1CpwlQQtSl49QNhY="
}
