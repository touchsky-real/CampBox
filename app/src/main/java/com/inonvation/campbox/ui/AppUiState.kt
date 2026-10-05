package com.inonvation.campbox.ui

import com.inonvation.campbox.data.BalanceData
import com.inonvation.campbox.data.DeviceItem
import com.inonvation.campbox.data.OrderHistoryItem
import com.inonvation.campbox.data.QuickLink
import com.inonvation.campbox.data.UnlockResult
import com.inonvation.campbox.ui.theme.ColorTheme
import com.inonvation.campbox.ui.theme.ThemeMode
import com.inonvation.campbox.ui.qzxy.QzxyUiState

data class DeviceShortcutRequest(
    val goodsId: String?,
    val id: String?,
    val goodsName: String?,
)

sealed class UnlockFlowState {
    data object Idle : UnlockFlowState()
    data class PreChecking(val step: String = "正在准备…") : UnlockFlowState()
    data class Working(val step: String, val elapsedSeconds: Int = 0) : UnlockFlowState()
    data class Success(val result: UnlockResult) : UnlockFlowState()
    data class Failed(
        val message: String,
        val step: String,
        val rawError: String,
        val suggestions: List<String> = emptyList(),
    ) : UnlockFlowState()
}

data class AppUiState(
    // ── 登录 ──
    val hasToken: Boolean = false,
    val phone: String = "",
    val code: String = "",
    val phoneError: String? = null,
    val sendingCode: Boolean = false,
    val loggingIn: Boolean = false,
    val showTokenLogin: Boolean = false,
    val tokenLoginInput: String = "",
    val tokenLoginVisible: Boolean = false,
    val tokenLoggingIn: Boolean = false,

    // ── 设备 ──
    val loadingDevices: Boolean = false,
    val loadingBalance: Boolean = false,
    val devices: List<DeviceItem> = emptyList(),
    val balance: BalanceData? = null,

    // ── 解锁 ──
    val unlocking: Boolean = false,
    val unlockingDeviceId: String? = null,
    val unlockStatus: String? = null,
    val unlockFlowState: UnlockFlowState = UnlockFlowState.Idle,
    val unlockElapsedSeconds: Int = 0,
    val usePointsForUnlock: Boolean = true,
    val unlockFlowHidden: Boolean = false,

    // ── 统计 ──
    val signInDoneToday: Boolean = false,
    val signingIn: Boolean = false,
    val totalWaterCount: Int = 0,
    val orderHistory: List<OrderHistoryItem> = emptyList(),

    // ── 积分任务 ──
    val pointsRunning: Boolean = false,
    val pointsPaused: Boolean = false,
    val pointsLog: List<String> = emptyList(),
    val pointsTodayEarned: Int = 0,

    // ── 校园网 ──
    val campusUsername: String = "",
    val campusPassword: String = "",
    val campusPasswordVisible: Boolean = false,
    val campusHasSaved: Boolean = false,
    val campusLoggingIn: Boolean = false,
    val campusLog: List<String> = emptyList(),
    val campusLastSuccess: Boolean? = null,

    // ── 设置 ──
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorTheme: ColorTheme = ColorTheme.GREEN,
    val hapticEnabled: Boolean = true,
    val autoSignInEnabled: Boolean = true,
    val autoCampusNetEnabled: Boolean = false,

    // ── 快捷方式 ──
    val quickLinks: List<QuickLink> = emptyList(),
    val quickLinksEnabled: Boolean = true,
    val showQuickLinksSettings: Boolean = false,

    // ── 弹窗/对话框 ──
    val showSettings: Boolean = false,
    val showOrderHistory: Boolean = false,
    val showLogoutConfirm: Boolean = false,
    val tokenDialogText: String? = null,
    val deviceInfoDialogText: String? = null,

    // ── 淋浴（趣智校园，独立账号与状态）──
    val qzxy: QzxyUiState = QzxyUiState(),

    // ── 全局 ──
    val appVersion: String = "",
)
