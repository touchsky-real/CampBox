package com.inonvation.campbox.ui.qzxy

import android.os.SystemClock

import com.inonvation.campbox.data.qzxy.QzxyActiveShower
import com.inonvation.campbox.data.qzxy.QzxyApiConfig
import com.inonvation.campbox.data.qzxy.QzxyApiException
import com.inonvation.campbox.data.qzxy.QzxyBluetoothScanner
import com.inonvation.campbox.data.qzxy.QzxyBoundDevice
import com.inonvation.campbox.data.qzxy.QzxyDeviceInfo
import com.inonvation.campbox.data.qzxy.QzxyNearbyDevice
import com.inonvation.campbox.data.qzxy.QzxyRepository
import com.inonvation.campbox.data.qzxy.QzxySessionExpiredException
import com.inonvation.campbox.data.qzxy.QzxySettleResult
import com.inonvation.campbox.ui.AppUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 趣智校园控制器，仿照 AuthController 的委托模式挂进 AppViewModel。
 * 管登录、设备绑定、蓝牙扫描、洗澡状态机；胖乖生活平台的登录与数据不受影响。
 *
 * 交互模型：绑定设备是主操作对象（查询到详情即绑定并持久化），
 * 扫描/手输 MAC 是"更换设备"里的次要入口。
 */
class QzxyController(
    private val state: StateFlow<AppUiState>,
    private val updateQzxy: ((QzxyUiState) -> QzxyUiState) -> Unit,
    private val scope: CoroutineScope,
    private val repository: QzxyRepository,
    private val scanner: QzxyBluetoothScanner,
    private val showToast: (String) -> Unit,
    private val showError: (String) -> Unit,
) {
    private val scanResults = ConcurrentHashMap<String, QzxyNearbyDevice>()
    private val enrichingMacs: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val revealedMacs: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val enrichSemaphore = Semaphore(4)
    private var timerJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var walletJob: Job? = null
    private var useCodeJob: Job? = null
    private var candidateTimerJob: Job? = null
    private var candidateDeadline: Long = 0
    private var accountGeneration: Long = 0
    private var pendingAccountPage: QzxyAccountPage? = null
    private var pendingDevicePicker = false
    private var smsTimerJob: Job? = null
    private var smsDeadline: Long = 0

    // ── 会话恢复 ──

    fun restoreSession() {
        val session = repository.session()
        if (session == null) {
            updateQzxy { QzxyUiState() }
            return
        }
        updateQzxy {
            it.copy(
                loggedIn = true,
                userName = session.userName,
                accountPhone = maskPhone(session.telephone),
                boundDevice = repository.readBoundDevice(),
            )
        }
        refreshWallet()
        restoreActiveOrder()
        refreshBoundDeviceInfo()
    }

    /** App 冷启动或重新登录后，检查绑定设备是否还有进行中的订单（含使用码启动的场景） */
    private fun restoreActiveOrder() = scope.launch {
        if (state.value.qzxy.showerFlow !is QzxyShowerState.Idle) return@launch
        val bound = state.value.qzxy.boundDevice ?: return@launch
        if (bound.snCode.isBlank()) return@launch
        val status = runCatching { repository.queryUsing(bound.snCode) }.getOrNull()
            ?: return@launch
        if (status.isOwner == false) return@launch
        val orderNo = status?.orderNo?.takeIf { it.isNotBlank() } ?: return@launch
        val active = QzxyActiveShower(
            mac = bound.mac,
            snCode = bound.snCode,
            orderNo = orderNo,
            deviceName = bound.name.ifBlank { "热水器" },
        )
        updateQzxy {
            it.copy(
                activeOrder = active,
                showerFlow = QzxyShowerState.Running(deviceName = active.deviceName, withholdMoney = "-"),
            )
        }
        startTimer()
        showToast("检测到进行中的洗澡订单，已恢复")
    }

    /** 静默刷新绑定设备的在线状态与预扣金额，失败不打扰 */
    private fun refreshBoundDeviceInfo() = scope.launch {
        val bound = state.value.qzxy.boundDevice ?: return@launch
        if (bound.mac.isBlank()) return@launch
        runCatching { repository.deviceInfo(bound.mac) }
            .onSuccess { info -> updateQzxy { it.copy(selectedDevice = info) } }
            .onFailure { e -> if (e is QzxySessionExpiredException) handleSessionExpired() }
    }

    // ── 登录 ──

    fun showLoginSheet() = updateQzxy {
        it.copy(showLoginSheet = true, password = "", smsCode = "", passwordVisible = false, loginError = null)
    }
    fun dismissLoginSheet() {
        if (state.value.qzxy.loggingIn || state.value.qzxy.sendingSms) return
        pendingAccountPage = null
        pendingDevicePicker = false
        updateQzxy { it.copy(showLoginSheet = false, password = "", smsCode = "", passwordVisible = false) }
    }

    fun updatePhone(value: String) {
        if (state.value.qzxy.loggingIn || state.value.qzxy.sendingSms) return
        val phone = value.filter { it in '0'..'9' }.take(11)
        updateQzxy { it.copy(phone = phone, smsCode = if (phone == it.phone) it.smsCode else "", loginError = null) }
    }

    fun updateSmsCode(value: String) = updateQzxy {
        if (it.loggingIn) it else it.copy(smsCode = value.filter { c -> c in '0'..'9' }.take(6), loginError = null)
    }

    fun setSmsLogin(enabled: Boolean) = updateQzxy {
        if (it.loggingIn || it.sendingSms) it
        else it.copy(smsLogin = enabled, smsCode = "", password = "", passwordVisible = false, loginError = null)
    }

    fun sendLoginSms() = scope.launch {
        val q = state.value.qzxy
        if (q.loggedIn || q.loggingIn || q.sendingSms || !q.smsLogin) return@launch
        if (!PHONE_REGEX.matches(q.phone)) {
            updateQzxy { it.copy(loginError = "请输入正确格式的手机号") }
            return@launch
        }
        if (SystemClock.elapsedRealtime() < smsDeadline) return@launch
        // 超时不代表短信未发出；倒计时从请求开始，切换方式或关闭弹层也不能绕过。
        smsDeadline = SystemClock.elapsedRealtime() + 60_000L
        updateQzxy { it.copy(sendingSms = true, smsSecondsLeft = 60, loginError = null) }
        smsTimerJob?.cancel()
        smsTimerJob = scope.launch {
            while (isActive) {
                val left = ((smsDeadline - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0).toInt()
                updateQzxy { it.copy(smsSecondsLeft = left) }
                if (left == 0) break
                delay(1000)
            }
        }
        try {
            repository.sendLoginSms(q.phone)
            showToast("验证码已发送")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateQzxy { it.copy(loginError = accountError(e, "验证码发送失败，请稍后重试")) }
        } finally {
            updateQzxy { it.copy(sendingSms = false) }
        }
    }
    fun updatePassword(value: String) = updateQzxy { it.copy(password = value) }
    fun togglePasswordVisibility() = updateQzxy { it.copy(passwordVisible = !it.passwordVisible) }

    fun login() = scope.launch {
        val q = state.value.qzxy
        if (q.loggingIn || q.sendingSms || q.loggedIn) return@launch
        val phone = q.phone.trim()
        val error = when {
            !PHONE_REGEX.matches(phone) -> "请输入正确格式的手机号"
            q.smsLogin && !Regex("^[0-9]{6}$").matches(q.smsCode) -> "请输入 6 位短信验证码"
            !q.smsLogin && q.password.isBlank() -> "请输入密码"
            else -> null
        }
        if (error != null) {
            updateQzxy { it.copy(loginError = error) }
            return@launch
        }
        updateQzxy { it.copy(loggingIn = true, loginError = null) }
        runCatching {
            if (q.smsLogin) repository.loginWithSms(phone, q.smsCode)
            else repository.login(phone, q.password)
        }
            .onSuccess { session ->
                stopEverything()
                updateQzxy {
                    QzxyUiState(
                        loggedIn = true,
                        userName = session.userName,
                        accountPhone = maskPhone(session.telephone),
                        boundDevice = repository.readBoundDevice(),
                    )
                }
                showToast("趣智校园登录成功")
                refreshWallet()
                restoreActiveOrder()
                refreshBoundDeviceInfo()
                if (pendingDevicePicker) {
                    pendingDevicePicker = false
                    setDevicePicker(true)
                }
                pendingAccountPage?.let { page ->
                    pendingAccountPage = null
                    showAccountPage(page)
                }
            }
            .onFailure { e ->
                if (e is CancellationException) throw e
                updateQzxy { it.copy(loggingIn = false, loginError = e.message ?: "登录失败") }
            }
    }

    fun logout() {
        pendingDevicePicker = false
        pendingAccountPage = null
        stopEverything()
        repository.logout()
        updateQzxy {
            // 保留绑定设备，退出后重登无需重新扫描绑定
            QzxyUiState(
                boundDevice = state.value.qzxy.boundDevice,
                selectedDevice = state.value.qzxy.selectedDevice,
            )
        }
        showToast("已退出趣智校园登录")
    }

    fun showLogoutConfirm() = updateQzxy { it.copy(showLogoutConfirm = true) }
    fun dismissLogoutConfirm() = updateQzxy { it.copy(showLogoutConfirm = false) }

    /** 被挤号或会话过期：清空趣智全部状态并引导重新登录 */
    private fun handleSessionExpired() {
        pendingAccountPage = state.value.qzxy.accountPage
        stopEverything()
        repository.logout()
        updateQzxy { QzxyUiState(showLoginSheet = true) }
        showError("趣智校园登录已失效，请重新登录")
    }

    private fun stopEverything() {
        smsTimerJob?.cancel()
        smsDeadline = 0
        // 先作废请求代次，避免退出或切换账号后旧响应写回新会话。
        accountGeneration++
        walletJob?.cancel()
        useCodeJob?.cancel()
        candidateTimerJob?.cancel()
        candidateDeadline = 0
        stopTimer()
        stopScanInternal()
        repository.closeBt()
    }

    // ── 钱包与使用码 ──

    private fun accountRequestIsCurrent(generation: Long): Boolean =
        generation == accountGeneration && state.value.qzxy.loggedIn

    fun showAccountPage(page: QzxyAccountPage) {
        if (!state.value.qzxy.loggedIn) {
            pendingAccountPage = page
            showLoginSheet()
            return
        }
        if (state.value.qzxy.useCodeAction != null) return
        updateQzxy { it.copy(accountPage = page) }
        when (page) {
            QzxyAccountPage.Wallet -> refreshWallet()
            QzxyAccountPage.UseCode -> loadUseCode()
        }
    }

    fun dismissAccountPage() {
        if (state.value.qzxy.useCodeAction != null) return
        discardUseCodeCandidate()
        updateQzxy { it.copy(accountPage = null) }
    }

    fun refreshWallet() {
        val q = state.value.qzxy
        if (!q.loggedIn || q.loadingWallet) return
        val generation = accountGeneration
        updateQzxy { it.copy(loadingWallet = true, wallet = null, walletError = null) }
        walletJob = scope.launch {
            try {
                val wallet = repository.wallet()
                if (accountRequestIsCurrent(generation)) updateQzxy { it.copy(wallet = wallet) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (accountRequestIsCurrent(generation)) {
                    if (e is QzxySessionExpiredException) handleSessionExpired()
                    else updateQzxy { it.copy(walletError = accountError(e, "查询余额失败，请重试")) }
                }
            } finally {
                if (accountRequestIsCurrent(generation)) updateQzxy { it.copy(loadingWallet = false) }
            }
        }
    }

    fun loadUseCode() {
        val q = state.value.qzxy
        if (!q.loggedIn || q.loadingUseCode || q.useCodeAction != null) return
        val generation = accountGeneration
        discardUseCodeCandidate()
        updateQzxy { it.copy(loadingUseCode = true, useCodeData = null, useCodeError = null, useCodeRemainingGenerations = null) }
        useCodeJob = scope.launch {
            try {
                val data = repository.useCode()
                if (accountRequestIsCurrent(generation)) updateQzxy { it.copy(useCodeData = data) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (accountRequestIsCurrent(generation)) {
                    if (e is QzxySessionExpiredException) handleSessionExpired()
                    else updateQzxy { it.copy(useCodeError = accountError(e, "查询使用码失败，请重试")) }
                }
            } finally {
                if (accountRequestIsCurrent(generation)) updateQzxy { it.copy(loadingUseCode = false) }
            }
        }
    }

    fun generateUseCode() {
        val q = state.value.qzxy
        if (!canChangeUseCode(q)) return
        val data = q.useCodeData ?: return
        if (!data.canClaim) {
            updateQzxy { it.copy(useCodeError = data.resetAvailabilityWarMark ?: "每天只能领取一次使用码") }
            return
        }
        if (q.useCodeRemainingGenerations == 0) {
            updateQzxy { it.copy(useCodeError = "今日换码次数已用完，请明天再试") }
            return
        }
        discardUseCodeCandidate()
        performUseCodeAction(QzxyUseCodeAction.Generate) { generation ->
            val startedAt = SystemClock.elapsedRealtime()
            val candidate = repository.generateUseCode()
            requireCurrentAccount(generation)
            candidateDeadline = startedAt + 180_000L
            updateQzxy {
                it.copy(useCodeCandidate = candidate.useCode, useCodeRemainingGenerations = candidate.remainTimes,
                    useCodeSecondsLeft = 180)
            }
            startCandidateTimer()
        }
    }

    fun claimUseCode() {
        val q = state.value.qzxy
        if (!canChangeUseCode(q)) return
        val code = q.useCodeCandidate ?: return
        if (SystemClock.elapsedRealtime() >= candidateDeadline) {
            updateQzxy { it.copy(useCodeSecondsLeft = 0, useCodeError = "候选码已过期，请换一个后再领取") }
            return
        }
        performUseCodeAction(QzxyUseCodeAction.Claim) { generation ->
            repository.claimUseCode(code)
            requireCurrentAccount(generation)
            discardUseCodeCandidate()
            // set 已成功但随后的查询仍可能失败；清空旧码，不能把旧码显示为仍然有效。
            updateQzxy { it.copy(useCodeData = null) }
            val current = repository.useCode()
            requireCurrentAccount(generation)
            updateQzxy { it.copy(useCodeData = current) }
            showToast("使用码已领取")
        }
    }

    fun setUseCodeEnabled(enabled: Boolean) {
        val q = state.value.qzxy
        if (!canChangeUseCode(q) || q.useCodeData?.code == null) return
        performUseCodeAction(if (enabled) QzxyUseCodeAction.Enable else QzxyUseCodeAction.Disable) { generation ->
            repository.setUseCodeEnabled(enabled)
            requireCurrentAccount(generation)
            updateQzxy { it.copy(useCodeData = null) }
            val current = repository.useCode()
            requireCurrentAccount(generation)
            updateQzxy { it.copy(useCodeData = current) }
            showToast(if (enabled) "使用码已开启" else "使用码已关闭")
        }
    }

    private fun canChangeUseCode(q: QzxyUiState): Boolean =
        q.loggedIn && !q.loadingUseCode && q.useCodeAction == null

    private fun performUseCodeAction(action: QzxyUseCodeAction, block: suspend (Long) -> Unit) {
        val generation = accountGeneration
        updateQzxy { it.copy(useCodeAction = action, useCodeError = null) }
        useCodeJob = scope.launch {
            try {
                block(generation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (accountRequestIsCurrent(generation)) {
                    if (e is QzxySessionExpiredException) handleSessionExpired()
                    else {
                        // 请求超时也可能已在服务端生效，先隐藏当前码，要求重新查询确认。
                        if (action != QzxyUseCodeAction.Generate) {
                            discardUseCodeCandidate()
                            updateQzxy { it.copy(useCodeData = null) }
                        }
                        val fallback = if (action == QzxyUseCodeAction.Generate) "换码失败，请重试"
                            else "操作结果未确认，请重新查询使用码"
                        updateQzxy { it.copy(useCodeError = accountError(e, fallback)) }
                    }
                }
            } finally {
                if (accountRequestIsCurrent(generation)) updateQzxy { it.copy(useCodeAction = null) }
            }
        }
    }

    fun discardUseCodeCandidate() {
        candidateTimerJob?.cancel()
        candidateDeadline = 0
        updateQzxy { it.copy(useCodeCandidate = null, useCodeSecondsLeft = 0) }
    }

    private fun startCandidateTimer() {
        candidateTimerJob?.cancel()
        candidateTimerJob = scope.launch {
            while (isActive) {
                val seconds = ((candidateDeadline - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0).toInt()
                updateQzxy { it.copy(useCodeSecondsLeft = seconds) }
                if (seconds == 0) break
                delay(1000)
            }
        }
    }

    private fun requireCurrentAccount(generation: Long) {
        if (!accountRequestIsCurrent(generation)) throw CancellationException("趣智账号已切换")
    }

    private fun accountError(error: Exception, fallback: String): String = when (error) {
        is java.io.IOException -> "网络连接失败，请检查网络后重试"
        is QzxyApiException -> error.message ?: fallback
        else -> fallback
    }

    // ── 绑定设备 ──

    /** 查询设备详情；成功即绑定并收起设备选择器 */
    fun selectDevice(device: QzxyNearbyDevice) = scope.launch {
        val q = state.value.qzxy
        if (q.queryingMac != null) return@launch
        if (q.showerFlow !is QzxyShowerState.Idle) {
            showError("洗澡进行中，暂不能更换设备")
            return@launch
        }
        updateQzxy { it.copy(queryingMac = device.mac, selectedDevice = null) }
        runCatching { repository.deviceInfo(device.mac) }
            .onSuccess { info ->
                val bound = QzxyBoundDevice(
                    mac = info.macAddress?.takeIf { it.isNotBlank() } ?: device.mac,
                    snCode = info.snCode.orEmpty(),
                    name = info.displayName,
                )
                repository.saveBoundDevice(bound)
                updateQzxy {
                    it.copy(
                        queryingMac = null,
                        selectedDevice = info,
                        boundDevice = bound,
                        showDevicePicker = false,
                    )
                }
                showToast("已绑定设备：${bound.name}")
            }
            .onFailure { e ->
                updateQzxy { it.copy(queryingMac = null) }
                if (e is QzxySessionExpiredException) handleSessionExpired()
                else showError(e.message ?: "查询设备信息失败")
            }
    }

    /** 刷新绑定设备的在线状态与预扣金额（用户手动触发） */
    fun refreshSelectedDevice() = scope.launch {
        val q = state.value.qzxy
        if (q.queryingMac != null) return@launch
        val mac = q.selectedDevice?.macAddress?.takeIf { it.isNotBlank() }
            ?: q.boundDevice?.mac?.takeIf { it.isNotBlank() }
            ?: return@launch
        updateQzxy { it.copy(queryingMac = mac) }
        runCatching { repository.deviceInfo(mac) }
            .onSuccess { info -> updateQzxy { it.copy(queryingMac = null, selectedDevice = info) } }
            .onFailure { e ->
                updateQzxy { it.copy(queryingMac = null) }
                if (e is QzxySessionExpiredException) handleSessionExpired()
                else showError(e.message ?: "刷新设备状态失败")
            }
    }

    // ── 更换设备（次要入口）──

    fun setDevicePicker(open: Boolean) {
        if (open) {
            if (!state.value.qzxy.loggedIn) {
                pendingDevicePicker = true
                showLoginSheet()
                return
            }
            if (state.value.qzxy.showerFlow !is QzxyShowerState.Idle) {
                showError("请先结束当前洗澡流程，再更换设备")
                return
            }
            updateQzxy { it.copy(showDevicePicker = true) }
        } else {
            stopScanInternal()
            updateQzxy { it.copy(showDevicePicker = false) }
        }
    }

    // ── 蓝牙扫描 ──

    fun startScan() {
        val q = state.value.qzxy
        if (!q.loggedIn) {
            showError("请先登录趣智校园")
            return
        }
        if (q.scanning) return
        scanResults.clear()
        enrichingMacs.clear()
        revealedMacs.clear()
        updateQzxy { it.copy(scanning = true, nearbyDevices = emptyList()) }
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(QzxyApiConfig.SCAN_DURATION_MS)
            stopScanInternal()
            if (state.value.qzxy.nearbyDevices.isEmpty()) {
                showToast("未发现附近热水器，可尝试手输 MAC 地址")
            }
        }
        scanner.start(
            onFound = { device ->
                // 重复广播只更新信号强度，保留已查到的设备详情
                val existing = scanResults[device.mac]
                val merged = existing?.copy(rssi = device.rssi) ?: device
                scanResults[device.mac] = merged
                if (existing == null) {
                    // 新设备：查到设备名（或超时兜底）才进列表，避免"先出广播名再替换"
                    enrichDevice(device.mac)
                } else if (device.mac in revealedMacs) {
                    publishNearby()
                }
            },
            onError = { message ->
                stopScanInternal()
                showError(message)
            },
        )
    }

    /**
     * 扫描发现新设备后自动按 MAC 查服务器真实设备名。
     * 查到名字（或超时兜底）之前设备不进列表——列表里出现的直接是真名。
     * 多设备并行查询（并发上限 4），蓝牙地址与注册地址在仓库层并行竞速。
     */
    private fun enrichDevice(mac: String) {
        if (!enrichingMacs.add(mac)) return
        scope.launch {
            enrichSemaphore.withPermit {
                val pending = async { runCatching { repository.deviceInfo(mac) }.getOrNull() }
                val info = withTimeoutOrNull(QzxyApiConfig.ENRICH_REVEAL_TIMEOUT_MS) { pending.await() }
                applyEnrichResult(mac, info)
                if (info == null) {
                    // 超时兜底后查询仍在后台继续，拿到名字再原地更新
                    val late = pending.await()
                    if (late != null) applyEnrichResult(mac, late)
                }
            }
            enrichingMacs.remove(mac)
        }
    }

    private fun applyEnrichResult(mac: String, info: QzxyDeviceInfo?) {
        scanResults[mac]?.let { current ->
            scanResults[mac] = current.copy(info = info ?: current.info, nameLoading = false)
        }
        revealedMacs.add(mac)
        publishNearby()
    }

    private fun publishNearby() {
        val sorted = scanResults.values
            .filter { it.mac in revealedMacs }
            .sortedByDescending { it.rssi }
        updateQzxy { it.copy(nearbyDevices = sorted) }
    }

    fun stopScan() = stopScanInternal()

    private fun stopScanInternal() {
        scanTimeoutJob?.cancel()
        scanner.stop()
        updateQzxy { it.copy(scanning = false) }
    }

    fun onScanPermissionDenied() {
        updateQzxy { it.copy(scanning = false) }
        showError("缺少蓝牙权限，无法扫描附近设备")
    }

    // ── 手输 MAC ──

    fun showManualMacDialog() = updateQzxy { it.copy(showManualMacDialog = true, manualMacInput = "") }
    fun dismissManualMacDialog() = updateQzxy { it.copy(showManualMacDialog = false) }
    fun updateManualMac(value: String) = updateQzxy { it.copy(manualMacInput = value.trim().uppercase()) }

    fun submitManualMac() {
        val mac = state.value.qzxy.manualMacInput.trim().uppercase()
        if (!MAC_REGEX.matches(mac)) {
            showError("MAC 地址格式不正确，例如 C4:7F:0E:12:34:56")
            return
        }
        updateQzxy { it.copy(showManualMacDialog = false) }
        selectDevice(QzxyNearbyDevice(mac = mac, name = "手动输入设备", rssi = 0))
    }

    // ── 洗澡状态机 ──

    fun startShower() {
        val q = state.value.qzxy
        if (q.showerFlow !is QzxyShowerState.Idle) return
        val selected = q.selectedDevice
        val bound = q.boundDevice
        val snCode = selected?.snCode?.takeIf { it.isNotBlank() }
            ?: bound?.snCode?.takeIf { it.isNotBlank() }
        if (snCode == null) {
            showError("请先绑定设备")
            return
        }
        launchShower(
            mac = selected?.macAddress?.takeIf { it.isNotBlank() } ?: bound?.mac.orEmpty(),
            snCode = snCode,
            deviceName = selected?.displayName ?: bound?.name ?: "热水器",
            withhold = selected?.withholdMoney,
            info = selected,
        )
    }

    private fun launchShower(
        mac: String,
        snCode: String,
        deviceName: String,
        withhold: Double?,
        info: QzxyDeviceInfo?,
    ) {
        updateQzxy { it.copy(showerFlow = QzxyShowerState.Starting("正在准备…"), elapsedSeconds = 0) }
        startTimer()
        // 扫描与 GATT 建链抢同一个无线电：扫描不停，建链要慢好几倍
        stopScanInternal()
        scope.launch {
            runCatching {
                // 蓝牙款需要完整设备信息（deviceId 等）；缺失时先补查
                val deviceInfo = info ?: run {
                    updateQzxy { it.copy(showerFlow = QzxyShowerState.Starting("正在获取设备信息…")) }
                    repository.deviceInfo(mac)
                }
                if (deviceInfo.communicationTypeId == 0) {
                    repository.btStartShower(deviceInfo, deviceName, withhold) { step ->
                        updateQzxy { it.copy(showerFlow = QzxyShowerState.Starting(step)) }
                    }
                } else {
                    repository.startShower(mac, snCode, deviceName, withhold) { step ->
                        updateQzxy { it.copy(showerFlow = QzxyShowerState.Starting(step)) }
                    }
                }
            }.onSuccess { active ->
                val bound = QzxyBoundDevice(
                    mac = active.mac.ifBlank { mac },
                    snCode = active.snCode,
                    name = active.deviceName,
                )
                repository.saveBoundDevice(bound)
                updateQzxy {
                    it.copy(
                        boundDevice = bound,
                        activeOrder = active,
                        showerFlow = QzxyShowerState.Running(
                            deviceName = active.deviceName,
                            withholdMoney = active.preDeduct?.let { money -> "¥%.2f".format(money) } ?: "-",
                            autoCloseSecondsLeft = active.autoCloseSeconds,
                        ),
                    )
                }
                showToast(if (active.resumed) "检测到进行中的订单，已恢复使用" else "已开阀，开始使用")
            }.onFailure { e ->
                stopTimer()
                repository.closeBt()
                if (e is QzxySessionExpiredException) {
                    handleSessionExpired()
                    return@launch
                }
                updateQzxy {
                    it.copy(
                        showerFlow = QzxyShowerState.Failed(e.message ?: "启动失败", "启动", rawOf(e)),
                    )
                }
            }
        }
    }

    fun stopShower() {
        val q = state.value.qzxy
        val active = q.activeOrder ?: return
        if (q.showerFlow !is QzxyShowerState.Running && q.showerFlow !is QzxyShowerState.Failed) return
        val elapsed = q.elapsedSeconds
        val btSession = active.isBtSession
        updateQzxy { it.copy(showerFlow = QzxyShowerState.Stopping(if (btSession) "正在结算…" else "正在关阀…")) }
        scope.launch {
            runCatching {
                if (btSession) {
                    repository.btStopShower(active) { step ->
                        updateQzxy { it.copy(showerFlow = QzxyShowerState.Stopping(step)) }
                    }
                } else {
                    repository.stopShower(active) { step ->
                        updateQzxy { it.copy(showerFlow = QzxyShowerState.Stopping(step)) }
                    }
                }
            }.onSuccess { stop ->
                stopTimer()
                repository.closeBt()
                updateQzxy {
                    it.copy(
                        activeOrder = null,
                        showerFlow = QzxyShowerState.Done(
                            QzxySettleResult(
                                deviceName = active.deviceName,
                                elapsedSeconds = elapsed,
                                consumeMoneyText = stop.consumeMoney?.let { money -> "¥%.2f".format(money) }
                                    ?: "以账单为准",
                                consumeTime = stop.consumeTime,
                            )
                        ),
                    )
                }
                refreshWallet()
                stop.note?.let { showToast(it) }
            }.onFailure { e ->
                stopTimer()
                repository.closeBt()
                if (e is QzxySessionExpiredException) {
                    handleSessionExpired()
                    return@launch
                }
                updateQzxy {
                    it.copy(
                        showerFlow = QzxyShowerState.Failed(e.message ?: "关阀失败", "结束使用", rawOf(e)),
                    )
                }
            }
        }
    }

    fun dismissShowerFlow() {
        stopTimer()
        repository.closeBt()
        updateQzxy { it.copy(showerFlow = QzxyShowerState.Idle, elapsedSeconds = 0, activeOrder = null) }
    }

    /** 设备已被外部关闭（闲置自动关停/他人在设备上操作）时，走结算流程收尾 */
    private fun checkExternalClose() = scope.launch {
        val q = state.value.qzxy
        val active = q.activeOrder ?: return@launch
        if (q.showerFlow !is QzxyShowerState.Running) return@launch
        // 蓝牙款没有服务端订单号可查；设备侧状态以停止时的采集结果为准
        if (active.isBtSession) return@launch
        val status = runCatching { repository.queryUsing(active.snCode) }.getOrNull() ?: return@launch
        if (status?.orderNo.isNullOrBlank()) {
            showToast("设备已停止出水（可能已自动关停），正在查询本次消费…")
            stopShower()
        }
    }

    /** 原始报错附上错误码，便于排查学校接口差异 */
    private fun rawOf(e: Throwable): String =
        if (e is QzxyApiException) "errorCode=${e.errorCode ?: "-"}：${e.message}" else e.message.orEmpty()

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                delay(1_000)
                val q = state.value.qzxy
                when (val flow = q.showerFlow) {
                    is QzxyShowerState.Starting, is QzxyShowerState.Stopping ->
                        updateQzxy { it.copy(elapsedSeconds = q.elapsedSeconds + 1) }
                    is QzxyShowerState.Running -> {
                        val auto = flow.autoCloseSecondsLeft
                        val newAuto = if (auto != null && auto > 0) auto - 1 else auto
                        if (auto != null && auto > 0 && newAuto == 0) {
                            showToast("设备闲置时间已到，可能已自动关停；请点击「结束使用」查看本次消费")
                        }
                        val newElapsed = q.elapsedSeconds + 1
                        updateQzxy {
                            it.copy(
                                elapsedSeconds = newElapsed,
                                showerFlow = flow.copy(autoCloseSecondsLeft = newAuto),
                            )
                        }
                        // 每 30 秒查一次订单状态，兜底检测设备被外部关闭/自动关停
                        if (newElapsed > 0 && newElapsed % 30 == 0) checkExternalClose()
                    }
                    else -> {
                        stopTimer()
                        return@launch
                    }
                }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private companion object {
        val PHONE_REGEX = Regex("^1[3-9]\\d{9}$")
        val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")

        fun maskPhone(phone: String): String =
            if (phone.length == 11) phone.take(3) + "****" + phone.takeLast(4) else phone
    }
}
