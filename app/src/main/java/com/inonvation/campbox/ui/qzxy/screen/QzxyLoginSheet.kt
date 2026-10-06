package com.inonvation.campbox.ui.qzxy.screen

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.inonvation.campbox.ui.AppViewModel
import com.inonvation.campbox.ui.qzxy.QzxyUiState
import com.inonvation.campbox.ui.theme.Spacings

/** 趣智独立账号登录：默认短信验证码，可切换密码登录。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QzxyLoginSheet(qzxy: QzxyUiState, vm: AppViewModel) {
    val busy = qzxy.loggingIn || qzxy.sendingSms
    val currentBusy by rememberUpdatedState(busy)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !currentBusy })
    ModalBottomSheet(onDismissRequest = { vm.qzxyDismissLogin() }, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacings.xl)
                .padding(bottom = Spacings.xxl),
        ) {
            Text("连接趣智校园", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(Spacings.sm))
            Text(
                if (qzxy.smsLogin) "使用短信验证码登录趣智校园；未注册的手机号将自动创建趣智账号。"
                else "使用趣智校园账号的手机号和密码登录；没设置密码可切换验证码登录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacings.lg))

            Row(horizontalArrangement = Arrangement.spacedBy(Spacings.sm)) {
                FilterChip(selected = qzxy.smsLogin, onClick = { vm.qzxySetSmsLogin(true) },
                    enabled = !busy, label = { Text("验证码登录") })
                FilterChip(selected = !qzxy.smsLogin, onClick = { vm.qzxySetSmsLogin(false) },
                    enabled = !busy, label = { Text("密码登录") })
            }
            Spacer(Modifier.height(Spacings.sm))
            OutlinedTextField(
                value = qzxy.phone,
                onValueChange = { vm.qzxyUpdatePhone(it) },
                label = { Text("手机号") },
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.height(Spacings.md))
            if (qzxy.smsLogin) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacings.sm)) {
                    OutlinedTextField(
                        value = qzxy.smsCode,
                        onValueChange = vm::qzxyUpdateSmsCode,
                        label = { Text("6 位验证码") },
                        enabled = !busy,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                    )
                    OutlinedButton(
                        onClick = { vm.qzxySendLoginSms() },
                        enabled = !busy && qzxy.smsSecondsLeft == 0 && qzxy.phone.length == 11,
                    ) {
                        Text(when {
                            qzxy.sendingSms -> "发送中…"
                            qzxy.smsSecondsLeft > 0 -> "${qzxy.smsSecondsLeft}秒后重发"
                            else -> "获取验证码"
                        })
                    }
                }
            } else {
                OutlinedTextField(
                    value = qzxy.password,
                    onValueChange = { vm.qzxyUpdatePassword(it) },
                    label = { Text("密码") },
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    visualTransformation = if (qzxy.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { vm.qzxyTogglePasswordVisible() }) {
                            Icon(
                                imageVector = if (qzxy.passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (qzxy.passwordVisible) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                )
            }
            qzxy.loginError?.let { error ->
                Spacer(Modifier.height(Spacings.sm))
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(Spacings.lg))

            Button(
                onClick = { vm.qzxyLogin() },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                if (qzxy.loggingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(Spacings.sm))
                }
                Text(if (qzxy.loggingIn) "登录中…" else "登录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(Spacings.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "登录即表示在另一设备上使用本账号会互踢下线",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
