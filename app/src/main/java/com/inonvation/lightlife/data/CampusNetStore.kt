package com.inonvation.lightlife.data

import android.content.Context

/** 校园网认证账号存储（学号 + 密码，仅用于向校园网网关认证） */
class CampusNetStore(context: Context) {
    private val prefs = context.getSharedPreferences("campus_net", Context.MODE_PRIVATE)

    fun getUsername(): String = prefs.getString("username", "") ?: ""
    fun getPassword(): String = prefs.getString("password", "") ?: ""
    fun hasSaved(): Boolean = getUsername().isNotBlank() && getPassword().isNotBlank()

    fun save(username: String, password: String) {
        prefs.edit()
            .putString("username", username)
            .putString("password", password)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
