package com.xavior.baiyunkeys.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 往系统剪贴板写一段文字，返回是否成功。
 *
 * 不用 Compose 的 `LocalClipboard` + `ClipEntry`：那条路是挂起函数、内部还会走 provider，
 * 失败时异常被协程吞掉，界面照样显示「已复制」，用户粘贴出来却是旧内容
 * （在模拟器上实测到过）。这里直接用 [ClipboardManager]，同步返回结果，失败能当场告诉用户。
 */
@Composable
fun rememberClipboardWriter(): (label: String, text: String) -> Boolean {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }
    return remember(manager) {
        { label, text ->
            if (manager == null) {
                false
            } else {
                runCatching {
                    manager.setPrimaryClip(ClipData.newPlainText(label, text))
                    true
                }.getOrDefault(false)
            }
        }
    }
}
