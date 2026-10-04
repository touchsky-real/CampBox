package com.inonvation.lightlife.data

import android.content.Context

/** 用户偏好存储：触感、积分抵扣、自动签到开关（SharedPreferences 文件名沿用历史名称，勿改） */
class UserPrefsStore(context: Context) {
    private val prefs = context.getSharedPreferences("points_task_state", Context.MODE_PRIVATE)

    // ── 设置开关 ──

    fun isHapticEnabled(): Boolean = prefs.getBoolean("haptic_enabled", true)
    fun setHapticEnabled(v: Boolean) { prefs.edit().putBoolean("haptic_enabled", v).apply() }

    fun isUsePointsForUnlockEnabled(): Boolean = prefs.getBoolean("use_points_unlock", true)
    fun setUsePointsForUnlockEnabled(v: Boolean) { prefs.edit().putBoolean("use_points_unlock", v).apply() }

    /** 打开 App 时自动签到（默认开启） */
    fun isAutoSignInEnabled(): Boolean = prefs.getBoolean("auto_sign_in", true)
    fun setAutoSignInEnabled(v: Boolean) { prefs.edit().putBoolean("auto_sign_in", v).apply() }
}
