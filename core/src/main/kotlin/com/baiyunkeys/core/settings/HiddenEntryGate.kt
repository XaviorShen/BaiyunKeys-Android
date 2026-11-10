package com.baiyunkeys.core.settings

import java.util.UUID

/**
 * 「配置」页隐藏入口的连点状态机（对应 iOS RootView 的连点逻辑）。
 *
 * - 连点 10 次 → 弹访问码；输入正确 → 显示「自动获取配置」
 * - 已显示时连点 5 次 → 立刻隐藏
 *
 * 纯逻辑，方便单测；UI 只负责把点击事件喂进来。
 */
class HiddenEntryGate(
    private val revealTaps: Int = 10,
    private val hideTaps: Int = 5,
) {

    sealed interface Result {
        data object Ignored : Result

        /** 该弹访问码输入框了。 */
        data object AskForCode : Result

        /** 访问码正确：显示。 */
        data object Reveal : Result

        /** 连点够了：隐藏。 */
        data object Hide : Result
    }

    private var taps = 0

    fun reset() {
        taps = 0
    }

    /** 用户点了一次标题。 */
    fun onTap(visibleNow: Boolean): Result {
        taps++
        if (visibleNow) {
            if (taps >= hideTaps) {
                taps = 0
                return Result.Hide
            }
            return Result.Ignored
        }
        if (taps >= revealTaps) {
            taps = 0
            return Result.AskForCode
        }
        return Result.Ignored
    }

    /** 用户提交了访问码。 */
    fun onSubmitCode(code: String, expected: String = AppSettings.AUTO_FETCH_ACCESS_CODE): Result =
        if (code.trim() == expected) Result.Reveal else Result.Ignored

    companion object {
        /** 生成一次性的设备 udid（只在安装期内保持不变）。 */
        fun newUdid(): String = UUID.randomUUID().toString()
    }
}
