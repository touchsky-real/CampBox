package com.inonvation.campbox.ui.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.inonvation.campbox.data.DeviceItem
import com.inonvation.campbox.ui.AppUiState
import com.inonvation.campbox.ui.AppViewModel
import com.inonvation.campbox.ui.UnlockFlowState
import com.inonvation.campbox.ui.pinDeviceShortcut
import com.inonvation.campbox.ui.qzxy.screen.QzxyShowerSection
import com.inonvation.campbox.ui.qzxy.screen.QzxyAccountActions
import com.inonvation.campbox.ui.qzxy.screen.QzxyDeviceDialogs
import com.inonvation.campbox.ui.qzxy.screen.QzxyAccountSheet
import com.inonvation.campbox.ui.qzxy.screen.QzxyLoginSheet
import com.inonvation.campbox.ui.qzxy.screen.QzxyLogoutConfirmDialog
import com.inonvation.campbox.ui.theme.AppColors
import com.inonvation.campbox.ui.theme.CardShapes
import com.inonvation.campbox.ui.theme.Spacings

/**
 * 最终版单页主界面：账户状态行 / 开水 / 洗澡 / 快捷方式 自上而下排列。
 * 开水与洗澡使用同一套卡片骨架（设备行 / 状态区 / 按钮行），
 * 进行中、结算、失败都在原卡状态区原地切换，不弹窗、不插新卡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleScreen(state: AppUiState, vm: AppViewModel, onPickIcon: ((Int) -> Unit)? = null) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var selectedDevice: DeviceItem? by remember { mutableStateOf(state.devices.firstOrNull()) }
    var showDeviceSheet by remember { mutableStateOf(false) }
    var showWaterDetail by remember { mutableStateOf(false) }
    var showWaterHelp by remember { mutableStateOf(false) }

    LaunchedEffect(state.devices) {
        val current = selectedDevice
        if (current == null || state.devices.none { it.id == current.id }) {
            selectedDevice = state.devices.firstOrNull()
        }
    }

    val pullRefreshState = rememberPullToRefreshState()
    val isRefreshing = state.loadingBalance || state.loadingDevices || state.qzxy.loadingWallet

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(WindowInsets.statusBars.asPaddingValues())
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("CampBox", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.balance?.pointsText ?: "-",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "分",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = { vm.showSettings() }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
                    }
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                if (state.hasToken) {
                    vm.refreshDevices()
                    vm.refreshBalance()
                }
                if (state.qzxy.loggedIn) vm.qzxyRefreshWallet()
            },
            state = pullRefreshState,
            modifier = Modifier.fillMaxSize().padding(padding),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    modifier = Modifier.align(Alignment.TopCenter),
                    isRefreshing = isRefreshing,
                    state = pullRefreshState,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    top = 4.dp,
                    end = 20.dp,
                    bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(Spacings.md),
            ) {
                // 快捷方式（置顶，未登录也可用）
                if (state.quickLinksEnabled) {
                    item { QuickLinksSection(state, vm, onPickIcon, cardVisible = true, haptic = haptic, context = ctx) }
                }

                if (!state.hasToken) {
                    item {
                        Spacer(Modifier.height(Spacings.sm))
                        LoginCard(
                            state = state,
                            onUpdatePhone = { vm.updatePhone(it) },
                            onUpdateCode = { vm.updateCode(it) },
                            onSendCode = { vm.sendCode() },
                            onLogin = { vm.login() },
                            onToggleTokenLogin = { vm.toggleTokenLogin() },
                            onUpdateTokenLoginInput = { vm.updateTokenLoginInput(it) },
                            onToggleTokenLoginVisibility = { vm.toggleTokenLoginVisibility() },
                            onLoginWithToken = { vm.loginWithToken() },
                            haptic = haptic,
                        )
                    }
                }

                if (state.hasToken) {
                    // 账户状态行：小票 + 累计开水 + 签到按钮
                    item { AccountStripRow(state, vm, haptic) }

                    // 开水
                    item { SectionLabel("开水", subtitle = "饮水机服务来自「胖乖生活」，用胖乖账号计费，积分可抵扣水费") }
                    item {
                        WaterCard(
                            state = state,
                            vm = vm,
                            selectedDevice = selectedDevice,
                            onSelectDevice = { selectedDevice = it },
                            onShowDetail = { showWaterDetail = true },
                            onPickDevice = { showDeviceSheet = true },
                            onShowHelp = { showWaterHelp = true },
                            haptic = haptic,
                            context = ctx,
                        )
                    }
                }

                // 校园网
                item { SectionLabel("校园网", subtitle = "学校 Dr.COM 上网认证，用学号登录，与胖乖、趣智校园账号互不相关") }
                item { CampusNetCard(state, vm, haptic) }

                // 洗澡（趣智校园）
                item { SectionLabel("洗澡", subtitle = "宿舍淋浴服务来自「趣智校园」，需另注册趣智账号，与胖乖无关") }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacings.sm)) {
                        if (state.qzxy.loggedIn) QzxyAccountActions(state.qzxy, vm)
                        QzxyShowerSection(state = state, vm = vm, haptic = haptic)
                    }
                }

                item {
                    Text(
                        text = "CampBox v${state.appVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = Spacings.lg),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    QzxyDeviceDialogs(state, vm, haptic)

    // 账户弹层放在列表外，趣智卡片滚出屏幕时仍能从设置打开。
    if (state.qzxy.showLoginSheet) QzxyLoginSheet(state.qzxy, vm)
    if (state.qzxy.showLogoutConfirm) QzxyLogoutConfirmDialog(state.qzxy, vm)
    if (state.qzxy.loggedIn && state.qzxy.accountPage != null) {
        QzxyAccountSheet(state.qzxy, vm)
    }

    state.waterScanError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissWaterScanError,
            title = { Text("扫码识别失败") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = vm::dismissWaterScanError) { Text("知道了") } },
        )
    }

    state.scannedWaterDevice?.let { device ->
        AlertDialog(
            onDismissRequest = vm::dismissScannedWaterDevice,
            title = { Text("识别到饮水机") },
            text = { Text("${device.goodsName}\n\n选择后，点首页「开水」即可使用。") },
            confirmButton = {
                TextButton(onClick = {
                    selectedDevice = device
                    vm.selectScannedWaterDevice()
                    vm.dismissUnlockFlow()
                }) { Text("选择这台设备") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissScannedWaterDevice) { Text("取消") }
            },
        )
    }

    // 开水设备选择弹层
    if (showDeviceSheet) {
        WaterDeviceSheet(
            state = state,
            selectedDevice = selectedDevice,
            onSelectDevice = {
                selectedDevice = it
                showDeviceSheet = false
            },
            onDismiss = { showDeviceSheet = false },
            haptic = haptic,
            context = ctx,
        )
    }

    // 开水成功详情弹窗（点"订单详情"查看）
    val successResult = when (val flow = state.unlockFlowState) {
        is UnlockFlowState.Success -> flow.result
        is UnlockFlowState.Pending -> flow.result
        else -> null
    }
    if (successResult != null && showWaterDetail) {
        AlertDialog(
            onDismissRequest = { showWaterDetail = false },
            title = { Text(if (successResult.usageConfirmed) "使用已结束" else "状态待确认", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    successResult.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    DetailRow("订单原价", if (successResult.originPrice == "-") "待确认" else "¥${successResult.originPrice}")
                    DetailRow("花费小票", successResult.ticketCost)
                    if (successResult.integralCost != "-") DetailRow("积分抵扣", successResult.integralCost)
                    successResult.otherPromotions.forEach { p ->
                        DetailRow("其他优惠", p.discountAmount ?: "-")
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    DetailRow("订单号", successResult.orderNo)
                }
            },
            confirmButton = {
                TextButton(onClick = { showWaterDetail = false }) {
                    Text("关闭")
                }
            },
        )
    }
    val failedState = state.unlockFlowState as? UnlockFlowState.Failed
    if (failedState != null && showWaterDetail) {
        AlertDialog(
            onDismissRequest = { showWaterDetail = false },
            title = { Text("开水失败", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(failedState.message, style = MaterialTheme.typography.bodyMedium)
                    if (failedState.step != "未知") {
                        Text("失败步骤：${failedState.step}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    failedState.suggestions.forEach { s ->
                        Text("• $s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWaterDetail = false }) {
                    Text("关闭")
                }
            },
        )
    }

    // 开水使用说明弹窗：提示扫码登记与免密签约两个前提
    if (showWaterHelp) {
        AlertDialog(
            onDismissRequest = { showWaterHelp = false },
            title = { Text("开水使用说明", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text(
                        "本机列表来自「胖乖生活」账户的「最近使用」记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("• 新设备可点首页「扫码喝水」，扫描机身上的胖乖二维码，识别并选择后再点「开水」。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("• 开水为「后付费」，需先在官方 App 开通支付宝免密支付，否则会在「开通后付」步骤报错。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showWaterHelp = false }) {
                    Text("知道了")
                }
            },
        )
    }
}

/** 顶栏下的一条账户状态行：小票余额 + 累计开水 + 签到按钮 */
@Composable
private fun AccountStripRow(state: AppUiState, vm: AppViewModel, haptic: HapticFeedback) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ticket = state.balance?.ticketText?.let { "¥$it" } ?: "-"
            Text(
                "小票 $ticket · 累计开水 ${state.totalWaterCount} 次",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    if (state.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.signInNow()
                },
                enabled = !state.signInDoneToday && !state.signingIn,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp),
                colors = if (state.signInDoneToday) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                if (state.signingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (state.signingIn) "签到中…" else if (state.signInDoneToday) "已签到" else "签到",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(Spacings.sm))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

private enum class WaterPhase { Idle, Busy, Success, Pending, Failed }

/** 开水卡：与洗澡卡统一骨架（设备行 / 状态区 / 按钮行） */
@Composable
private fun WaterCard(
    state: AppUiState,
    vm: AppViewModel,
    selectedDevice: DeviceItem?,
    onSelectDevice: (DeviceItem) -> Unit,
    onShowDetail: () -> Unit,
    onPickDevice: () -> Unit,
    onShowHelp: () -> Unit,
    haptic: HapticFeedback,
    context: android.content.Context,
) {
    val flow = state.unlockFlowState
    val phase = when (flow) {
        is UnlockFlowState.Idle -> WaterPhase.Idle
        is UnlockFlowState.Success -> WaterPhase.Success
        is UnlockFlowState.Pending -> WaterPhase.Pending
        is UnlockFlowState.Failed -> WaterPhase.Failed
        else -> WaterPhase.Busy
    }
    val deviceName = selectedDevice?.goodsName?.ifBlank { "未命名设备" } ?: "暂无设备"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShapes.cardCorner,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(Spacings.lg)) {
            // 设备选择和说明分别点击，使用过程中仍可查看说明。
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = state.devices.isNotEmpty() && phase == WaterPhase.Idle) {
                            if (state.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPickDevice()
                        }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(deviceName, style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (state.devices.isNotEmpty()) {
                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "选择设备",
                            modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (state.devices.isEmpty()) {
                    TextButton(onClick = { vm.refreshDevices() }) { Text("刷新") }
                }
                IconButton(onClick = {
                    if (state.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onShowHelp()
                }) {
                    Icon(Icons.Outlined.Info, contentDescription = "开水说明",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ── 行2 状态区（原地切换，带过渡动画） ──
            Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
                AnimatedContent(
                    targetState = flow,
                    contentKey = { it::class },
                    transitionSpec = {
                        (fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 8 })
                            .togetherWith(fadeOut(tween(140)))
                    },
                    label = "waterInfo",
                ) { contentFlow ->
                    // 动画退出帧仍持有旧状态，必须读取动画提供的快照，避免强转到新状态时崩溃。
                    val p = when (contentFlow) {
                        is UnlockFlowState.Idle -> WaterPhase.Idle
                        is UnlockFlowState.Success -> WaterPhase.Success
                        is UnlockFlowState.Pending -> WaterPhase.Pending
                        is UnlockFlowState.Failed -> WaterPhase.Failed
                        else -> WaterPhase.Busy
                    }
                    Column(modifier = Modifier.padding(top = Spacings.md)) {
                        when (p) {
                            WaterPhase.Idle -> Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("使用积分抵扣", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text("开启后开水优先用积分抵扣", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = state.usePointsForUnlock,
                                    onCheckedChange = {
                                        if (state.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        vm.toggleUsePointsForUnlock()
                                    },
                                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
                                )
                            }
                            WaterPhase.Busy -> Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    formatClock(state.unlockElapsedSeconds),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        when (val f = contentFlow) {
                                            is UnlockFlowState.PreChecking -> f.step
                                            is UnlockFlowState.Working -> f.step
                                            else -> "正在处理…"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            WaterPhase.Success -> {
                                val r = (contentFlow as? UnlockFlowState.Success)?.result
                                    ?: return@AnimatedContent
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        if (r.originPrice == "-") "账单待确认" else "¥${com.inonvation.campbox.data.calculateActualCost(r)}",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(successSubtitle(r), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "订单详情 ›",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clickable { onShowDetail() },
                                        )
                                    }
                                }
                            }
                            WaterPhase.Pending -> {
                                val result = (contentFlow as UnlockFlowState.Pending).result
                                Column {
                                    Text("状态待确认", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(result.note.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                    Text("请勿重复开水；订单已保留，可在胖乖生活核对。", style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = onShowDetail) { Text("查看订单") }
                                }
                            }
                            WaterPhase.Failed -> {
                                val f = contentFlow as? UnlockFlowState.Failed ?: return@AnimatedContent
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(f.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "失败步骤：${f.step}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            "查看详情 ›",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clickable { onShowDetail() },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── 行3 按钮行 ──
            Spacer(Modifier.height(Spacings.md))
            when (phase) {
                WaterPhase.Idle -> Row(horizontalArrangement = Arrangement.spacedBy(Spacings.sm)) {
                    WaterScanButton(
                        enabled = !state.unlocking,
                        loading = state.waterScanLoading,
                        onCode = { vm.resolveWaterCode(it) },
                        modifier = Modifier.weight(1f).height(44.dp),
                    )
                    Button(
                        onClick = {
                            if (state.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val device = selectedDevice
                            if (device != null) vm.unlock(device)
                        },
                        enabled = selectedDevice != null && !state.unlocking && !state.waterScanLoading,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("开水", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
                WaterPhase.Busy -> Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("开水进行中…", style = MaterialTheme.typography.titleSmall)
                }
                WaterPhase.Success, WaterPhase.Pending -> OutlinedButton(
                    onClick = { vm.dismissUnlockFlow() },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(if (phase == WaterPhase.Pending) "知道了" else "完成")
                }
                WaterPhase.Failed -> Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (state.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val device = selectedDevice
                            if (device != null) vm.unlock(device)
                        },
                        enabled = selectedDevice != null,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("重试", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(Spacings.sm))
                    OutlinedButton(
                        onClick = { vm.dismissUnlockFlow() },
                        modifier = Modifier.height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

/** 开水设备选择弹层：点选切换，长按或点 ＋ 添加到桌面 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun WaterDeviceSheet(
    state: AppUiState,
    selectedDevice: DeviceItem?,
    onSelectDevice: (DeviceItem) -> Unit,
    onDismiss: () -> Unit,
    haptic: HapticFeedback,
    context: android.content.Context,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacings.xl)
                .padding(bottom = Spacings.xxl),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("选择开水设备", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "点选切换设备；长按或点右侧 ＋ 可添加到桌面",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacings.sm),
            )
            Spacer(Modifier.height(Spacings.lg))
            if (state.devices.isEmpty()) {
                Text(
                    "暂无设备，请在主页下拉刷新",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.devices.forEach { device ->
                val isSelected = device.id == selectedDevice?.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClick = { onSelectDevice(device) },
                            onLongClick = {
                                if (state.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                pinDeviceShortcut(context, device)
                            },
                        )
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Devices,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(Spacings.sm))
                    Text(
                        device.goodsName.ifBlank { "未命名设备" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "已选择",
                            tint = AppColors.runningIndicator,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(Spacings.xs))
                    }
                    IconButton(
                        onClick = {
                            if (state.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            pinDeviceShortcut(context, device)
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = "添加到桌面",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun successSubtitle(r: com.inonvation.campbox.data.UnlockResult): String {
    if (r.originPrice == "-") return "使用已结束，费用以官方账单为准"
    val parts = buildList {
        if (r.integralCost != "-") add("积分抵扣 ${r.integralCost}")
        r.otherPromotions.forEach { p -> p.discountAmount?.let { add("其他优惠 $it") } }
    }
    return parts.joinToString(" · ").ifBlank { "小票支付" }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 校园网认证卡：账号密码输入 + 一键联网；未连 Wi-Fi 时提示 */
@Composable
private fun CampusNetCard(state: AppUiState, vm: AppViewModel, haptic: HapticFeedback) {
    val context = LocalContext.current
    var showConnectionInfo by rememberSaveable { mutableStateOf(false) }

    if (showConnectionInfo) {
        AlertDialog(
            onDismissRequest = { showConnectionInfo = false },
            title = { Text("校园网连接说明") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("请先连接校园网 Wi-Fi，再回 App 点「连接」，由 App 向校园网网关提交认证。")
                    Text("1. 打开系统 Wi-Fi 设置，选择 tyut-tsg（或所在位置的校园网 Wi-Fi）。")
                    Text("2. 等待显示「已连接」或「已连接，但无法访问互联网」。如果还显示「正在连接」，请稍等。")
                    Text("3. 回到 App，填写学号 / 上网账号和上网密码，点击「连接」。无需额外等待很久，App 会等待 Wi-Fi 就绪并尝试认证。")
                    Text("4. App 提示 Wi-Fi 外网已连通，即可上网。认证前系统提示无法访问互联网是正常现象。")
                    Text("自动认证失败时会打开官方网页登录页，并尝试填入保存的账号密码；请确认后点网页登录，完成后返回 App 检查联网。也可直接点「官方网页登录」。")
                }
            },
            confirmButton = {
                TextButton(onClick = { showConnectionInfo = false }) { Text("知道了") }
            },
        )
    }

    // 定位权限状态：SSID 识别依赖它；未授权时在卡片内给场景化的再申请入口
    var locationGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    val owner = LocalLifecycleOwner.current
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { locationGranted = hasLocationPermission(context) }
    DisposableEffect(context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) locationGranted = hasLocationPermission(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShapes.cardCorner,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(Spacings.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("一键连校园网", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (state.campusLoggingIn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "认证中", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else if (state.campusLastSuccess == true) {
                    Icon(
                        Icons.Filled.CheckCircle, contentDescription = null,
                        tint = AppColors.runningIndicator, modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = { showConnectionInfo = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = "校园网连接说明",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(Spacings.xs))
            TextButton(
                onClick = { openWifiSettings(context) },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    "没连上 Wi-Fi？打开系统 Wi-Fi 面板",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!locationGranted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "未授权定位，无法识别校园网 Wi-Fi 名称",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            locationLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                )
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "去授权",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacings.md))

            OutlinedTextField(
                value = state.campusUsername,
                onValueChange = { vm.updateCampusUsername(it) },
                label = { Text("学号 / 上网账号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next
                ),
            )
            Spacer(Modifier.height(Spacings.sm))
            OutlinedTextField(
                value = state.campusPassword,
                onValueChange = { vm.updateCampusPassword(it) },
                label = { Text("上网密码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (state.campusPasswordVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password, imeAction = ImeAction.Done
                ),
                trailingIcon = {
                    IconButton(onClick = { vm.toggleCampusPasswordVisible() }) {
                        Text(
                            if (state.campusPasswordVisible) "隐藏" else "显示",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
            if (state.campusHasSaved) {
                Spacer(Modifier.height(Spacings.xs))
                Text(
                    "账号已保存在本机，下次点「连接」即可",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Spacings.md))

            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        if (state.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.campusNetLogin()
                    },
                    enabled = !state.campusLoggingIn &&
                        state.campusUsername.isNotBlank() && state.campusPassword.isNotBlank(),
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        if (state.campusLoggingIn) "认证中..." else "连接",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (state.campusHasSaved) {
                    Spacer(Modifier.width(Spacings.sm))
                    OutlinedButton(
                        onClick = {
                            if (state.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.clearCampusCredentials()
                        },
                        modifier = Modifier.height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("清除")
                    }
                }
            }

            TextButton(onClick = vm::openCampusPortal, enabled = !state.campusLoggingIn) {
                Text("官方网页登录")
            }

            // 认证日志
            if (state.campusLog.isNotEmpty()) {
                Spacer(Modifier.height(Spacings.md))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(10.dp),
                ) {
                    state.campusLog.takeLast(6).forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (line.contains("成功") || line.contains("已连通") || line.contains("已在线") || line.contains("无需认证"))
                                AppColors.runningIndicator else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }
    }
}

/** 定位权限是否已授予（SSID 识别、开水定位均依赖） */
private fun hasLocationPermission(context: android.content.Context): Boolean = runCatching {
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
}.getOrDefault(false)

/** 打开系统 Wi-Fi 面板/设置。Android 10+ 普通应用无法代连 Wi-Fi，只能引导用户点一下 */
private fun openWifiSettings(context: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Intent(android.provider.Settings.Panel.ACTION_WIFI)
    } else {
        Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun formatClock(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}
