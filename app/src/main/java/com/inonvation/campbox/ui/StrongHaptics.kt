package com.inonvation.campbox.ui

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * 全局触感实现：直接驱动 Vibrator 播放系统「点按」原语（EFFECT_CLICK）。
 * Compose 默认走 view 的 LongPress 反馈，受系统「触摸时振动」开关控制（系统关闭时静默失效），
 * 且震感很弱；这里绕过该开关，App 内的「触感反馈」开关是唯一控制项。
 */
class StrongHaptics(private val view: View, private val enabled: () -> Boolean) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (!enabled()) return
        val vibrator = view.context.getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
