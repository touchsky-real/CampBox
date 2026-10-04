package com.inonvation.lightlife.ui.screen

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.inonvation.lightlife.data.DeviceItem
import com.inonvation.lightlife.ui.AppUiState
import com.inonvation.lightlife.ui.AppViewModel
import com.inonvation.lightlife.ui.UnlockFlowState
import com.inonvation.lightlife.ui.pinDeviceShortcut
import com.inonvation.lightlife.ui.qzxy.screen.QzxyShowerSection
import com.inonvation.lightlife.ui.theme.AppColors
import com.inonvation.lightlife.ui.theme.CardShapes
import com.inonvation.lightlife.ui.theme.Spacings

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
        if (selectedDevice == null || state.devices.none { it.id == selectedDevice!!.id }) {
            selectedDevice = state.devices.firstOrNull()
        }
    }

    val pullRefreshState = rememberPullToRefreshState()
    val isRefreshing = state.loadingBalance || state.loadingDevices

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
                Text("LightLife", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                contentPadding = PaddingValues(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 12.dp),
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
                    return@LazyColumn
                }

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

                // 校园网
                item { SectionLabel("校园网", subtitle = "学校 Dr.COM 上网认证，用学号登录，与胖乖、趣智校园账号互不相关") }
                item { CampusNetCard(state, vm, haptic) }

                // 积分任务
                item { SectionLabel("积分任务", subtitle = "刷的是胖乖生活积分，可用于抵扣开水费用") }
                item { PointsTaskCard(state, vm, haptic) }

                // 洗澡（趣智校园）
                item { SectionLabel("洗澡", subtitle = "宿舍淋浴服务来自「趣智校园」，需另注册趣智账号，与胖乖无关") }
                item { QzxyShowerSection(state = state, vm = vm, haptic = haptic) }

                item {
                    Text(
                        text = "LightLife v${state.appVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = Spacings.lg),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
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
    val successResult = (state.unlockFlowState as? UnlockFlowState.Success)?.result
    if (successResult != null && showWaterDetail) {
        AlertDialog(
            onDismissRequest = { showWaterDetail = false },
            title = { Text("开水成功", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    DetailRow("订单原价", "¥${successResult.originPrice}")
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
                    Text("• 新饮水机需先在官方 App 扫描机身二维码登记一次，之后才会出现在这里。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("• 开水为「后付费」，需先在官方 App 开通支付宝免密支付，否则会在「开通后付」步骤报错。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("• 已开通但不想被自动扣款：可在支付宝「设置 → 支付设置 → 免密支付」中调低限额。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private enum class WaterPhase { Idle, Busy, Success, Failed }

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
            // ── 行1 设备行（右端含 info 图标说明使用前提） ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(deviceName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        if (state.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onShowHelp()
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = "开水说明",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                if (state.devices.isNotEmpty()) {
                    // 选择设备的下拉箭头，参与设备行点击
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = phase == WaterPhase.Idle) { onPickDevice() }
                            .padding(4.dp),
                    ) {
                        if (phase == WaterPhase.Idle) {
                            Icon(
                                Icons.Outlined.KeyboardArrowDown,
                                contentDescription = "选择设备",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.width(Spacings.xs))
                    Text(
                        "刷新",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { vm.refreshDevices() },
                    )
                }
            }

            // ── 行2 状态区（原地切换，带过渡动画） ──
            Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
                AnimatedContent(
                    targetState = phase,
                    transitionSpec = {
                        (fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 8 })
                            .togetherWith(fadeOut(tween(140)))
                    },
                    label = "waterInfo",
                ) { p ->
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
                                        when (val f = state.unlockFlowState) {
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
                                val r = (state.unlockFlowState as UnlockFlowState.Success).result
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        r.ticketCost.ifBlank { "¥${r.originPrice}" },
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
                            WaterPhase.Failed -> {
                                val f = state.unlockFlowState as UnlockFlowState.Failed
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
                WaterPhase.Idle -> Button(
                    onClick = {
                        if (state.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val device = selectedDevice
                        if (device != null) vm.unlock(device)
                    },
                    enabled = selectedDevice != null && !state.unlocking,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("开水", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                WaterPhase.Busy -> Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("开水进行中…", style = MaterialTheme.typography.titleSmall)
                }
                WaterPhase.Success -> OutlinedButton(
                    onClick = { vm.dismissUnlockFlow() },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("完成")
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
            Text("选择开水设备", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
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

private fun successSubtitle(r: com.inonvation.lightlife.data.UnlockResult): String {
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
                Text("一键连校园网", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
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
            }
            Spacer(Modifier.height(Spacings.xs))
            Text(
                "自动向校园网网关提交认证。请先连接校园网 Wi-Fi，再点「连接」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

private fun formatClock(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}

/** 积分任务卡：未运行时展示说明+启动按钮；运行中展示实时日志+暂停/停止 */
@Composable
private fun PointsTaskCard(state: AppUiState, vm: AppViewModel, haptic: HapticFeedback) {
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
                Text("刷积分", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (state.pointsRunning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (state.pointsPaused) "已暂停" else "运行中",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacings.sm))

            if (!state.pointsRunning && state.pointsLog.isEmpty()) {
                Text(
                    "自动完成签到、首页浏览和浏览类任务；广告、视频类任务需真实观看，无法自动完成",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacings.md))
                Button(
                    onClick = {
                        if (state.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.startPointsTask()
                    },
                    enabled = state.hasToken,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("开始刷积分", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            } else {
                // 实时日志（最多展示最近 8 条）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp, max = 220.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(10.dp),
                ) {
                    val shown = state.pointsLog.takeLast(8)
                    shown.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                }
                Spacer(Modifier.height(Spacings.md))
                if (state.pointsRunning) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                if (state.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.togglePausePointsTask()
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(if (state.pointsPaused) "继续" else "暂停")
                        }
                        Spacer(Modifier.width(Spacings.sm))
                        Button(
                            onClick = {
                                if (state.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.stopPointsTask()
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text("停止", color = MaterialTheme.colorScheme.onError)
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            if (state.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.clearPointsLog()
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("清除记录，重新开始")
                    }
                }
            }
        }
    }
}
