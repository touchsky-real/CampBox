package com.inonvation.campbox.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inonvation.campbox.data.ApiConfig
import com.inonvation.campbox.data.AppRepository
import com.inonvation.campbox.data.CampusNetResult
import com.inonvation.campbox.data.CampusNetRunner
import com.inonvation.campbox.data.CampusNetStore
import com.inonvation.campbox.data.DEFAULT_QUICK_LINKS
import com.inonvation.campbox.data.PRESET_LINK_COUNT
import com.inonvation.campbox.data.DeviceItem
import com.inonvation.campbox.data.DeviceIdProvider
import com.inonvation.campbox.data.SignInRunner
import com.inonvation.campbox.data.UserPrefsStore
import com.inonvation.campbox.data.QuickLinkStore
import com.inonvation.campbox.data.TokenExpiredException
import com.inonvation.campbox.data.UnlockException
import com.inonvation.campbox.data.UpdateChecker
import com.inonvation.campbox.ui.auth.AuthController
import com.inonvation.campbox.ui.qzxy.QzxyController
import com.inonvation.campbox.ui.qzxy.QzxyAccountPage
import com.inonvation.campbox.ui.qzxy.QzxyUiState
import com.inonvation.campbox.data.qzxy.QzxyBluetoothScanner
import com.inonvation.campbox.data.qzxy.QzxyNearbyDevice
import com.inonvation.campbox.data.qzxy.QzxyRepository
import com.inonvation.campbox.ui.theme.ColorTheme
import com.inonvation.campbox.ui.theme.ThemeMode
import com.inonvation.campbox.ui.theme.ThemePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class UiEvent {
    data object OpenCampusPortal : UiEvent()
    data object RequestWaterLocation : UiEvent()
    data class Toast(val message: String) : UiEvent()
    data class Error(val message: String) : UiEvent()
}

class AppViewModel(
    application: Application,
    private val repository: AppRepository,
    private val appVersion: String = "",
    private val userPrefsStore: UserPrefsStore? = null,
    private val themePreferences: ThemePreferences? = null,
    private val quickLinkStore: QuickLinkStore? = null,
    private val qzxyRepository: QzxyRepository,
) : ViewModel() {
    private val context: Context = application.applicationContext
    private val unlockMutex = kotlinx.coroutines.sync.Mutex()
    private var unlockJob: Job? = null
    private var pendingWaterPermissionDevice: DeviceItem? = null
    private var devicesLoadAttempted = false

    // ── State ──
    private val _state = MutableStateFlow(
        AppUiState(
            hasToken = repository.localToken() != null,
            phone = repository.readPhone() ?: "",
            orderHistory = repository.orderHistory(),
            appVersion = appVersion,
        ),
    )
    val state: StateFlow<AppUiState> = _state

    /** 顶层主题只取外观两项，单独成流，避免高频状态变化引发整页重组 */
    val appearance: StateFlow<Pair<ThemeMode, ColorTheme>> = state
        .map { it.themeMode to it.colorTheme }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, _state.value.let { it.themeMode to it.colorTheme })

    // 公开 quickLinkStore 供快捷方式图标使用
    fun getQuickLinkStore(): QuickLinkStore? = quickLinkStore

    // ── 一次性事件通道（Toast / Error）──
    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private fun showToast(message: String) { _events.trySend(UiEvent.Toast(message)) }
    private fun showError(message: String) { _events.trySend(UiEvent.Error(friendlyErrorMessage(message))) }

    private fun friendlyErrorMessage(raw: String): String {
        val lower = raw.lowercase()
        return when {
            "unknownhostexception" in lower || "unable to resolve host" in lower -> "网络连接失败，请检查网络设置"
            "connectexception" in lower || "failed to connect" in lower -> "无法连接服务器，请稍后再试"
            "sockettimeoutexception" in lower || "timeout" in lower -> "请求超时，请检查网络后重试"
            "sslhandshakeexception" in lower -> "网络安全验证失败"
            "eofexception" in lower -> "数据传输中断，请重试"
            "请先登录" in raw -> "请先登录"
            "500" in raw || "502" in raw || "503" in raw || "504" in raw -> "服务器繁忙，请稍后再试"
            " 401 " in raw || " 403 " in raw || "http 401" in lower || "http 403" in lower -> "请求被拒绝，请检查权限"
            else -> raw.ifBlank { "操作失败，请重试" }
        }
    }

    // ── 控制器 ──
    private val authController: AuthController by lazy {
        AuthController(
            state = state,
            updateState = { _state.update(it) },
            scope = viewModelScope,
            repository = repository,
            userPrefsStore = userPrefsStore,
            onAuthSuccess = {
                refreshBalance()
                refreshDevices()
                refreshTodayWater()
            },
            showToast = ::showToast,
            showError = ::showError,
        )
    }

    private val signInRunner = SignInRunner({ repository.localToken() }, context) { deviceId }

    // 与主客户端共享的稳定设备标识（模拟官方 OAID，登录/请求/签到统一使用）
    private val deviceId by lazy { DeviceIdProvider.deviceId(context) }

    // ── 校园网认证 ──
    private val campusNetStore by lazy { CampusNetStore(context) }
    private val campusNetRunner by lazy { CampusNetRunner(context) }

    init {
        _state.update {
            it.copy(
                campusUsername = campusNetStore.getUsername(),
                campusPassword = campusNetStore.getPassword(),
                campusHasSaved = campusNetStore.hasSaved(),
            )
        }
    }

    fun updateCampusUsername(v: String) = _state.update { it.copy(campusUsername = v) }
    fun updateCampusPassword(v: String) = _state.update { it.copy(campusPassword = v) }
    fun toggleCampusPasswordVisible() =
        _state.update { it.copy(campusPasswordVisible = !it.campusPasswordVisible) }

    fun campusNetLogin(silent: Boolean = false) = viewModelScope.launch {
        if (state.value.campusLoggingIn) return@launch
        val username = state.value.campusUsername.trim()
        val password = state.value.campusPassword
        if (username.isBlank() || password.isBlank()) {
            if (!silent) showError("请输入校园网账号和密码")
            return@launch
        }
        campusNetStore.save(username, password)
        _state.update {
            it.copy(
                campusLoggingIn = true,
                campusLog = if (silent) listOf("启动自动连接...") else listOf("开始认证..."),
                campusLastSuccess = null,
            )
        }
        val log: suspend (String) -> Unit = { msg ->
            _state.update { s -> s.copy(campusLog = (s.campusLog + msg).takeLast(30)) }
        }
        val result = runCatching { campusNetRunner.login(username, password, log, auto = silent) }
            .getOrElse { e ->
                if (e is CancellationException) {
                    _state.update { it.copy(campusLoggingIn = false) }
                    throw e
                }
                CampusNetResult.Failure("认证异常（${e.javaClass.simpleName}），请检查 Wi-Fi 后重试")
            }
        val ok = result is CampusNetResult.Success
        val skipped = result is CampusNetResult.Skipped
        _state.update { s ->
            s.copy(
                campusLoggingIn = false,
                // 跳过（没连 Wi-Fi / 非校园网）保持上次状态，不标成功也不标失败
                campusLastSuccess = if (skipped) s.campusLastSuccess else ok,
                campusHasSaved = campusNetStore.hasSaved(),
            )
        }
        when {
            ok -> showToast("校园网已连通")
            result is CampusNetResult.Failure -> {
                if (silent) showError(result.reason) else {
                    log(result.reason)
                    log("自动认证未完成，打开官方网页登录备用入口")
                    openCampusPortal()
                }
            }
            // Skipped：原因已写入认证日志，不弹错误打扰
        }
    }

    fun campusPortalUrl(): String = campusNetRunner.officialPortalUrl

    fun openCampusPortal() {
        if (!state.value.campusLoggingIn) _events.trySend(UiEvent.OpenCampusPortal)
    }

    fun verifyCampusInternetAfterPortal() = viewModelScope.launch {
        if (state.value.campusLoggingIn) return@launch
        _state.update { it.copy(campusLoggingIn = true) }
        try {
            val online = campusNetRunner.verifyWifiInternet()
            _state.update { it.copy(campusLastSuccess = online,
                campusLog = (it.campusLog + if (online) "官方网页登录后，Wi-Fi 外网已连通"
                    else "尚未检测到 Wi-Fi 外网连通，可继续官方网页登录或重试连接").takeLast(30)) }
            if (online) showToast("校园网已连通")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(campusLastSuccess = false,
                campusLog = (it.campusLog + "联网检查异常（${e.javaClass.simpleName}）").takeLast(30)) }
        } finally {
            _state.update { it.copy(campusLoggingIn = false) }
        }
    }

    /** 启动时自动连接校园网：开关开启 + 已保存账号才执行，失败按原因提示 */
    fun autoCampusNetOnLaunch() {
        if (!state.value.autoCampusNetEnabled) return
        if (!campusNetStore.hasSaved()) return
        if (state.value.campusLoggingIn) return
        campusNetLogin(silent = true)
    }

    fun toggleAutoCampusNet() {
        val v = !state.value.autoCampusNetEnabled
        userPrefsStore?.setAutoCampusNetEnabled(v)
        _state.update { it.copy(autoCampusNetEnabled = v) }
        if (v) showToast("启动时将自动连接校园网") else showToast("已关闭启动自动连校园网")
    }

    fun clearCampusCredentials() {
        campusNetStore.clear()
        _state.update { it.copy(campusUsername = "", campusPassword = "", campusHasSaved = false, campusLog = emptyList()) }
    }

    // ── 淋浴（趣智校园）控制器 ──
    private fun updateQzxy(reduce: (QzxyUiState) -> QzxyUiState) {
        _state.update { it.copy(qzxy = reduce(it.qzxy)) }
    }

    private val qzxyController: QzxyController by lazy {
        QzxyController(
            state = state,
            updateQzxy = ::updateQzxy,
            scope = viewModelScope,
            repository = qzxyRepository,
            scanner = QzxyBluetoothScanner(context),
            showToast = ::showToast,
            showError = ::showError,
        )
    }

    private var pendingShortcutRequest: DeviceShortcutRequest? = null
    private var unlockTimerJob: Job? = null

    // ── Init ──
    init {
        userPrefsStore?.let {
            _state.update { s -> s.copy(
                hapticEnabled = it.isHapticEnabled(),
                autoSignInEnabled = it.isAutoSignInEnabled(),
                autoCampusNetEnabled = it.isAutoCampusNetEnabled(),
                usePointsForUnlock = it.isUsePointsForUnlockEnabled(),
            ) }
        }
        themePreferences?.let {
            _state.update { s -> s.copy(
                themeMode = it.getThemeMode(),
                colorTheme = it.getColorTheme(),
            ) }
        }
        quickLinkStore?.let {
            _state.update { s -> s.copy(quickLinks = it.getLinks(), quickLinksEnabled = it.isEnabled()) }
        }
        _state.update { it.copy(signInDoneToday = signInRunner.isSignedInToday()) }
        if (repository.localToken() != null) {
            refreshDevices()
            refreshBalance()
            refreshTodayWater()
        }
        // 打开 App 时自动签到
        autoSignInOnLaunch()
        // 打开 App 时自动连接校园网
        autoCampusNetOnLaunch()
        // 恢复趣智校园登录态与进行中的洗澡订单
        qzxyController.restoreSession()
    }

    // ── 登录 ──
    fun updatePhone(value: String) = authController.updatePhone(value)
    fun updateCode(value: String) = authController.updateCode(value)
    fun toggleTokenLogin() = authController.toggleTokenLogin()
    fun updateTokenLoginInput(value: String) = authController.updateTokenLoginInput(value)
    fun toggleTokenLoginVisibility() = authController.toggleTokenLoginVisibility()
    fun loginWithToken() = authController.loginWithToken()
    fun sendCode() = authController.sendCode()
    fun login() = authController.login()
    fun logout() {
        pendingWaterPermissionDevice = null
        unlockJob?.cancel()
        authController.logout()
        _state.update { it.copy(unlocking = false, unlockElapsedSeconds = 0) }
    }

    // ── 签到 ──
    /** 启动时自动签到：开关开启 + 已登录 + 今日未签到才执行，复用手动签到的全部保护 */
    fun autoSignInOnLaunch() {
        if (!state.value.autoSignInEnabled) return
        if (!state.value.hasToken) return
        if (signInRunner.isSignedInToday()) return
        if (state.value.signingIn) return
        signInNow()
    }

    fun signInNow() = viewModelScope.launch {
        if (!state.value.hasToken) {
            showError("请先登录")
            return@launch
        }
        if (state.value.signingIn) return@launch
        _state.update { it.copy(signingIn = true) }
        runCatching {
            signInRunner.signIn(ApiConfig.USER_AGENT)
        }.onSuccess { result ->
            _state.update { it.copy(signInDoneToday = true, signingIn = false) }
            showToast(result.message)
            refreshBalance()
        }.onFailure { e ->
            _state.update { it.copy(signingIn = false) }
            if (e is CancellationException) throw e
            if (e is TokenExpiredException) {
                authController.handleTokenExpired()
            } else {
                showError(e.message ?: "签到失败")
            }
        }
    }

    // ── 设备 / 余额 ──
    private fun handleApiError(error: Throwable, fallbackMessage: String = "操作失败"): Boolean {
        if (error is CancellationException) throw error
        return if (error is TokenExpiredException) {
            authController.handleTokenExpired()
            true
        } else {
            showError(error.message ?: fallbackMessage)
            false
        }
    }

    fun refreshDevices() = viewModelScope.launch {
        if (!state.value.hasToken) return@launch
        if (state.value.loadingDevices) return@launch
        runCatching {
            _state.update { it.copy(loadingDevices = true) }
            repository.latestDevices()
        }.onSuccess { devices ->
            _state.update { it.copy(devices = devices, loadingDevices = false) }
            devicesLoadAttempted = false
            consumePendingShortcut(devices)
        }.onFailure {
            _state.update { it.copy(loadingDevices = false) }
            if (!handleApiError(it, "查询历史设备失败")) {
                devicesLoadAttempted = true
            }
        }
    }

    fun refreshBalance(silent: Boolean = false) = viewModelScope.launch {
        if (state.value.loadingBalance) return@launch
        if (!state.value.hasToken) return@launch
        runCatching {
            _state.update { it.copy(loadingBalance = true) }
            repository.queryBalance()
        }.onSuccess { balance ->
            _state.update { it.copy(balance = balance, loadingBalance = false) }
        }.onFailure {
            _state.update { it.copy(loadingBalance = false) }
            if (it is CancellationException) throw it
            if (!silent || it is TokenExpiredException) handleApiError(it, "查询资产失败")
        }
    }

    // ── 解锁 ──
    fun resolveWaterCode(raw: String) = viewModelScope.launch {
        if (state.value.waterScanLoading || state.value.unlocking) return@launch
        if (!state.value.hasToken) {
            showError("请先登录胖乖账号")
            return@launch
        }
        _state.update { it.copy(waterScanLoading = true, scannedWaterDevice = null, waterScanError = null) }
        try {
            val device = repository.resolveWaterCode(raw)
            if (state.value.hasToken) _state.update { it.copy(scannedWaterDevice = device) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.inonvation.campbox.data.WaterScanException) {
            _state.update { it.copy(waterScanError = "${e.message}\n\n步骤：${e.step}\n错误码：${e.serverCode ?: "未知"}\n接口协议：${ApiConfig.VERSION} / ${ApiConfig.VERSION_CODE}") }
        } catch (e: Exception) {
            handleApiError(e, "识别设备失败，请重新扫描")
        } finally {
            _state.update { it.copy(waterScanLoading = false) }
        }
    }

    fun dismissScannedWaterDevice() {
        _state.update { it.copy(scannedWaterDevice = null) }
    }

    fun dismissWaterScanError() {
        _state.update { it.copy(waterScanError = null) }
    }

    fun selectScannedWaterDevice() {
        val device = state.value.scannedWaterDevice ?: return
        _state.update { it.copy(scannedWaterDevice = null,
            devices = listOf(device) + it.devices.filterNot { existing ->
                (existing.goodsId ?: existing.id) == device.goodsId
            }) }
    }

    fun onWaterLocationPermissionResult(granted: Boolean) {
        val device = pendingWaterPermissionDevice ?: return
        pendingWaterPermissionDevice = null
        if (granted) unlock(device)
        else showError("开水需要定位权限，请在系统设置中允许后重试")
    }

    fun unlock(device: DeviceItem) = viewModelScope.launch {
        if (state.value.waterScanLoading || state.value.unlocking || !unlockMutex.tryLock()) return@launch
        try {
            if (!state.value.hasToken) {
                showError("请先登录")
                return@launch
            }
            val locationGranted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            if (!locationGranted) {
                if (pendingWaterPermissionDevice == null) {
                    pendingWaterPermissionDevice = device
                    _events.trySend(UiEvent.RequestWaterLocation)
                }
                return@launch
            }
            unlockJob = coroutineContext[Job]
            _state.update {
                it.copy(unlocking = true, unlockingDeviceId = device.goodsName.ifBlank { device.id },
                    unlockStatus = "准备解锁", unlockFlowState = UnlockFlowState.PreChecking(),
                    unlockElapsedSeconds = 0, unlockFlowHidden = false)
            }
            unlockTimerJob = launch {
                while (isActive) {
                    delay(1000)
                    _state.update {
                        if (it.unlockFlowState is UnlockFlowState.Working)
                            it.copy(unlockElapsedSeconds = it.unlockElapsedSeconds + 1) else it
                    }
                }
            }
            val result = repository.unlockDevice(
                device, usePoints = state.value.usePointsForUnlock,
                onStarted = {
                    _state.update { it.copy(unlockFlowState = UnlockFlowState.Working("开水指令已受理")) }
                },
            ) { step ->
                _state.update {
                    it.copy(unlockStatus = step,
                        unlockFlowState = if (it.unlockFlowState is UnlockFlowState.Working)
                            UnlockFlowState.Working(step, it.unlockElapsedSeconds)
                        else UnlockFlowState.PreChecking(step))
                }
            }
            val history = repository.orderHistory()
            _state.update {
                it.copy(unlockStatus = null,
                    unlockFlowState = if (result.usageConfirmed) UnlockFlowState.Success(result)
                        else UnlockFlowState.Pending(result),
                    unlockElapsedSeconds = 0, unlockFlowHidden = false, orderHistory = history,
                    totalWaterCount = history.count { order -> order.usageConfirmed })
            }
            refreshBalance(silent = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TokenExpiredException) {
            _state.update { it.copy(unlockFlowState = UnlockFlowState.Idle, unlockStatus = null) }
            authController.handleTokenExpired()
        } catch (e: Exception) {
            val diag = (e as? UnlockException)?.diagnosis
            val failure = if (diag != null)
                UnlockFlowState.Failed(diag.primaryReason, diag.step, diag.rawError, diag.suggestions)
            else UnlockFlowState.Failed(friendlyErrorMessage(e.message ?: "未知错误"), "未知", e.message ?: "")
            _state.update { it.copy(unlockStatus = null, unlockFlowState = failure, unlockFlowHidden = false) }
        } finally {
            unlockTimerJob?.cancel()
            unlockJob = null
            _state.update { it.copy(unlocking = false, unlockElapsedSeconds = 0) }
            unlockMutex.unlock()
        }
    }

    fun dismissUnlockFlow() {
        if (state.value.unlocking) {
            dismissUnlockAnimation()
            return
        }
        _state.update { it.copy(unlockFlowState = UnlockFlowState.Idle, unlockElapsedSeconds = 0, unlockingDeviceId = null) }
    }

    fun dismissUnlockAnimation() {
        // 隐藏界面不能取消后台状态确认或改变计时。
        _state.update { it.copy(unlockFlowHidden = true) }
    }

    fun openDeviceShortcut(request: DeviceShortcutRequest) {
        pendingShortcutRequest = request
        if (!state.value.hasToken) {
            showError("请先登录后再使用桌面设备快捷方式")
            return
        }
        val devices = state.value.devices
        if (devices.isEmpty()) {
            refreshDevices()
        } else {
            consumePendingShortcut(devices)
        }
    }

    private fun consumePendingShortcut(devices: List<DeviceItem>) {
        val request = pendingShortcutRequest ?: return
        val target = devices.firstOrNull { device ->
            (!request.goodsId.isNullOrBlank() && device.goodsId == request.goodsId) ||
                (!request.id.isNullOrBlank() && device.id == request.id) ||
                (!request.goodsName.isNullOrBlank() && device.goodsName == request.goodsName)
        }
        pendingShortcutRequest = null
        if (target == null) {
            showError("未找到对应的历史设备，请刷新设备列表后重试")
            return
        }
        unlock(target)
    }

    // ── 快捷方式 ──
    /**
     * 更新快捷方式。预设槽位（前 PRESET_LINK_COUNT 个）一旦内容被改动，
     * 就脱离预设：保存时清掉 presetIndex（图标也不再强制用预设图标），
     * 该槽位变为普通自定义槽，可删除。
     */
    fun updateQuickLink(index: Int, name: String, url: String, packageName: String, presetIndex: Int = -1) {
        val isPresetSlot = index < PRESET_LINK_COUNT
        val preset = DEFAULT_QUICK_LINKS.getOrNull(index)
        val contentMatchesPreset = isPresetSlot && preset != null &&
            name == preset.name && url == preset.url && packageName == preset.packageName
        val effectivePreset = if (isPresetSlot && contentMatchesPreset) index else -1

        quickLinkStore?.updateLink(index, name, url, packageName, effectivePreset)
        if (effectivePreset >= 0 && name.isNotBlank()) {
            quickLinkStore?.savePresetIcon(index, effectivePreset)
        }
        quickLinkStore?.let {
            _state.update { s -> s.copy(quickLinks = it.getLinks()) }
        }
    }

    /** 删除任意槽位：预设槽清空后仍可被「重置为默认」找回；自定义槽删除后后续条目（含图标）前移补位 */
    fun deleteQuickLink(index: Int) {
        val links = _state.value.quickLinks.toMutableList()
        if (index !in links.indices) return
        quickLinkStore?.removeIcon(index)
        if (index < PRESET_LINK_COUNT) {
            // 预设槽被改为自定义后允许删除，清空内容即可
            quickLinkStore?.updateLink(index, "", "", "", -1)
        } else {
            quickLinkStore?.shiftLinksAfterDelete(index)
        }
        quickLinkStore?.let {
            _state.update { s -> s.copy(quickLinks = it.getLinks()) }
        }
    }

    fun swapQuickLinks(index1: Int, index2: Int) {
        quickLinkStore?.swapLinks(index1, index2)
        quickLinkStore?.let {
            _state.update { s -> s.copy(quickLinks = it.getLinks()) }
        }
    }

    fun toggleQuickLinksEnabled() {
        val v = !_state.value.quickLinksEnabled
        quickLinkStore?.setEnabled(v)
        _state.update { it.copy(quickLinksEnabled = v) }
    }

    fun showQuickLinksSettings() { _state.update { it.copy(showQuickLinksSettings = true) } }
    fun dismissQuickLinksSettings() { _state.update { it.copy(showQuickLinksSettings = false) } }

    fun resetQuickLinksToDefault() {
        DEFAULT_QUICK_LINKS.forEachIndexed { index, link ->
            quickLinkStore?.updateLink(index, link.name, link.url, link.packageName, link.presetIndex)
            quickLinkStore?.savePresetIcon(index, link.presetIndex)
        }
        for (i in DEFAULT_QUICK_LINKS.size until 9) {
            quickLinkStore?.removeIcon(i)
            quickLinkStore?.updateLink(i, "", "", "", -1)
        }
        quickLinkStore?.let {
            _state.update { s -> s.copy(quickLinks = it.getLinks()) }
        }
    }

    fun setQuickLinkIcon(index: Int, uri: android.net.Uri) {
        val success = quickLinkStore?.saveIcon(index, uri) ?: false
        if (success) {
            quickLinkStore?.let {
                _state.update { s -> s.copy(quickLinks = it.getLinks()) }
            }
            showToast("图标已设置")
        } else {
            showError("设置图标失败")
        }
    }

    fun removeQuickLinkIcon(index: Int) {
        quickLinkStore?.removeIcon(index)
        quickLinkStore?.let {
            _state.update { s -> s.copy(quickLinks = it.getLinks()) }
        }
        showToast("图标已移除")
    }
    // ── 淋浴（趣智校园）──
    fun qzxyShowLogin() = qzxyController.showLoginSheet()
    fun qzxyDismissLogin() = qzxyController.dismissLoginSheet()
    fun qzxyUpdatePhone(value: String) = qzxyController.updatePhone(value)
    fun qzxyUpdateSmsCode(value: String) = qzxyController.updateSmsCode(value)
    fun qzxySetSmsLogin(enabled: Boolean) = qzxyController.setSmsLogin(enabled)
    fun qzxySendLoginSms() = qzxyController.sendLoginSms()
    fun qzxyUpdatePassword(value: String) = qzxyController.updatePassword(value)
    fun qzxyTogglePasswordVisible() = qzxyController.togglePasswordVisibility()
    fun qzxyLogin() = qzxyController.login()
    fun qzxyLogout() = qzxyController.logout()
    fun qzxyShowLogoutConfirm() = qzxyController.showLogoutConfirm()
    fun qzxyDismissLogoutConfirm() = qzxyController.dismissLogoutConfirm()
    fun qzxyRefreshWallet() = qzxyController.refreshWallet()
    fun qzxyLoadUseCode() = qzxyController.loadUseCode()
    fun qzxyShowWallet() = qzxyController.showAccountPage(QzxyAccountPage.Wallet)
    fun qzxyShowUseCode() = qzxyController.showAccountPage(QzxyAccountPage.UseCode)
    fun qzxyDismissAccount() = qzxyController.dismissAccountPage()
    fun qzxyGenerateUseCode() = qzxyController.generateUseCode()
    fun qzxyClaimUseCode() = qzxyController.claimUseCode()
    fun qzxySetUseCodeEnabled(enabled: Boolean) = qzxyController.setUseCodeEnabled(enabled)
    fun qzxyDiscardUseCodeCandidate() = qzxyController.discardUseCodeCandidate()
    fun qzxyStartScan() = qzxyController.startScan()
    fun qzxyStopScan() = qzxyController.stopScan()
    fun qzxyOnScanPermissionDenied() = qzxyController.onScanPermissionDenied()
    fun qzxySelectDevice(device: QzxyNearbyDevice) = qzxyController.selectDevice(device)
    fun qzxySetDevicePicker(open: Boolean) = qzxyController.setDevicePicker(open)
    fun qzxyRefreshSelectedDevice() = qzxyController.refreshSelectedDevice()
    fun qzxyShowManualMacDialog() = qzxyController.showManualMacDialog()
    fun qzxyDismissManualMacDialog() = qzxyController.dismissManualMacDialog()
    fun qzxyUpdateManualMac(value: String) = qzxyController.updateManualMac(value)
    fun qzxySubmitManualMac() = qzxyController.submitManualMac()
    fun qzxyStartShower() = qzxyController.startShower()
    fun qzxyStopShower() = qzxyController.stopShower()
    fun qzxyDismissShowerFlow() = qzxyController.dismissShowerFlow()

    // ── 统计 ──
    fun refreshTodayWater() {
        val count = repository.orderHistory().count { it.usageConfirmed }
        _state.update { it.copy(totalWaterCount = count) }
    }

    fun showOrderHistory() {
        _state.update { it.copy(showOrderHistory = true, orderHistory = repository.orderHistory()) }
    }
    fun dismissOrderHistory() { _state.update { it.copy(showOrderHistory = false) } }

    // ── 检查更新 ──
    fun checkUpdate() {
        if (_state.value.updateChecking) return
        _state.update { it.copy(updateChecking = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = UpdateChecker.check(_state.value.appVersion)
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { info ->
                        _state.update { it.copy(updateChecking = false, updateInfo = info) }
                        if (info == null) showToast("已是最新版本")
                    },
                    onFailure = {
                        _state.update { it.copy(updateChecking = false) }
                        showToast("检查更新失败，请检查网络后重试")
                    },
                )
            }
        }
    }

    fun dismissUpdateDialog() { _state.update { it.copy(updateInfo = null) } }

    // ── 设置 ──
    fun showSettings() { _state.update { it.copy(showSettings = true) } }
    fun dismissSettings() { _state.update { it.copy(showSettings = false) } }

    fun updateThemeMode(mode: ThemeMode) {
        themePreferences?.setThemeMode(mode)
        _state.update { it.copy(themeMode = mode) }
    }
    fun updateColorTheme(theme: ColorTheme) {
        themePreferences?.setColorTheme(theme)
        _state.update { it.copy(colorTheme = theme) }
    }
    fun toggleHaptic() {
        val v = !state.value.hapticEnabled
        userPrefsStore?.setHapticEnabled(v)
        _state.update { it.copy(hapticEnabled = v) }
    }
    fun toggleAutoSignIn() {
        val v = !state.value.autoSignInEnabled
        userPrefsStore?.setAutoSignInEnabled(v)
        _state.update { it.copy(autoSignInEnabled = v) }
    }
    fun toggleUsePointsForUnlock() {
        val v = !state.value.usePointsForUnlock
        userPrefsStore?.setUsePointsForUnlockEnabled(v)
        _state.update { it.copy(usePointsForUnlock = v) }
        showToast(if (v) "开水将使用积分抵扣" else "开水不使用积分抵扣")
    }

    fun showCurrentToken() {
        val token = repository.localToken()?.takeIf { it.isNotBlank() }
        _state.update { it.copy(tokenDialogText = token ?: "当前未登录，暂无 Token") }
    }
    fun dismissCurrentToken() { _state.update { it.copy(tokenDialogText = null) } }

    fun showCurrentDeviceInfo() {
        _state.update { it.copy(deviceInfoDialogText = ApiConfig.USER_AGENT) }
    }
    fun dismissCurrentDeviceInfo() { _state.update { it.copy(deviceInfoDialogText = null) } }

    fun showLogoutConfirm() { _state.update { it.copy(showLogoutConfirm = true) } }
    fun dismissLogoutConfirm() { _state.update { it.copy(showLogoutConfirm = false) } }

    // ── Lifecycle ──
    override fun onCleared() {
        unlockTimerJob?.cancel()
        super.onCleared()
    }
}

class AppViewModelFactory(
    private val application: Application,
    private val repository: AppRepository,
    private val appVersion: String = "",
    private val userPrefsStore: UserPrefsStore? = null,
    private val themePreferences: ThemePreferences? = null,
    private val quickLinkStore: QuickLinkStore? = null,
    private val qzxyRepository: QzxyRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AppViewModel(application, repository, appVersion, userPrefsStore, themePreferences, quickLinkStore, qzxyRepository) as T
    }
}
