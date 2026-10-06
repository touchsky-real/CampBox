package com.inonvation.campbox.data

import android.content.Context

/** 用户偏好存储：触感、积分抵扣、自动签到/校园网开关（SharedPreferences 文件名沿用历史名称，勿改） */
class UserPrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("points_task_state", Context.MODE_PRIVATE)

    // ── 设置开关 ──

    fun isHapticEnabled(): Boolean = prefs.getBoolean("haptic_enabled", true)
    fun setHapticEnabled(v: Boolean) { prefs.edit().putBoolean("haptic_enabled", v).apply() }

    fun isUsePointsForUnlockEnabled(): Boolean = prefs.getBoolean("use_points_unlock", true)
    fun setUsePointsForUnlockEnabled(v: Boolean) { prefs.edit().putBoolean("use_points_unlock", v).apply() }

    /** 打开 App 时自动执行每日签到 */
    fun isAutoSignInEnabled(): Boolean = prefs.getBoolean("auto_sign_in", true)
    fun setAutoSignInEnabled(v: Boolean) { prefs.edit().putBoolean("auto_sign_in", v).apply() }

    /** 打开 App 时自动连接校园网（默认关闭：需要校园网账号密码，且多数场景非必需） */
    fun isAutoCampusNetEnabled(): Boolean = prefs.getBoolean("auto_campus_net", false)
    fun setAutoCampusNetEnabled(v: Boolean) { prefs.edit().putBoolean("auto_campus_net", v).apply() }
}
