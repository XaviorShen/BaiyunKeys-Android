package com.baiyunkeys.core.unlock

/**
 * 开锁流程的阶段，对应 iOS `UnlockService.Phase`。
 *
 * `idle → preparing → scanning → connecting → negotiating → waiting → success/failure`
 * 中途任何一步失败都会落到 [Failure]（静默模式下失败直接回 [Idle]）。
 */
sealed interface UnlockPhase {

    data object Idle : UnlockPhase
    data object Preparing : UnlockPhase
    data object Scanning : UnlockPhase
    data object Connecting : UnlockPhase
    data object Negotiating : UnlockPhase
    data object Waiting : UnlockPhase
    data object Success : UnlockPhase
    data class Failure(val message: String) : UnlockPhase

    /** 流程进行中（UI 据此禁用按钮、阻止重入）。 */
    val isBusy: Boolean
        get() = this is Preparing || this is Scanning || this is Connecting ||
            this is Negotiating || this is Waiting
}
