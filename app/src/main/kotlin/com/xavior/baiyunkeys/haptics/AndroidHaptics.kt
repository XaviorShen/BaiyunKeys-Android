package com.xavior.baiyunkeys.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.baiyunkeys.core.unlock.HapticsFeedback

/**
 * 触感反馈（对应 iOS `UINotificationFeedbackGenerator`）。
 *
 * 方案 §4.6 说明 Android 只做两档：成功 / 失败，比 iOS 的三档略简。
 * 总开关直接读偏好，关掉就完全不震。
 */
class AndroidHaptics(
    context: Context,
    private val enabled: () -> Boolean,
) : HapticsFeedback {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(VibratorManager::class.java)
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    override fun success() = vibrate(SUCCESS_PATTERN)

    override fun failure() = vibrate(FAILURE_PATTERN)

    private fun vibrate(pattern: LongArray) {
        if (!enabled()) return
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        runCatching {
            device.vibrate(VibrationEffect.createWaveform(pattern, NO_REPEAT))
        }
    }

    private companion object {
        const val NO_REPEAT = -1

        /** 一记短震。 */
        val SUCCESS_PATTERN = longArrayOf(0, 40)

        /** 两记短震，间隔 70ms —— 失败与成功手感明显不同。 */
        val FAILURE_PATTERN = longArrayOf(0, 25, 70, 25)
    }
}
