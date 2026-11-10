package com.xavior.baiyunkeys.ui.transfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import com.baiyunkeys.core.model.DoorConfig
import com.baiyunkeys.core.text.DoorTextExporter
import com.baiyunkeys.core.text.DoorTextParser
import com.xavior.baiyunkeys.qr.QrCodec
import com.xavior.baiyunkeys.ui.components.BaiyunCard
import com.xavior.baiyunkeys.ui.components.CardHeader
import com.xavior.baiyunkeys.ui.components.FilledButton
import com.xavior.baiyunkeys.ui.components.QuietButton
import com.xavior.baiyunkeys.ui.theme.BaiyunDimens
import com.xavior.baiyunkeys.ui.theme.LocalBaiyunPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导入 / 导出，对应 iOS `ConfigView.transferCard` + `ImportSheet`。
 *
 * 文本格式与 iOS 逐字兼容（见 `DoorTextExporter`）；二维码内容就是同一段文本，
 * 所以 iOS 扫码能读、本机生成 iOS 也能读。
 */
@Composable
fun TransferScreen(
    doors: List<DoorConfig>,
    onImport: (List<DoorConfig>) -> Unit,
    onOpenScanner: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalBaiyunPalette.current
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pasted by remember { mutableStateOf("") }
    val parsed = remember(pasted) { DoorTextParser.parse(pasted) }

    fun copyToClipboard(text: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("白云钥匙", text)))
        }
    }

    fun pasteFromClipboard() {
        scope.launch {
            val entry = clipboard.getClipEntry() ?: return@launch
            val data = entry.clipData
            val item = if (data.itemCount > 0) data.getItemAt(0) else null
            pasted = item?.coerceToText(context)?.toString().orEmpty()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(BaiyunDimens.pagePadding.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        QuietButton(title = "← 返回", onClick = onBack)
        Text("导入 / 导出", color = palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "内容包含门禁 Key，等同实体钥匙，请勿发到聊天群或公开位置。",
            color = palette.danger,
            fontSize = 11.sp,
        )

        // MARK: 导入

        BaiyunCard {
            CardHeader(title = "导入", subtitle = "从另一台设备粘贴文本")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton(
                    title = "从剪贴板粘贴",
                    onClick = { pasteFromClipboard() },
                    tint = palette.info,
                )
                QuietButton(title = "扫码导入", onClick = onOpenScanner)
                QuietButton(title = "清空", onClick = { pasted = "" }, enabled = pasted.isNotEmpty())
            }

            OutlinedTextField(
                value = pasted,
                onValueChange = { pasted = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                placeholder = {
                    Text(
                        "门禁名称：一号楼大门\n门禁 MAC：AA:BB:CC:DD:EE:FF\n门禁 Key：0011…\n蓝牙名称：BY0AABBCCDD",
                        color = palette.textTertiary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                },
                shape = RoundedCornerShape(BaiyunDimens.fieldRadius.dp),
            )

            Text(
                text = when {
                    pasted.isBlank() -> "粘贴后会自动识别，空行分条"
                    parsed.isEmpty() -> "没识别到完整条目（MAC 与 Key 都得有）"
                    else -> "识别到 ${parsed.size} 条：${parsed.joinToString("、") { it.displayName }}"
                },
                color = if (pasted.isNotBlank() && parsed.isEmpty()) palette.danger else palette.textSecondary,
                fontSize = 12.sp,
            )

            FilledButton(
                title = if (parsed.isEmpty()) "导入" else "导入 ${parsed.size} 条",
                onClick = {
                    onImport(parsed)
                    pasted = ""
                },
                enabled = parsed.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                tint = palette.info,
            )
        }

        // MARK: 导出

        if (doors.isEmpty()) {
            BaiyunCard {
                CardHeader(title = "导出")
                Text("还没有可导出的门禁", color = palette.textTertiary, fontSize = 12.sp)
            }
        } else {
            val exportText = remember(doors) { DoorTextExporter.exportAll(doors) }
            var qrDoorId by remember { mutableStateOf(doors.first().id) }
            val qrDoor = doors.firstOrNull { it.id == qrDoorId } ?: doors.first()
            val qrSource = remember(qrDoor) { DoorTextExporter.export(qrDoor) }
            val qr by produceState<ImageBitmap?>(initialValue = null, qrSource) {
                value = withContext(Dispatchers.Default) {
                    runCatching { QrCodec.encode(qrSource).asImageBitmap() }.getOrNull()
                }
            }

            BaiyunCard {
                CardHeader(title = "导出全部", subtitle = "${doors.size} 条文本")
                QuietButton(
                    title = "复制全部到剪贴板",
                    onClick = { copyToClipboard(exportText) },
                    tint = palette.info,
                )
                SelectionContainer {
                    Text(
                        text = exportText,
                        color = palette.textSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp,
                    )
                }
            }

            BaiyunCard {
                CardHeader(title = "二维码", subtitle = "另一台设备用「导入」扫码即可")

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    doors.forEach { doorItem ->
                        FilterChip(
                            selected = doorItem.id == qrDoorId,
                            onClick = { qrDoorId = doorItem.id },
                            label = { Text(doorItem.displayName, fontSize = 12.sp) },
                        )
                    }
                }

                val bitmap = qr
                if (bitmap == null) {
                    Text("生成中…", color = palette.textTertiary, fontSize = 12.sp)
                } else {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "${qrDoor.displayName} 的二维码",
                        modifier = Modifier
                            .size(220.dp)
                            .align(Alignment.CenterHorizontally)
                            .background(Color.White, RoundedCornerShape(12.dp)),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}
