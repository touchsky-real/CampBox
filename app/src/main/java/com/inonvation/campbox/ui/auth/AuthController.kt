package com.inonvation.campbox.ui.auth

import com.inonvation.campbox.data.AppRepository
import com.inonvation.campbox.data.UserPrefsStore
import com.inonvation.campbox.ui.AppUiState
import com.inonvation.campbox.ui.UnlockFlowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuthController(
    private val state: StateFlow<AppUiState>,
    private val updateState: ((AppUiState) -> AppUiState) -> Unit,
    private val scope: CoroutineScope,
    private val repository: AppRepository,
    private val userPrefsStore: UserPrefsStore?,
    private val onAuthSuccess: () -> Unit,
    private val showToast: (String) -> Unit,
    private val showError: (String) -> Unit,
) {
    private val PHONE_REGEX = Regex("^1[3-9]\\d{9}$")
    private var codeSentTimestamp: Long = 0

    fun updatePhone(value: String) {
        updateState { it.copy(phone = value, phoneError = null) }
        val trimmed = value.trim()
        if (trimmed.isNotEmpty() && !PHONE_REGEX.matches(trimmed)) {
            updateState { it.copy(phoneError = "请输入正确格式的手机号") }
        }
    }

    fun updateCode(value: String) {
        updateState { it.copy(code = value.filter { it.isDigit() }) }
    }

    fun toggleTokenLogin() {
        updateState { it.copy(showTokenLogin = !it.showTokenLogin, tokenLoginInput = "", tokenLoginVisible = false) }
    }

    fun updateTokenLoginInput(value: String) {
        updateState { it.copy(tokenLoginInput = value) }
    }

    fun toggleTokenLoginVisibility() {
        updateState { it.copy(tokenLoginVisible = !it.tokenLoginVisible) }
    }

    fun loginWithToken() = scope.launch {
        if (state.value.tokenLoggingIn || state.value.loggingIn) return@launch
        val token = state.value.tokenLoginInput.trim()
        if (token.isBlank()) {
            showError("请输入 Token")
            return@launch
        }
        runCatching {
            updateState { it.copy(tokenLoggingIn = true) }
            repository.validateToken(token)
        }.onSuccess {
            repository.saveToken(token)
            updateState { it.copy(hasToken = true, tokenLoggingIn = false, showTokenLogin = false, tokenLoginInput = "") }
            showToast("登录成功")
            onAuthSuccess()
        }.onFailure {
            updateState { it.copy(tokenLoggingIn = false) }
            if (it is CancellationException) throw it
            showError(it.message ?: "Token 无效或已过期")
        }
    }

    fun sendCode() = scope.launch {
        if (state.value.sendingCode) return@launch
        val phone = state.value.phone.trim()
        val elapsed = System.currentTimeMillis() - codeSentTimestamp
        if (elapsed < 60_000) {
            showError("验证码已发送，请${(60 - elapsed / 1000).toInt()}秒后再试")
            return@launch
        }
        if (phone.isBlank() || !PHONE_REGEX.matches(phone)) {
            updateState { it.copy(phoneError = "请输入正确格式的手机号") }
            return@launch
        }
        runCatching {
            updateState { it.copy(sendingCode = true) }
            repository.sendCode(phone)
        }.onSuccess {
            codeSentTimestamp = System.currentTimeMillis()
            showToast("验证码已发送")
        }.onFailure {
            updateState { it.copy(sendingCode = false) }
            if (it is CancellationException) throw it
            showError(it.message ?: "验证码发送失败")
        }
        updateState { it.copy(sendingCode = false) }
    }

    fun login() = scope.launch {
        if (state.value.loggingIn || state.value.tokenLoggingIn) return@launch
        val phone = state.value.phone.trim()
        val code = state.value.code.trim()
        if (phone.isBlank() || !PHONE_REGEX.matches(phone)) {
            updateState { it.copy(phoneError = "请输入正确格式的手机号") }
            return@launch
        }
        if (code.isBlank()) {
            showError("请输入验证码")
            return@launch
        }
        runCatching {
            updateState { it.copy(loggingIn = true) }
            repository.login(phone, code)
        }.onSuccess {
            updateState { it.copy(hasToken = true, loggingIn = false, phoneError = null) }
            showToast("登录成功")
            repository.savePhone(phone)
            onAuthSuccess()
        }.onFailure {
            updateState { it.copy(loggingIn = false) }
            if (it is CancellationException) throw it
            showError(it.message ?: "登录失败")
        }
    }

    fun logout() {
        repository.clearToken()
        repository.savePhone("")
        repository.clearOrderHistory()
        updateState {
            it.copy(
                hasToken = false,
                phone = "",
                code = "",
                balance = null,
                devices = emptyList(),
                orderHistory = emptyList(),
                totalWaterCount = 0,
                unlockFlowState = UnlockFlowState.Idle,
                unlockStatus = null,
                unlockingDeviceId = null,
            )
        }
        showToast("已退出登录")
    }

    fun handleTokenExpired() {
        repository.clearToken()
        repository.savePhone("")
        updateState { it.copy(hasToken = false) }
        showError("登录已失效，请重新登录")
    }
}
