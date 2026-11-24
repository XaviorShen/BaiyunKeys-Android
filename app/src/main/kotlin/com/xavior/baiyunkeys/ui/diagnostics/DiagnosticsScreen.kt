package com.xavior.baiyunkeys.ui.diagnostics

import android.content.ClipData
import android.content.Context
import android.location.LocationManager
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.baiyunkeys.core.hex.Hex
import com.baiyunkeys.core.model.DoorConfig
import com.baiyunkeys.core.model.MacOrigin
import com.baiyunkeys.core.protocol.BleProtocol
import com.baiyunkeys.core.unlock.BleState
import com.baiyunkeys.core.unlock.DeviceMatcher
import com.baiyunkeys.core.unlock.MatchTier
import com.baiyunkeys.core.unlock.ScanHit
import com.baiyunkeys.core.unlock.SettingsTarget
import com.xavior.baiyunkeys.App
import com.xavior.baiyunkeys.permissions.BlePermissions
import com.xavior.baiyunkeys.ui.components.BaiyunCard
import com.xavior.baiyunkeys.ui.components.CardHeader
import com.xavior.baiyunkeys.ui.components.QuietButton
import com.xavior.baiyunkeys.ui.theme.BaiyunDimens
import com.xavior.baiyunkeys.ui.theme.LocalBaiyunPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 诊断屏：蓝牙扫描面板 + 协议探针。
 *
 * 用途是**真机验收与排查**：能看到附近广播的设备名 / MAC / RSSI，就说明权限分支、
 * 定位闸门、原始 AD 解析这几件事都通了。
 *
 * issue #5 之后补了四样：定位开关与权限状态、每台设备对当前门禁的匹配结果、
 * 「绑定到当前门禁」（手动指定直连地址），以及一键复制诊断信息。
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val palette = LocalBaiyunPalette.current
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as App).container }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    val bleState by container.ble.state.collectAsState()
    val doorState by container.doors.state.collectAsState()
    val selected = doorState.current

    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val hits = remember { mutableStateMapOf<String, ScanHit>() }
    var bindTarget by remember { mutableStateOf<ScanHit?>(null) }

    // 定位开关和权限会跟着系统设置变：进这一页、回到这一页、每次扫描前都重新读一遍
    var environment by remember { mutableStateOf(readEnvironment(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) environment = readEnvironment(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun startScan() {
        if (scanning) return
        hits.clear()
        scanError = null
        message = null
        environment = readEnvironment(context)
        scanning = true
        scope.launch {
            try {
                withTimeoutOrNull(SCAN_DURATION_MS) {
                    container.ble.scan().collect { hit -> hits[hit.deviceId] = hit }
                }
            } catch (e: CancellationException) {
                // 离开这一页时会走到这里，不是扫描出错
                throw e
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
        environment = readEnvironment(context)
        if (granted.values.all { it }) {
            startScan()
        } else {
            scanError = "没有拿到蓝牙权限，无法扫描。可以点「去设置」到系统设置里打开。"
        }
    }

    // 缺权限或定位没开时，给一个出口：Android 11 起权限被拒两次后系统不再弹窗
    val settingsTarget = when {
        !environment.permissionGranted -> SettingsTarget.BluetoothPermission
        !environment.locationEnabled -> SettingsTarget.LocationService
        else -> null
    }

    fun copyDiagnostics() {
        val text = buildDiagnostics(bleState, environment, selected, hits.values)
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("白云钥匙诊断", text)))
        }
        message = "诊断信息已复制（${hits.size} 台设备），可以贴到 issue 里"
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
            InfoRow("蓝牙权限", if (environment.permissionGranted) "已授予" else "未授予")
            InfoRow(
                "系统定位服务",
                when {
                    environment.locationEnabled -> "已开启"
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> "已关闭（Android 12 及以上通常不影响扫描）"
                    else -> "已关闭（Android 12 以下必须开着才能扫描）"
                },
            )
            InfoRow("握手帧探针", probe)
        }

        // 当前门禁的 MAC 是按蓝牙名反推的：没法直连，下面列表里的「绑定」正好补上真实地址
        selected?.let { door ->
            if (MacOrigin.looksDerivedFromName(door.mac, door.bluetoothName)) {
                Text(
                    "当前门禁「${door.displayName}」的 MAC 是按蓝牙名反推的，不是真实地址，没法直连。" +
                        "在下面找到门锁并「绑定」，或者补填真实 MAC。",
                    color = palette.danger,
                    fontSize = 12.sp,
                )
            }
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

            if (settingsTarget != null || hits.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (settingsTarget != null) {
                        QuietButton(
                            title = "去设置",
                            onClick = { BlePermissions.openSettings(context, settingsTarget) },
                            tint = palette.danger,
                        )
                    }
                    if (hits.isNotEmpty()) {
                        QuietButton(title = "复制诊断信息", onClick = { copyDiagnostics() }, tint = palette.info)
                    }
                }
            }

            scanError?.let { Text(it, color = palette.danger, fontSize = 12.sp) }
            message?.let { Text(it, color = palette.textSecondary, fontSize = 12.sp) }

            if (hits.isEmpty()) {
                Text(
                    if (scanning) "正在搜索附近的蓝牙设备…" else "尚未发现设备",
                    color = palette.textTertiary,
                    fontSize = 12.sp,
                )
            } else {
                val shown = hits.values.sortedByDescending { it.rssi }
                shown.take(MAX_ROWS).forEach { hit ->
                    DeviceRow(
                        hit = hit,
                        // 没选门禁时不显示匹配结果，免得整列的 ✗ 像出了问题
                        matchLabel = selected?.let { door -> DeviceMatcher.matchTier(hit, door)?.label ?: "✗" },
                        onBind = if (selected != null) ({ bindTarget = hit }) else null,
                    )
                }
                if (shown.size > MAX_ROWS) {
                    Text(
                        "还有 ${shown.size - MAX_ROWS} 台没列出，「复制诊断信息」里有全部",
                        color = palette.textTertiary,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        Text(
            "提示：Android 12 以下扫描必须授予定位权限，并且系统定位服务要开着；" +
                "12 及以上需要「附近设备」权限。诊断信息里有附近设备的地址，贴到公开位置前可以先打码。",
            color = palette.textTertiary,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(4.dp))
    }

    // 绑定前确认一次：绑错了不会出事（握手用的是门禁自己的参数），但会白等一轮
    if (bindTarget != null && selected != null) {
        val door = selected
        val hit = bindTarget!!
        AlertDialog(
            onDismissRequest = { bindTarget = null },
            title = { Text("绑定到「${door.displayName}」") },
            text = {
                Text(
                    "把 ${hit.name ?: "未命名"}（${hit.deviceId}）记为这条门禁的直连地址？\n\n" +
                        "下次开锁会直接连接它，不再扫描；握手仍然用这条门禁自己的参数。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        container.doors.put(door.id, hit.deviceId)
                        bindTarget = null
                        message = "已绑定 ${hit.deviceId}，下次开锁会直接连接它"
                    },
                ) {
                    Text("绑定")
                }
            },
            dismissButton = {
                TextButton(onClick = { bindTarget = null }) { Text("取消") }
            },
        )
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

/** 一台被发现的外设：名字、地址、信号强度，以及对当前门禁的匹配结果。 */
@Composable
private fun DeviceRow(hit: ScanHit, matchLabel: String?, onBind: (() -> Unit)?) {
    val palette = LocalBaiyunPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(hit.name ?: "（未命名）", color = palette.textPrimary, fontSize = 13.sp)
            Text("${hit.deviceId}   RSSI ${hit.rssi}", color = palette.textTertiary, fontSize = 11.sp)
        }
        if (matchLabel != null) {
            Text(
                matchLabel,
                color = if (matchLabel.startsWith("✓")) palette.brand else palette.textTertiary,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        if (onBind != null) {
            QuietButton(title = "绑定", onClick = onBind)
        }
    }
}

private val MatchTier.label: String
    get() = when (this) {
        MatchTier.Mac -> "✓ MAC"
        MatchTier.Name -> "✓ 名字"
        MatchTier.AdvPayload -> "✓ 广播数据"
    }

private fun BleState.describe(): String = when (this) {
    BleState.PoweredOn -> "已开启"
    BleState.PoweredOff -> "已关闭"
    BleState.Unauthorized -> "未授权"
    BleState.Unsupported -> "不支持 BLE"
    BleState.Resetting -> "正在重置"
    BleState.Unknown -> "未知"
}

/** 扫描能不能出结果，取决于这两样系统状态。 */
private data class Environment(val locationEnabled: Boolean, val permissionGranted: Boolean)

private fun readEnvironment(context: Context): Environment = Environment(
    // API 28 起才有 isLocationEnabled，minSdk 正好是 28
    locationEnabled = context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true,
    permissionGranted = BlePermissions.granted(context),
)

/** 复制出去的诊断文本：运行环境 + 全部发现的设备（不受列表条数限制）。 */
private fun buildDiagnostics(
    bleState: BleState,
    environment: Environment,
    selected: DoorConfig?,
    hits: Collection<ScanHit>,
): String = buildString {
    appendLine("白云钥匙 · 扫描与诊断")
    appendLine("Android：${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
    appendLine("型号：${Build.MANUFACTURER} ${Build.MODEL}")
    appendLine("蓝牙状态：${bleState.describe()}")
    appendLine("蓝牙权限：${if (environment.permissionGranted) "已授予" else "未授予"}")
    appendLine("系统定位服务：${if (environment.locationEnabled) "已开启" else "已关闭"}")
    if (selected != null) {
        appendLine("当前门禁：${selected.displayName}")
        if (MacOrigin.looksDerivedFromName(selected.mac, selected.bluetoothName)) {
            appendLine("  MAC 是按蓝牙名反推的，不是真实地址")
        }
    }
    appendLine("发现 ${hits.size} 台设备：")
    hits.sortedByDescending { it.rssi }.forEach { hit ->
        val match = selected?.let { door -> DeviceMatcher.matchTier(hit, door)?.label ?: "✗" }.orEmpty()
        appendLine("- ${hit.name ?: "（未命名）"} | ${hit.deviceId} | RSSI ${hit.rssi} $match")
    }
}

private const val SCAN_DURATION_MS = 10_000L
private const val MAX_ROWS = 12
