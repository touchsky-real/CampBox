package com.inonvation.campbox.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 一次检查更新得到的新版本信息；downloadUrl 已套用国内镜像前缀 */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val downloadUrl: String,
    val releaseUrl: String,
)

object UpdateChecker {
    private const val REPO = "touchsky-real/light-life"
    private const val RELEASE_PAGE = "https://github.com/$REPO/releases/latest"
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"

    // 国内可达的 GitHub 镜像（前缀式代理），按顺序尝试；末尾空串表示直连兜底
    private val MIRRORS = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://ghfast.top/",
        "",
    )

    /**
     * 检查最新 Release。
     * 成功返回 Result.success(info)；已是最新版本时 info 为 null；所有更新源均失败时返回 failure。
     */
    fun check(currentVersion: String): Result<UpdateInfo?> = runCatching {
        var lastError: Exception? = null
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        for (mirror in MIRRORS) {
            try {
                val url = if (mirror.isEmpty()) LATEST_API else "$mirror$LATEST_API"
                val body = client.newCall(
                    Request.Builder().url(url).header("User-Agent", "CampBox").build(),
                ).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    resp.body?.string().orEmpty()
                }
                val json = JSONObject(body)
                val tag = json.optString("tag_name").removePrefix("v").trim()
                if (tag.isEmpty()) error("响应缺少版本号")
                if (!isNewerVersion(tag, currentVersion)) return@runCatching null
                val assets = json.optJSONArray("assets").let { arr ->
                    if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                }.filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
                val apkUrl = assets.firstOrNull { it.optString("name").startsWith("CampBox") }
                    ?.optString("browser_download_url")
                    ?: assets.firstOrNull()?.optString("browser_download_url").orEmpty()
                return@runCatching UpdateInfo(
                    version = tag,
                    notes = json.optString("body").trim(),
                    downloadUrl = if (mirror.isEmpty()) apkUrl else "$mirror$apkUrl",
                    releaseUrl = RELEASE_PAGE,
                )
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("所有更新源均不可用")
    }
}

/** 版本号比较：candidate 比 current 新时返回 true；容忍 v 前缀，按「.」分段取数字前缀比较 */
internal fun isNewerVersion(candidate: String, current: String): Boolean {
    val c = candidate.trimStart('v', 'V').split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val u = current.trimStart('v', 'V').split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(c.size, u.size)) {
        val a = c.getOrElse(i) { 0 }
        val b = u.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}
