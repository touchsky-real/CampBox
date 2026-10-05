package com.inonvation.campbox.ui.campus

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.inonvation.campbox.data.CAMPUS_PORTAL_URL
import com.inonvation.campbox.data.campusPortalAutofillScript
import com.inonvation.campbox.data.isOfficialCampusPortal

/** 独立 :campus_portal 进程：网页绑定 Wi-Fi，不改变主 App 的网络路由。 */
class CampusPortalActivity : ComponentActivity() {
    private var browser: WebView? = null
    private var previousNetwork: Network? = null
    private var networkBound = false
    private var portalUrl = CAMPUS_PORTAL_URL
    private lateinit var status: TextView
    private val connectivity get() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        portalUrl = intent.getStringExtra(EXTRA_URL)?.takeIf(::isOfficialCampusPortal) ?: CAMPUS_PORTAL_URL
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val controls = LinearLayout(this)
        controls.addView(Button(this).apply {
            text = "返回 App"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(Button(this).apply {
            text = "重新加载"
            setOnClickListener { browser?.loadUrl(portalUrl) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(controls)
        status = TextView(this).apply {
            text = "官方校园网登录 · 正在使用校园 Wi-Fi 打开页面…"
            setTextColor(Color.DKGRAY)
            setPadding(24, 12, 24, 12)
        }
        root.addView(status)
        setContentView(root)

        val wifi = connectivity.allNetworks.firstOrNull {
            val caps = connectivity.getNetworkCapabilities(it)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        if (wifi == null) {
            status.text = "请先连接校园网 Wi-Fi，再返回 App 打开官方登录页。"
            return
        }
        previousNetwork = connectivity.boundNetworkForProcess
        networkBound = connectivity.bindProcessToNetwork(wifi)
        if (!networkBound) {
            status.text = "无法使用校园 Wi-Fi，请重新连接 Wi-Fi 后再试。"
            return
        }
        // 此 Activity 只在专用进程运行，避免与主进程争用 WebView 的数据目录。
        if (!webViewInitialized) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) WebView.setDataDirectorySuffix("campus_portal")
            webViewInitialized = true
        }
        val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        browser = WebView(this).apply {
            // 官方 getTermType() 根据 Android / Mobile 选择手机模板。
            // 保留本机 Chromium 版本，去掉内嵌浏览器标记，避免被当作兼容桌面页面。
            val mobileAgent = WebSettings.getDefaultUserAgent(this@CampusPortalActivity)
                .replace("; wv", "")
                .replace("Version/4.0 ", "")
            settings.userAgentString = if (mobileAgent.contains("Mobile")) mobileAgent else "$mobileAgent Mobile"
            // 官方页面存在 width=640 的 viewport；允许按页面宽度缩放，避免只显示左半边。
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            @Suppress("DEPRECATION")
            settings.saveFormData = false
            webViewClient = object : WebViewClient() {
                private var pageFailed = false

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    pageFailed = false
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (isOfficialCampusPortal(request.url.toString())) return false
                    if (request.isForMainFrame && request.url.host == "www.msftconnecttest.com" && request.url.path == "/redirect") {
                        // 官方登录后的返回地址：回 App 用 Wi-Fi 探针确认，不能仅凭网页跳转报成功。
                        finish()
                        return true
                    }
                    status.text = "已阻止离开官方 HTTPS 登录页；可返回 App 重试。"
                    return true
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (pageFailed || !isOfficialCampusPortal(url) || !isOfficialCampusPortal(view.url)) return
                    if (username.isNotBlank() && password.isNotBlank()) {
                        view.evaluateJavascript(campusPortalAutofillScript(username, password), null)
                        status.text = "已尝试填入保存的账号密码，请确认后点网页的登录；完成后点「返回 App」。"
                    } else {
                        status.text = "请在官方页面输入校园网账号密码并登录，完成后点「返回 App」。"
                    }
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    pageFailed = true
                    status.text = "官方页面证书验证失败，未继续加载。请返回 App 检查网络或反馈错误。"
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        pageFailed = true
                        status.text = "官方页面加载失败（${error.errorCode}），请检查校园 Wi-Fi 后重新加载。"
                    }
                }
            }
            root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            loadUrl(portalUrl)
        }
    }

    override fun onDestroy() {
        browser?.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        browser = null
        if (networkBound) runCatching { connectivity.bindProcessToNetwork(previousNetwork) }
        super.onDestroy()
    }

    companion object {
        private var webViewInitialized = false
        private const val EXTRA_USERNAME = "campus_username"
        private const val EXTRA_PASSWORD = "campus_password"
        private const val EXTRA_URL = "campus_url"

        fun intent(context: Context, username: String, password: String, url: String): Intent =
            Intent(context, CampusPortalActivity::class.java)
                .putExtra(EXTRA_USERNAME, username)
                .putExtra(EXTRA_PASSWORD, password)
                .putExtra(EXTRA_URL, url.takeIf(::isOfficialCampusPortal) ?: CAMPUS_PORTAL_URL)
    }
}
