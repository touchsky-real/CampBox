package com.inonvation.campbox.ui

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
import com.inonvation.campbox.data.DeviceIdProvider
import com.inonvation.campbox.data.DeviceItem
import com.inonvation.campbox.data.PointsTaskRunner
import com.inonvation.campbox.data.UserPrefsStore
import com.inonvation.campbox.data.QuickLinkStore
import com.inonvation.campbox.data.SignInRunner
import com.inonvation.campbox.data.TaskCancelledException
import com.inonvation.campbox.data.TokenExpiredException
import com.inonvation.campbox.data.UnlockException
import com.inonvation.campbox.data.UpdateChecker
import com.inonvation.campbox.ui.auth.AuthController
import com.inonvation.campbox.ui.qzxy.QzxyController
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class UiEvent {
    data object OpenCampusPortal : UiEvent()
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

    // 与主客户端共享的稳定设备标识（模拟官方 OAID，登录/请求/积分任务统一使用）
    private val deviceId by lazy { DeviceIdProvider.deviceId(context) }

    // ── 积分任务 ──
    private var pointsRunner: PointsTaskRunner? = null
    private var pointsJob: Job? = null

    fun startPointsTask() {
        if (state.value.pointsRunning) return
        if (!state.value.hasToken) {
            showError("请先登录")
            return
        }
        val runner = PointsTaskRunner({ repository.localToken() }, context) { deviceId }
        pointsRunner = runner
        _state.update { it.copy(pointsRunning = true, pointsPaused = false, pointsLog = listOf("任务启动...")) }
        pointsJob = viewModelScope.launch {
            val log: suspend (String) -> Unit = { msg ->
                _state.update { s -> s.copy(pointsLog = (s.pointsLog + msg).takeLast(200)) }
            }
            try {
                runner.run(ApiConfig.POINTS_USER_AGENT, log)
                _state.update { it.copy(pointsRunning = false) }
                refreshBalance()
                _state.update { s -> s.copy(signInDoneToday = true) }
                showToast("积分任务完成")
            } catch (e: TaskCancelledException) {
                _state.update { s -> s.copy(pointsRunning = false, pointsLog = s.pointsLog + "任务已停止") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: "任务异常"
                if (msg == "token") {
                    _state.update { it.copy(pointsRunning = false) }
                    authController.handleTokenExpired()
                } else {
                    _state.update { s -> s.copy(pointsRunning = false, pointsLog = s.pointsLog + "任务失败：$msg") }
                }
            }
        }
    }

    fun stopPointsTask() {
        pointsRunner?.cancelled = true
        pointsJob?.cancel()
        _state.update { s -> s.copy(pointsRunning = false, pointsLog = s.pointsLog + "已停止") }
    }

    fun togglePausePointsTask() {
        val runner = pointsRunner ?: return
        runner.paused = !runner.paused
        _state.update { it.copy(pointsPaused = runner.paused) }
    }

    fun clearPointsLog() {
        _state.update { it.copy(pointsLog = emptyList()) }
    }

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
    private var unlockTimeoutJob: Job? = null

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
        _state.update { s -> s.copy(signInDoneToday = signInRunner.isSignedInToday()) }
        if (repository.localToken() != null) {
            refreshDevices()
            refreshBalance()
            refreshTodayWater()
        }
        // 打开 App 时自动签到（仅签到；首页浏览等积分任务仍需手动执行）
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
    fun logout() = authController.logout()

    // ── 签到 ──
    /** 启动时自动签到：开关开启 + 已登录 + 今日未签到才执行，复用手动签到的全部保护 */
    fun autoSignInOnLaunch() {
        if (!state.value.autoSignInEnabled) return
        if (!state.value.hasToken) return
        // 签到标记存在两处（手动签到写 ad_video_state，积分任务写 points_task），
        // 任一为真都视为今天已签过——积分任务流程本身包含签到，无需重复请求
        if (signInRunner.isSignedInToday() || PointsTaskRunner.isPointsDoneToday(context)) return
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
            if (e is TokenExpiredException) {
                authController.handleTokenExpired()
            } else {
                showError(e.message ?: "签到失败")
            }
        }
    }

    // ── 设备 / 余额 ──
    private fun handleApiError(error: Throwable, fallbackMessage: String = "操作失败"): Boolean {
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

    fun refreshBalance() = viewModelScope.launch {
        if (!state.value.hasToken) return@launch
        runCatching {
            _state.update { it.copy(loadingBalance = true) }
            repository.queryBalance()
        }.onSuccess { balance ->
            _state.update { it.copy(balance = balance, loadingBalance = false) }
        }.onFailure {
            _state.update { it.copy(loadingBalance = false) }
            handleApiError(it, "查询资产失败")
        }
    }

    // ── 解锁 ──
    fun unlock(device: DeviceItem) = viewModelScope.launch {
        if (state.value.unlocking) return@launch
        if (!unlockMutex.tryLock()) return@launch
        try {
            _state.update {
                it.copy(unlocking = true, unlockingDeviceId = device.goodsName.ifBlank { device.id }, unlockStatus = "准备解锁", unlockFlowState = UnlockFlowState.PreChecking(), unlockElapsedSeconds = 0, unlockFlowHidden = false)
            }
            unlockTimerJob?.cancel()
            unlockTimerJob = viewModelScope.launch {
                while (isActive) {
                    delay(1000)
                    // 直接累加全局秒数；Working 卡片展示时用 165 - elapsed 计算剩余
                    if (state.value.unlockFlowState is UnlockFlowState.Working) {
                        _state.update { it.copy(unlockElapsedSeconds = state.value.unlockElapsedSeconds + 1) }
                    }
                }
            }
            unlockTimeoutJob?.cancel()
            unlockTimeoutJob = viewModelScope.launch {
                delay(165_000)
                if (state.value.unlockFlowState is UnlockFlowState.Working) {
                    // 兜底：165 秒到点设备仍未上报结束，视为平台已自动关阀结算，结束前台等待
                    unlockJob?.cancel()
                    _state.update {
                        it.copy(
                            unlocking = false,
                            unlockStatus = null,
                            unlockFlowState = UnlockFlowState.Idle,
                            unlockElapsedSeconds = 0,
                            unlockFlowHidden = false,
                            unlockingDeviceId = null,
                            orderHistory = repository.orderHistory(),
                        )
                    }
                    refreshBalance()
                    refreshDevices()
                    refreshTodayWater()
                    showToast("饮水机已自动关闭并结算")
                }
            }
            unlockJob = viewModelScope.launch {
                runCatching {
                    repository.unlockDevice(device, usePoints = state.value.usePointsForUnlock) { step ->
                        val isWorking = step.contains("等待") || step.contains("设备工作") ||
                            step.contains("创建后付") || step.contains("查询订单")
                        _state.update {
                            it.copy(unlockStatus = step, unlockFlowState = if (isWorking) UnlockFlowState.Working(step, state.value.unlockElapsedSeconds) else UnlockFlowState.PreChecking(step))
                        }
                    }
                }.onSuccess { result ->
                    unlockTimerJob?.cancel()
                    unlockTimeoutJob?.cancel()
                    unlockJob = null
                    _state.update { it.copy(unlocking = false, unlockStatus = null, unlockFlowState = UnlockFlowState.Success(result), unlockElapsedSeconds = 0, unlockFlowHidden = false, orderHistory = repository.orderHistory()) }
                    refreshBalance()
                    refreshTodayWater()
                }.onFailure { e ->
                    unlockTimerJob?.cancel()
                    unlockTimeoutJob?.cancel()
                    unlockJob = null
                    // 165 秒兜底主动取消轮询时不算失败，状态已由超时处理器收尾
                    if (e is kotlinx.coroutines.CancellationException) return@launch
                    if (e is TokenExpiredException) {
                        _state.update { it.copy(unlocking = false, unlockStatus = null, unlockFlowState = UnlockFlowState.Idle, unlockElapsedSeconds = 0, unlockFlowHidden = false) }
                        authController.handleTokenExpired()
                        return@launch
                    }
                    val diag = if (e is UnlockException) e.diagnosis else null
                    val failState = if (diag != null) UnlockFlowState.Failed(diag.primaryReason, diag.step, diag.rawError, diag.suggestions)
                        else UnlockFlowState.Failed(e.message ?: "未知错误", "未知", e.message ?: "")
                    _state.update { it.copy(unlocking = false, unlockStatus = null, unlockFlowState = failState, unlockElapsedSeconds = 0, unlockFlowHidden = false) }
                }
            }
        } finally {
            unlockMutex.unlock()
        }
    }

    fun dismissUnlockFlow() {
        unlockTimerJob?.cancel()
        unlockTimeoutJob?.cancel()
        _state.update { it.copy(unlockFlowState = UnlockFlowState.Idle, unlockElapsedSeconds = 0, unlockingDeviceId = null) }
    }

    fun dismissUnlockAnimation() {
        unlockTimerJob?.cancel()
        unlockTimeoutJob?.cancel()
        _state.update { it.copy(unlockFlowHidden = true, unlockElapsedSeconds = 0) }
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
    fun qzxyUpdatePassword(value: String) = qzxyController.updatePassword(value)
    fun qzxyTogglePasswordVisible() = qzxyController.togglePasswordVisibility()
    fun qzxyLogin() = qzxyController.login()
    fun qzxyLogout() = qzxyController.logout()
    fun qzxyShowLogoutConfirm() = qzxyController.showLogoutConfirm()
    fun qzxyDismissLogoutConfirm() = qzxyController.dismissLogoutConfirm()
    fun qzxyRefreshWallet() = qzxyController.refreshWallet()
    fun qzxyLoadUseCode() = qzxyController.loadUseCode()
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
        _state.update { it.copy(totalWaterCount = repository.orderHistory().size) }
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
        unlockTimeoutJob?.cancel()
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
