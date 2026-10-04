package com.inonvation.lightlife.data

import android.content.Context

/**
 * 全局稳定设备标识（模拟官方 OAID 角色）。
 *
 * 官方 App 所有请求头都带 deviceId（取 OAID），服务端以它做设备维度风控；
 * 本应用无法读 OAID，用首次生成的随机 ID 代替并持久化，保证：
 * 1. 同一安装内所有请求（主客户端 / 积分任务 / 登录）发同一个 ID
 * 2. 登录接口也携带，使会话与设备 ID 绑定一致，避免任务接口「未登录」误判
 */
object DeviceIdProvider {
    private const val PREFS_NAME = "device_identity"
    private const val KEY_DEVICE_ID = "device_id"

    @Volatile
    private var cached: String? = null

    fun deviceId(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrBlank()) {
                cached = existing
                return existing
            }
            val generated = java.util.UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            cached = generated
            return generated
        }
    }
}
