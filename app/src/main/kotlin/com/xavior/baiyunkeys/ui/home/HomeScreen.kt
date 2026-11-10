package com.xavior.baiyunkeys.ui.home

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baiyunkeys.core.model.DoorConfig
import com.baiyunkeys.core.unlock.BleState
import com.baiyunkeys.core.unlock.UnlockLog
import com.baiyunkeys.core.unlock.UnlockPhase
import com.baiyunkeys.core.unlock.UnlockRecord
import com.xavior.baiyunkeys.ui.components.BaiyunCard
import com.xavior.baiyunkeys.ui.components.CardHeader
import com.xavior.baiyunkeys.ui.components.EmptyStateView
import com.xavior.baiyunkeys.ui.components.FilledButton
import com.xavior.baiyunkeys.ui.components.KeyValueRow
import com.xavior.baiyunkeys.ui.components.QuietButton
import com.xavior.baiyunkeys.ui.components.UnlockButton
import com.xavior.baiyunkeys.ui.theme.BaiyunDimens
import com.xavior.baiyunkeys.ui.theme.LocalBaiyunPalette
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首页：门禁选择 + 开锁大按钮 + 参数卡 + 运行日志 + 开锁记录。
 *
 * 对应 iOS `HomeView`。长辈模式会隐藏日志 / 记录 / 门禁选择器（`elderHides*`）。
 */
@Composable
fun HomeScreen(
    doors: List<DoorConfig>,
    selected: DoorConfig?,
    phase: UnlockPhase,
    statusText: String,
    logs: List<UnlockLog>,
    history: List<UnlockRecord>,
    bluetoothState: BleState,
    loadFailed: Boolean,
    hideSensitive: Boolean,
    lockDoorSelector: Boolean = false,
    hideLogs: Boolean = false,
    hideHistory: Boolean = false,
    onUnlock: () -> Unit,
    onQuickUnlock: () -> Unit,
    onCancel: () -> Unit,
    onSelect: (String) -> Unit,
    onEdit: (DoorConfig) -> Unit,
    onAdd: () -> Unit,
    onClearHistory: () -> Unit,
    onToggleElderMode: () -> Unit,
) {
    val palette = LocalBaiyunPalette.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(BaiyunDimens.pagePadding.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 长按 3 秒切换长辈模式（iOS 的隐藏交互，方案 §4.6）
        PressAndHold(millis = 3_000L, onTrigger = onToggleElderMode) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "白云钥匙",
                    color = palette.textPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = bluetoothState.describe(),
                    color = if (bluetoothState == BleState.PoweredOn) palette.textSecondary else palette.danger,
                    fontSize = 12.sp,
                )
            }
        }

        if (loadFailed) {
            BaiyunCard {
                CardHeader(title = "门禁数据读不出来", tint = palette.danger)
                Text(
                    "本机密钥已失效（换机 / 恢复备份 / 部分 ROM 会这样）。请在「配置」里重新录入，或导入文本恢复。",
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                )
            }
        }

        // 门禁选择器（长辈模式可锁死）
        if (doors.isNotEmpty() && !lockDoorSelector) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                doors.forEach { door ->
                    FilterChip(
                        selected = door.id == selected?.id,
                        onClick = { onSelect(door.id) },
                        label = { Text(door.displayName, fontSize = 12.sp) },
                    )
                }
            }
        }

        if (selected == null) {
            BaiyunCard {
                EmptyStateView(
                    title = "还没有门禁",
                    subtitle = "去「配置」页新增，或用文本 / 二维码导入",
                )
                FilledButton(title = "去配置页新增", onClick = onAdd)
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                UnlockButton(phase = phase, onClick = onUnlock)

                Text(text = statusText, color = palette.textSecondary, fontSize = 12.sp)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (phase.isBusy) {
                        QuietButton(title = "取消", onClick = onCancel, tint = palette.danger)
                    } else if (doors.size > 1 && !lockDoorSelector) {
                        QuietButton(title = "快速模式", onClick = onQuickUnlock, tint = palette.info)
                    }
                    QuietButton(title = "编辑", onClick = { onEdit(selected) })
                }
            }

            BaiyunCard {
                CardHeader(title = selected.displayName, subtitle = "门禁参数")
                KeyValueRow("MAC", selected.maskedMac(hidden = hideSensitive))
                KeyValueRow("Key", selected.maskedKey(hidden = hideSensitive))
                KeyValueRow("蓝牙名称", selected.bluetoothName.ifEmpty { "未填写（Android 可按 MAC 匹配）" })
            }
        }

        // 运行日志
        if (logs.isNotEmpty() && !hideLogs) {
            var expanded by remember { mutableStateOf(false) }
            BaiyunCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CardHeader(title = "运行日志", subtitle = "${logs.size} 行")
                    QuietButton(title = if (expanded) "收起" else "展开", onClick = { expanded = !expanded })
                }
                if (expanded) {
                    Spacer(Modifier.height(2.dp))
                    SelectionContainer {
                        Text(
                            text = logs.takeLast(LOG_PREVIEW_LINES).joinToString("\n") { it.format() },
                            color = palette.textSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 14.sp,
                        )
                    }
                }
            }
        }

        // 开锁记录
        if (history.isNotEmpty() && !hideHistory) {
            var historyExpanded by remember { mutableStateOf(false) }
            BaiyunCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CardHeader(title = "开锁记录", subtitle = "${history.size} 条")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        QuietButton(
                            title = if (historyExpanded) "收起" else "展开",
                            onClick = { historyExpanded = !historyExpanded },
                        )
                        QuietButton(title = "清空", onClick = onClearHistory, tint = palette.danger)
                    }
                }

                history.take(if (historyExpanded) HISTORY_FULL else HISTORY_PREVIEW).forEach { record ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(record.doorName, color = palette.textPrimary, fontSize = 13.sp)
                            Text(record.message, color = palette.textTertiary, fontSize = 11.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = if (record.ok) "成功" else "失败",
                                color = if (record.ok) palette.brand else palette.danger,
                                fontSize = 12.sp,
                            )
                            Text(record.timeLabel(), color = palette.textTertiary, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

private fun BleState.describe(): String = when (this) {
    BleState.PoweredOn -> "蓝牙已就绪"
    BleState.PoweredOff -> "蓝牙已关闭，请在设置中打开"
    BleState.Unauthorized -> "未授权使用蓝牙"
    BleState.Unsupported -> "当前设备不支持蓝牙低功耗"
    BleState.Resetting -> "蓝牙正在重置"
    BleState.Unknown -> "蓝牙状态未知"
}

/**
 * 长按 [millis] 毫秒才触发（而不是 Compose 默认的 ~500ms）。
 *
 * 方案 §4.6 的「首页顶栏长按 3 秒切换长辈模式」就是靠这个实现：
 * 按下时起一个延时任务，抬手（或移出）就取消。
 */
@Composable
private fun PressAndHold(
    millis: Long,
    onTrigger: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier.pointerInput(millis) {
            detectTapGestures(
                onPress = {
                    val job = scope.launch {
                        delay(millis)
                        onTrigger()
                    }
                    tryAwaitRelease()
                    job.cancel()
                },
            )
        },
    ) {
        content()
    }
}

private val HISTORY_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

private fun UnlockRecord.timeLabel(): String =
    HISTORY_TIME_FORMAT.format(at.atZone(ZoneId.systemDefault()))

private const val LOG_PREVIEW_LINES = 40
private const val HISTORY_PREVIEW = 3
private const val HISTORY_FULL = 50
