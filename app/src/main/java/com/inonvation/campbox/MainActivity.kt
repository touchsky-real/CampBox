package com.inonvation.campbox

import android.Manifest

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inonvation.campbox.data.AppRepository
import com.inonvation.campbox.data.DeviceIdProvider
import com.inonvation.campbox.data.OrderHistoryStore
import com.inonvation.campbox.data.UserPrefsStore
import com.inonvation.campbox.data.QuickLinkStore
import com.inonvation.campbox.data.TokenStore
import com.inonvation.campbox.data.WaterLocationProvider
import com.inonvation.campbox.data.qzxy.QzxyAuthStore
import com.inonvation.campbox.data.qzxy.QzxyRepository
import com.inonvation.campbox.ui.AppViewModel
import com.inonvation.campbox.ui.AppViewModelFactory
import com.inonvation.campbox.ui.StrongHaptics
import com.inonvation.campbox.ui.UiEvent
import com.inonvation.campbox.ui.campus.CampusPortalActivity
import com.inonvation.campbox.ui.screen.OrderHistoryBottomSheet
import com.inonvation.campbox.ui.screen.QuickLinksSettingsScreen
import com.inonvation.campbox.ui.screen.SettingsScreen
import com.inonvation.campbox.ui.screen.SimpleScreen
import com.inonvation.campbox.ui.screen.TokenDialog
import com.inonvation.campbox.ui.shortcutRequestFromIntent
import com.inonvation.campbox.ui.theme.DeviceControlTheme
import com.inonvation.campbox.ui.theme.ThemeMode
import com.inonvation.campbox.ui.theme.ThemePreferences

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = AppRepository(
            tokenStore = TokenStore(applicationContext),
            orderHistoryStore = OrderHistoryStore(applicationContext),
            locationProvider = WaterLocationProvider(applicationContext),
            deviceIdProvider = { DeviceIdProvider.deviceId(applicationContext) },
        )
        val userPrefsStore = UserPrefsStore(applicationContext)
        val themePrefs = ThemePreferences(applicationContext)
        val quickLinkStore = QuickLinkStore(applicationContext)
        val qzxyRepository = QzxyRepository(applicationContext, QzxyAuthStore(applicationContext))
        val factory = AppViewModelFactory(
            application = application,
            repository = repository,
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown",
            userPrefsStore = userPrefsStore,
            themePreferences = themePrefs,
            quickLinkStore = quickLinkStore,
            qzxyRepository = qzxyRepository,
        )
        setContent {
            val vm: AppViewModel = viewModel(factory = factory)
            // 顶层主题只订阅外观设置，计时、日志等状态由 AppRoot 自己处理。
            val appearance by vm.appearance.collectAsState()
            DeviceControlTheme(
                darkTheme = when (appearance.first) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                },
                colorTheme = appearance.second,
            ) {
                // 全局换用强触感实现，绕开系统「触摸时振动」开关
                val view = LocalView.current
                val haptics = remember(view, vm) { StrongHaptics(view) { vm.state.value.hapticEnabled } }
                CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                    AppRoot(vm)
                }
            }
        }
    }
}


@Composable
private fun AppRoot(vm: AppViewModel) {
    val state by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val waterLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        vm.onWaterLocationPermissionResult(grants.values.any { it })
    }
    val campusPortalLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.verifyCampusInternetAfterPortal()
    }
    var pendingIconIndex by remember { mutableStateOf(-1) }
    val iconPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null && pendingIconIndex >= 0) {
            vm.setQuickLinkIcon(pendingIconIndex, uri)
            pendingIconIndex = -1
        }
    }

    BackHandler(
        enabled = state.showOrderHistory || state.showLogoutConfirm || state.showSettings ||
            state.showQuickLinksSettings || state.tokenDialogText != null || state.deviceInfoDialogText != null ||
            state.qzxy.showLogoutConfirm
    ) {
        when {
            state.showOrderHistory -> vm.dismissOrderHistory()
            state.showLogoutConfirm -> vm.dismissLogoutConfirm()
            state.showSettings -> vm.dismissSettings()
            state.showQuickLinksSettings -> vm.dismissQuickLinksSettings()
            state.tokenDialogText != null -> vm.dismissCurrentToken()
            state.deviceInfoDialogText != null -> vm.dismissCurrentDeviceInfo()
            state.qzxy.showLogoutConfirm -> vm.qzxyDismissLogoutConfirm()
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.RequestWaterLocation -> waterLocationLauncher.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                ))
                is UiEvent.OpenCampusPortal -> {
                    val current = vm.state.value
                    campusPortalLauncher.launch(CampusPortalActivity.intent(context,
                        current.campusUsername, current.campusPassword, vm.campusPortalUrl()))
                }
                is UiEvent.Toast -> Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                is UiEvent.Error -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    LaunchedEffect(vm) {
        shortcutRequestFromIntent((context as? ComponentActivity)?.intent)?.let(vm::openDeviceShortcut)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SimpleScreen(
            state = state,
            vm = vm,
            onPickIcon = { index ->
                pendingIconIndex = index
                iconPickerLauncher.launch("image/*")
            },
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }

    // 退出登录确认
    if (state.showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = vm::dismissLogoutConfirm,
            title = { Text("确认退出") },
            text = { Text("退出后将清除本地登录状态与订单记录，确定退出吗？") },
            confirmButton = {
                TextButton(onClick = { vm.dismissLogoutConfirm(); vm.logout() }) {
                    Text("退出")
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissLogoutConfirm) {
                    Text("取消")
                }
            },
        )
    }

    // 全屏设置页
    AnimatedVisibility(
        visible = state.showSettings,
        enter = slideInHorizontally { it },
        exit = slideOutHorizontally { it },
    ) {
        SettingsScreen(state = state, vm = vm)
    }
    AnimatedVisibility(
        visible = state.showQuickLinksSettings,
        enter = slideInHorizontally { it },
        exit = slideOutHorizontally { it },
    ) {
        QuickLinksSettingsScreen(state = state, vm = vm)
    }

    // 订单历史
    if (state.showOrderHistory) {
        OrderHistoryBottomSheet(orders = state.orderHistory, onDismiss = vm::dismissOrderHistory)
    }

    state.tokenDialogText?.let { TokenDialog(token = it, title = "我的 Token", onDismiss = vm::dismissCurrentToken) }
    state.deviceInfoDialogText?.let { TokenDialog(token = it, title = "设备信息", onDismiss = vm::dismissCurrentDeviceInfo) }
}
