package com.xavior.baiyunkeys.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baiyunkeys.core.hex.Hex
import com.baiyunkeys.core.model.DoorConfig
import com.xavior.baiyunkeys.ui.components.BaiyunCard
import com.xavior.baiyunkeys.ui.components.CardHeader
import com.xavior.baiyunkeys.ui.components.FilledButton
import com.xavior.baiyunkeys.ui.components.FlatField
import com.xavior.baiyunkeys.ui.components.QuietButton
import com.xavior.baiyunkeys.ui.theme.BaiyunDimens
import com.xavior.baiyunkeys.ui.theme.LocalBaiyunPalette

/**
 * 门禁编辑页，对应 iOS `DoorEditorView`。
 *
 * 与 iOS 的差异：**蓝牙名称不是必填**（Android 能直接读 MAC）。
 */
@Composable
fun DoorEditorScreen(
    initial: DoorConfig?,
    onSave: (DoorConfig) -> Unit,
    onDelete: ((String) -> Unit)?,
    onBack: () -> Unit,
) {
    val palette = LocalBaiyunPalette.current

    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var mac by remember(initial) { mutableStateOf(initial?.mac.orEmpty()) }
    var key by remember(initial) { mutableStateOf(initial?.key.orEmpty()) }
    var bluetoothName by remember(initial) { mutableStateOf(initial?.bluetoothName.orEmpty()) }

    val macValid = Hex.isValidMac(mac)
    val keyValid = Hex.isValidKey(key)
    val canSave = macValid && keyValid

    // 保存时把 MAC / Key 规范化，避免用户手输的空格与大小写影响匹配
    fun build(): DoorConfig {
        val base = initial ?: DoorConfig()
        return base.copy(
            name = name.trim(),
            mac = Hex.formatMac(mac),
            key = Hex.sanitizeKey(key),
            bluetoothName = bluetoothName.trim().uppercase(),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(BaiyunDimens.pagePadding.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            QuietButton(title = "← 返回", onClick = onBack)
            if (initial != null && onDelete != null) {
                QuietButton(
                    title = "删除",
                    onClick = { onDelete(initial.id) },
                    tint = palette.danger,
                )
            }
        }

        Text(
            text = if (initial == null) "新增门禁" else "编辑门禁",
            color = palette.textPrimary,
            fontSize = 24.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )

        BaiyunCard {
            CardHeader(title = "参数", subtitle = "MAC 与 Key 来自官方下发的门禁记录")
            FlatField(
                title = "门禁名称",
                value = name,
                onValueChange = { name = it },
                placeholder = "如 一号楼大门",
            )
            FlatField(
                title = "门禁 MAC",
                value = mac,
                onValueChange = { mac = it },
                placeholder = "AA:BB:CC:DD:EE:FF",
                hint = if (mac.isBlank()) "macNum，12 位十六进制，冒号可省略" else if (!macValid) "格式不对：需要 6 组两位十六进制" else null,
                monospaced = true,
                keyboardType = KeyboardType.Ascii,
                supportingError = if (mac.isNotBlank() && !macValid) "MAC 格式不合法" else null,
            )
            FlatField(
                title = "门禁 Key",
                value = key,
                onValueChange = { key = it },
                placeholder = "16~32 位十六进制",
                hint = "productKey，等同实体钥匙，别外传",
                monospaced = true,
                keyboardType = KeyboardType.Ascii,
                supportingError = if (key.isNotBlank() && !keyValid) "Key 需为 16~32 位偶数长度十六进制" else null,
            )
            FlatField(
                title = "蓝牙名称（可选）",
                value = bluetoothName,
                onValueChange = { bluetoothName = it },
                placeholder = "如 BY0AABBCCDD",
                hint = "Android 能直接按 MAC 匹配；填了可提高命中率",
                monospaced = true,
                keyboardType = KeyboardType.Ascii,
            )
        }

        FilledButton(
            title = if (initial == null) "保存并启用" else "保存",
            onClick = { onSave(build()) },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth(),
        )

        if (!canSave) {
            Text(
                "MAC 与 Key 都填对才能保存。",
                color = palette.textTertiary,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
