package com.xavior.baiyunkeys.ui.diagnostics

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baiyunkeys.core.hex.Hex
import com.baiyunkeys.core.protocol.BleProtocol
import com.baiyunkeys.core.unlock.BleState
import com.baiyunkeys.core.unlock.ScanHit
import com.xavior.baiyunkeys.App
import com.xavior.baiyunkeys.permissions.BlePermissions
import com.xavior.baiyunkeys.ui.components.BaiyunCard
import com.xavior.baiyunkeys.ui.components.CardHeader
import com.xavior.baiyunkeys.ui.components.QuietButton
import com.xavior.baiyunkeys.ui.theme.BaiyunDimens
import com.xavior.baiyunkeys.ui.theme.LocalBaiyunPalette
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 诊断屏：蓝牙扫描面板 + 协议探针。
 *
 * 用途是**真机验收**：能看到附近广播的设备名 / MAC / RSSI，就说明权限分支、Android 10 定位闸门、
 * 原始 AD 解析这三件事都通了。
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val palette = LocalBaiyunPalette.current
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as App).container }
    val scope = rememberCoroutineScope()

    val bleState by container.ble.state.collectAsState()

    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val hits = remember { mutableStateMapOf<String, ScanHit>() }

    fun startScan() {
        if (scanning) return
        hits.clear()
        scanError = null
        scanning = true
        scope.launch {
            try {
                withTimeoutOrNull(SCAN_DURATION_MS) {
                    container.ble.scan().collect { hit -> hits[hit.deviceId] = hit }
                }
            } catch (e: Exception) {
                scanError = e.message ?: e::class.java.simpleName
            } finally {
                scanning = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) startScan() else scanError = "权限被拒绝，无法扫描"
    }

    val probe = remember {
        runCatching {
            Hex.hexFromBytes(
                BleProtocol.handshakeCommand(
                    random = byteArrayOf(0x3C, 0x91.toByte(), 0x0F, 0xA7.toByte()),
                    header = BleProtocol.headerFromMac("00:0A:AB:BC:CD:DD"),
                    productKey = "00112233445566778899AABBCCDDEEFF",
                ),
            )
        }.getOrElse { "计算失败：${it.message}" }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(BaiyunDimens.pagePadding.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            QuietButton(title = "← 返回", onClick = onBack)
        }

        Text("扫描与诊断", color = palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

        BaiyunCard {
            CardHeader(title = "运行环境")
            InfoRow("Android", "${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            InfoRow("型号", "${Build.MANUFACTURER} ${Build.MODEL}")
            InfoRow("蓝牙状态", bleState.describe())
            InfoRow("握手帧探针", probe)
        }

        BaiyunCard {
            CardHeader(title = "蓝牙扫描", subtitle = "列出附近广播的设备")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        if (scanning) scanError = null else permissionLauncher.launch(BlePermissions.required())
                    },
                    enabled = !scanning,
                ) {
                    Text(if (scanning) "扫描中…" else "开始扫描（${SCAN_DURATION_MS / 1000}s）")
                }
                OutlinedButton(onClick = { hits.clear() }, enabled = hits.isNotEmpty()) {
                    Text("清空")
                }
            }

            scanError?.let { Text(it, color = palette.danger, fontSize = 12.sp) }

            if (hits.isEmpty()) {
                Text(
                    if (scanning) "正在搜索附近的蓝牙设备…" else "尚未发现设备",
                    color = palette.textTertiary,
                    fontSize = 12.sp,
                )
            } else {
                hits.values.sortedByDescending { it.rssi }.take(MAX_ROWS).forEach { hit ->
                    InfoRow(hit.name ?: "（未命名）", "${hit.deviceId}   RSSI ${hit.rssi}")
                }
            }
        }

        Text(
            "提示：Android 10 上扫描必须授予定位权限，且系统定位服务要开着。",
            color = palette.textTertiary,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val palette = LocalBaiyunPalette.current
    Column {
        Text(label, color = palette.textTertiary, fontSize = 11.sp)
        Text(value, color = palette.textPrimary, fontSize = 13.sp)
    }
}

private fun BleState.describe(): String = when (this) {
    BleState.PoweredOn -> "已开启"
    BleState.PoweredOff -> "已关闭"
    BleState.Unauthorized -> "未授权"
    BleState.Unsupported -> "不支持 BLE"
    BleState.Resetting -> "正在重置"
    BleState.Unknown -> "未知"
}

private const val SCAN_DURATION_MS = 10_000L
private const val MAX_ROWS = 8

