package com.inonvation.campbox.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri as AndroidUri
import com.inonvation.campbox.ui.AppUiState
import com.inonvation.campbox.ui.AppViewModel
import com.inonvation.campbox.ui.theme.AppColors
import com.inonvation.campbox.ui.theme.CardShapes
import com.inonvation.campbox.ui.theme.ColorTheme
import com.inonvation.campbox.ui.theme.Spacings
import com.inonvation.campbox.ui.theme.ThemeMode
import com.inonvation.campbox.ui.theme.label
import com.inonvation.campbox.ui.theme.swatchColor

/**
 * 设置页：按 关于 / 外观 / 喝水·胖乖生活 / 校园网 / 洗澡·趣智校园 / 通用 / 调试 分组，
 * 每个平台的账号管理与该平台的功能开关放在一起，调试入口集中在最后。
 */
@Composable
fun SettingsScreen(state: AppUiState, vm: AppViewModel) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val currentMode = state.themeMode
    var showDisclaimerDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
            .navigationBarsPadding()
    ) {
        SettingsTopBar(
            title = "设置",
            onBack = { vm.dismissSettings() },
            hapticEnabled = state.hapticEnabled,
        )
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .padding(horizontal = Spacings.xl)
                .size(width = 36.dp, height = 3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        val scrollState = rememberScrollState()

        LaunchedEffect(scrollState) {
            var lastEdgeTrigger = 0L
            var started = false
            snapshotFlow { scrollState.canScrollBackward to scrollState.canScrollForward }
                .collect { pair ->
                    if (!started) { started = true; return@collect }
                    val atEdge = pair.first == false || pair.second == false
                    if (atEdge) {
                        val now = System.currentTimeMillis()
                        if (now - lastEdgeTrigger > 500) {
                            lastEdgeTrigger = now
                            if (vm.state.value.hapticEnabled)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // ═══ 关于 ═══
            SectionLabel("关于")
            Spacer(Modifier.height(Spacings.sm))
            StandardCard {
                Column {
                    // 版本行：点击右侧箭头跳转官网
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, AndroidUri.parse(REPO_URL)),
                                )
                            },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("版本", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "CampBox v${state.appVersion}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = "前往官网",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    // 检查更新行
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (state.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.checkUpdate()
                            },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("检查更新", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                if (state.updateChecking) "正在检查更新…" else "获取最新版本与下载地址",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.updateChecking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacings.lg))

            // ═══ 外观 ═══
            SectionLabel("外观")
            Spacer(Modifier.height(Spacings.sm))
            StandardCard {
                Column {
                    Text("主题模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("应用的明暗主题", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色").forEach { (mode, label) ->
                            FilterChip(
                                selected = currentMode == mode,
                                onClick = { if (state.hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.updateThemeMode(mode) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text("主题配色", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("点击色块立即预览", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        modifier = Modifier.selectableGroup(),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                    ) {
                        ColorTheme.entries.forEach { theme ->
                            ThemeColorSwatch(
                                color = theme.swatchColor(),
                                label = theme.label,
                                selected = state.colorTheme == theme,
                                hapticEnabled = state.hapticEnabled,
                                onClick = { vm.updateColorTheme(theme) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    SettingSwitchRow(
                        title = "触感反馈",
                        subtitle = "操作按钮和开关时轻微振动",
                        checked = state.hapticEnabled,
                        onCheckedChange = { vm.toggleHaptic() },
                        hapticEnabled = state.hapticEnabled,
                    )
                }
            }

            // ═══ 喝水 · 胖乖生活 ═══
            SectionLabel("喝水 · 胖乖生活", subtitle = "开水与积分属于此平台；账号为手机号注册")
            Spacer(Modifier.height(Spacings.sm))
            AccountCard(
                platform = "胖乖生活账号",
                loggedIn = state.hasToken,
                accountLine = if (state.phone.length == 11) {
                    state.phone.take(3) + "****" + state.phone.takeLast(4)
                } else {
                    state.phone
                },
                onAction = {
                    if (state.hasToken) vm.showLogoutConfirm() else vm.dismissSettings()
                },
                hapticEnabled = state.hapticEnabled,
            )
            Spacer(Modifier.height(Spacings.sm))
            StandardCard {
                Column {
                    SettingSwitchRow(
                        title = "启动时自动签到",
                        subtitle = "打开 App 时自动完成每日签到",
                        checked = state.autoSignInEnabled,
                        onCheckedChange = { vm.toggleAutoSignIn() },
                        hapticEnabled = state.hapticEnabled,
                    )
                }
            }

            // ═══ 校园网 ═══
            SectionLabel("校园网", subtitle = "学校 Dr.COM 上网认证，用学号登录，与前两个平台账号互不相关")
            Spacer(Modifier.height(Spacings.sm))
            StandardCard {
                Column {
                    SettingSwitchRow(
                        title = "启动时自动连校园网",
                        subtitle = "自动用已保存的账号认证",
                        checked = state.autoCampusNetEnabled,
                        onCheckedChange = { vm.toggleAutoCampusNet() },
                        hapticEnabled = state.hapticEnabled,
                    )
                }
            }

            // ═══ 洗澡 · 趣智校园 ═══
            SectionLabel("洗澡 · 趣智校园", subtitle = "宿舍淋浴属于此平台，需另注册趣智账号，与胖乖无关")
            Spacer(Modifier.height(Spacings.sm))
            AccountCard(
                platform = "趣智校园账号",
                loggedIn = state.qzxy.loggedIn,
                accountLine = listOf(state.qzxy.userName, state.qzxy.accountPhone)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                onAction = {
                    if (state.qzxy.loggedIn) {
                        vm.qzxyShowLogoutConfirm()
                    } else {
                        vm.dismissSettings()
                        vm.qzxyShowLogin()
                    }
                },
                hapticEnabled = state.hapticEnabled,
            )
            Spacer(Modifier.height(Spacings.sm))
            StandardCard {
                Column {
                    ClickableRow(
                        title = "查询钱包余额",
                        subtitle = if (state.qzxy.loggedIn) "趣智钱包 ${state.qzxy.wallet?.balanceText ?: "—"}" else "登录趣智账号后查询",
                        onClick = {
                            vm.dismissSettings()
                            vm.qzxyShowWallet()
                        },
                        hapticEnabled = state.hapticEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = Spacings.md))
                    ClickableRow(
                        title = "使用码",
                        subtitle = "查看、领取或开关热水器键盘使用码",
                        onClick = {
                            vm.dismissSettings()
                            vm.qzxyShowUseCode()
                        },
                        hapticEnabled = state.hapticEnabled,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = Spacings.md))
                    ClickableRow(
                        title = "绑定设备",
                        subtitle = state.qzxy.boundDevice?.name ?: "未绑定，去扫描或手输 MAC",
                        onClick = {
                            vm.dismissSettings()
                            vm.qzxySetDevicePicker(true)
                        },
                        hapticEnabled = state.hapticEnabled,
                    )
                }
            }

            // ═══ 通用 ═══
            SectionLabel("通用")
            Spacer(Modifier.height(Spacings.sm))
            StandardCard {
                Column {
                    SettingSwitchRow(
                        title = "显示首页快捷方式",
                        subtitle = "在主界面顶部显示常用链接卡片",
                        checked = state.quickLinksEnabled,
                        onCheckedChange = { vm.toggleQuickLinksEnabled() },
                        hapticEnabled = state.hapticEnabled,
                    )
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    ClickableRow(
                        title = "管理快捷链接",
                        subtitle = "编辑链接、图标与桌面快捷方式",
                        onClick = { vm.showQuickLinksSettings() },
                        hapticEnabled = state.hapticEnabled,
                    )
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    AboutLink(
                        title = "免责声明",
                        subtitle = "使用即代表同意以下条款",
                        isError = true,
                        onClick = { showDisclaimerDialog = true }
                    )
                }
            }

            // ═══ 调试 ═══
            SectionLabel("调试")
            Spacer(Modifier.height(Spacings.sm))
            StandardCard {
                Column {
                    ClickableRow(
                        title = "我的 Token",
                        subtitle = "当前胖乖生活登录凭证",
                        onClick = { vm.showCurrentToken() },
                        hapticEnabled = state.hapticEnabled,
                    )
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    ClickableRow(
                        title = "设备信息",
                        subtitle = "请求所用的客户端标识，反馈问题时可提供",
                        onClick = { vm.showCurrentDeviceInfo() },
                        hapticEnabled = state.hapticEnabled,
                    )
                }
            }

            Spacer(Modifier.height(Spacings.xxl))
        }
    }

    state.tokenDialogText?.let { TokenDialog(token = it, title = "我的 Token", onDismiss = vm::dismissCurrentToken) }
    state.deviceInfoDialogText?.let { TokenDialog(token = it, title = "设备信息", onDismiss = vm::dismissCurrentDeviceInfo) }

    if (showDisclaimerDialog) {
        InfoDialog(
            title = "免责声明",
            titleColor = MaterialTheme.colorScheme.error,
            subtitle = "使用即代表同意以下条款，请仔细阅读",
            content = listOf(
                "本项目为个人兴趣开发，仅供学习和测试使用。",
                "自动签到与开水功能模拟正常用户操作流程，可能违反相关平台服务条款。",
                "请自行承担账号、设备、接口变更和平台规则风险。",
                "可能面临账户积分清零、永久无法使用积分甚至封号的风险。",
                "本人概不承担因此产生的任何责任。"
            ),
            onDismiss = { showDisclaimerDialog = false }
        )
    }

    // 发现新版本弹窗：展示更新说明，确认后经国内镜像下载
    state.updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = vm::dismissUpdateDialog,
            title = { Text("发现新版本 v${info.version}") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        info.notes.ifBlank { "暂无更新说明" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (info.downloadUrl.isBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "未找到 APK 附件，请前往发布页手动下载",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissUpdateDialog()
                    val target = info.downloadUrl.ifBlank { info.releaseUrl }
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, AndroidUri.parse(target)))
                    }
                }) { Text("立即更新") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissUpdateDialog) { Text("下次再说") }
            },
        )
    }
}

/** 平台账号卡：显示登录状态，登录后操作为退出，未登录为去登录 */
@Composable
private fun AccountCard(
    platform: String,
    loggedIn: Boolean,
    accountLine: String,
    onAction: () -> Unit,
    hapticEnabled: Boolean,
) {
    val haptic = LocalHapticFeedback.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CardShapes.cardCorner,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(Spacings.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(platform, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    if (loggedIn) "已登录" else "未登录",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (loggedIn) AppColors.runningIndicator else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loggedIn && accountLine.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    accountLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (loggedIn) "退出登录" else "去登录",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (loggedIn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onAction()
                    }
                    .padding(vertical = 2.dp),
            )
        }
    }
}

/** 配色选择器色块：OneUI 风格的圆形色板，选中的显示主题色描边与勾 */
@Composable
private fun ThemeColorSwatch(
    color: Color,
    label: String,
    selected: Boolean,
    hapticEnabled: Boolean,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .semantics { contentDescription = label }
            .selectable(selected = selected, role = Role.RadioButton) {
                if (hapticEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(4.dp)
            .border(
                width = 2.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .padding(3.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// 仓库主页：设置页版本号点击跳转
private const val REPO_URL = "https://github.com/touchsky-real/light-life"
