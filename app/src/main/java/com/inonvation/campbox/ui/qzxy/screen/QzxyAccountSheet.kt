package com.inonvation.campbox.ui.qzxy.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inonvation.campbox.ui.AppViewModel
import com.inonvation.campbox.ui.qzxy.QzxyAccountPage
import com.inonvation.campbox.ui.qzxy.QzxyUiState
import com.inonvation.campbox.ui.qzxy.QzxyUseCodeAction
import com.inonvation.campbox.ui.theme.Spacings

/** 趣智账户信息保持一条状态行，查询不依赖胖乖登录或热水器绑定。 */
@Composable
fun QzxyAccountActions(q: QzxyUiState, vm: AppViewModel) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = when {
                q.loadingWallet -> "钱包查询中…"
                q.walletError != null -> "钱包查询失败"
                else -> "钱包 ${q.wallet?.balanceText ?: "—"}"
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = vm::qzxyShowWallet) { Text("查询余额") }
        TextButton(onClick = vm::qzxyShowUseCode) { Text("使用码") }
    }
}

/** 查询、失败与领取预览均在同一弹层原地展示，避免叠加对话框。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QzxyAccountSheet(q: QzxyUiState, vm: AppViewModel) {
    val page = q.accountPage ?: return
    val busy = q.useCodeAction != null
    val currentBusy by rememberUpdatedState(busy)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !currentBusy },
    )
    ModalBottomSheet(
        onDismissRequest = vm::qzxyDismissAccount,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = Spacings.lg).padding(bottom = Spacings.xl),
            verticalArrangement = Arrangement.spacedBy(Spacings.md),
        ) {
            Text(
                if (page == QzxyAccountPage.Wallet) "趣智钱包余额" else "趣智校园使用码",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                listOf(q.userName, q.accountPhone).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (page == QzxyAccountPage.Wallet) WalletContent(q, vm) else UseCodeContent(q, vm)
            TextButton(onClick = vm::qzxyDismissAccount, enabled = !busy, modifier = Modifier.align(Alignment.End)) {
                Text("关闭")
            }
        }
    }
}

private enum class QueryPhase { Loading, Error, Ready, Empty }

@Composable
private fun WalletContent(q: QzxyUiState, vm: AppViewModel) {
    val phase = when {
        q.loadingWallet -> QueryPhase.Loading
        q.walletError != null -> QueryPhase.Error
        q.wallet?.balanceText != null -> QueryPhase.Ready
        else -> QueryPhase.Empty
    }
    Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        AnimatedContent(targetState = phase, label = "walletQuery") { current ->
            when (current) {
                QueryPhase.Loading -> QueryLoading("正在查询趣智钱包…")
                QueryPhase.Error -> Text(q.walletError ?: "查询失败，请重试", color = MaterialTheme.colorScheme.error)
                QueryPhase.Empty -> Text("平台未返回钱包余额，请稍后重试", color = MaterialTheme.colorScheme.onSurfaceVariant)
                QueryPhase.Ready -> Text(
                    q.wallet?.balanceText ?: "—",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
    Button(onClick = { vm.qzxyRefreshWallet() }, enabled = !q.loadingWallet, modifier = Modifier.fillMaxWidth()) {
        Text(if (q.loadingWallet) "查询中…" else if (q.walletError != null) "重新查询" else "刷新余额")
    }
}

@Composable
private fun UseCodeContent(q: QzxyUiState, vm: AppViewModel) {
    val data = q.useCodeData
    val busy = q.useCodeAction != null || q.loadingUseCode
    val phase = when {
        q.loadingUseCode -> QueryPhase.Loading
        data != null -> QueryPhase.Ready
        q.useCodeError != null -> QueryPhase.Error
        else -> QueryPhase.Empty
    }
    Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        AnimatedContent(targetState = phase, label = "useCodeQuery") { current ->
            when (current) {
                QueryPhase.Loading -> QueryLoading("正在查询使用码…")
                QueryPhase.Error -> Text("暂未取得使用码，请重新查询", color = MaterialTheme.colorScheme.onSurfaceVariant)
                QueryPhase.Empty -> Text(if (busy) "正在确认使用码…" else "请查询使用码")
                QueryPhase.Ready -> Column(verticalArrangement = Arrangement.spacedBy(Spacings.sm)) {
                    SelectionContainer {
                        Text(data?.code ?: "尚未领取使用码", style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold)
                    }
                    if (data?.code != null) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (data.useCodeStatus) {
                                    1 -> "已开启，可在设备键盘输入"
                                    0 -> "已关闭，开启后才能使用"
                                    else -> "状态未知，请刷新确认"
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Switch(checked = data.enabled, onCheckedChange = vm::qzxySetUseCodeEnabled,
                                enabled = !busy && q.useCodeCandidate == null && data.useCodeStatus in listOf(0, 1))
                        }
                    }
                }
            }
        }
    }
    q.useCodeError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (q.useCodeAction != null) {
        QueryLoading(when (q.useCodeAction) {
            QzxyUseCodeAction.Generate -> "正在生成候选码…"
            QzxyUseCodeAction.Claim -> "正在领取并确认使用码…"
            QzxyUseCodeAction.Enable -> "正在开启使用码…"
            QzxyUseCodeAction.Disable -> "正在关闭使用码…"
        })
    }
    Text(
        "在支持使用码的热水器键盘上输入，按机身提示使用。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (q.useCodeCandidate != null) {
        HorizontalDivider()
        Text("待领取的新码", style = MaterialTheme.typography.titleSmall)
        Text(q.useCodeCandidate, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            if (q.useCodeSecondsLeft > 0) "请在 ${q.useCodeSecondsLeft / 60}分${q.useCodeSecondsLeft % 60}秒内领取，领取后替换原码"
            else "候选码已过期，请换一个",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        q.useCodeRemainingGenerations?.let {
            Text("今日还能换 $it 次", style = MaterialTheme.typography.bodySmall)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacings.sm)) {
            OutlinedButton(onClick = vm::qzxyGenerateUseCode,
                enabled = !busy && q.useCodeRemainingGenerations != 0, modifier = Modifier.weight(1f)) { Text("换一个") }
            Button(onClick = vm::qzxyClaimUseCode, enabled = !busy && q.useCodeSecondsLeft > 0,
                modifier = Modifier.weight(1f)) { Text("确定领取") }
        }
        TextButton(onClick = vm::qzxyDiscardUseCodeCandidate, enabled = !busy) { Text("取消领取，保留原码") }
    } else {
        if (data != null && !data.canClaim) {
            Text(data.resetAvailabilityWarMark ?: "每天只能领取一次使用码",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (q.useCodeRemainingGenerations == 0) {
            Text("今日换码次数已用完，请明天再试",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (data != null) {
            Text("每天可领取一次；先生成候选码，再点「确定领取」。换码每天最多 20 次。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacings.sm)) {
            OutlinedButton(onClick = { vm.qzxyLoadUseCode() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(if (q.useCodeError != null) "重新查询" else "刷新使用码")
            }
            Button(onClick = vm::qzxyGenerateUseCode,
                enabled = !busy && data != null && data.canClaim && q.useCodeRemainingGenerations != 0,
                modifier = Modifier.weight(1f)) {
                Text(if (data?.code == null) "领取使用码" else "重新领取")
            }
        }
    }
}

@Composable
private fun QueryLoading(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacings.sm)) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
