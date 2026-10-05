package com.inonvation.campbox.data

object ApiConfig {
    const val BASE_URL = "https://userapi.qiekj.com/"
    const val VERSION = "1.142.0"
    const val LOGIN_CHANNEL = "android_app"
    const val API_CHANNEL = "android_app"
    const val PHONE_BRAND = "Redmi"
    const val USER_AGENT = "okhttp/3.14.9"

    // 对齐官方 HeadInterceptor.buildUserAgent()：QEUser/<版本>（包名; build:版本码; Android 版本; 渠道）
    // 任务系接口（task/*）做设备风控，非官方 UA 会被判「未登录」，积分任务统一用这个
    const val POINTS_USER_AGENT =
        "QEUser/$VERSION (com.inonvation.campbox; build:1142; Android 14; userChannel:android_app; version:$VERSION) OkHttp/4.12.0"

    const val CONTENT_TYPE = "application/x-www-form-urlencoded;charset=UTF-8"
    const val ANDROID_SECRET = "nFU9pbG8YQoAe1kFh+E7eyrdlSLglwEJeA0wwHB1j5o="
    const val ALIPAY_SECRET = "Ew+ZSuppXZoA9YzBHgHmRvzt0Bw1CpwlQQtSl49QNhY="
}
