package com.xavior.baiyunkeys

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.baiyunkeys.core.crypto.Sm2Gm
import com.baiyunkeys.core.deeplink.UnlockCommand
import com.baiyunkeys.core.deeplink.UnlockLink
import com.baiyunkeys.core.model.DoorConfig
import com.baiyunkeys.core.settings.AppSettings
import com.baiyunkeys.core.settings.FetchMethod
import com.baiyunkeys.core.settings.ThemeMode
import com.xavior.baiyunkeys.permissions.BlePermissions
import com.xavior.baiyunkeys.security.BiometricGate
import com.xavior.baiyunkeys.ui.config.AutoFetchResult
import com.xavior.baiyunkeys.ui.config.ConfigActions
import com.xavior.baiyunkeys.ui.config.ConfigScreen
import com.xavior.baiyunkeys.ui.diagnostics.DiagnosticsScreen
import com.xavior.baiyunkeys.ui.editor.DoorEditorScreen
import com.xavior.baiyunkeys.ui.home.HomeScreen
import com.xavior.baiyunkeys.ui.scan.QrScannerScreen
import com.xavior.baiyunkeys.ui.settings.SettingsScreen
import com.xavior.baiyunkeys.ui.theme.BaiyunDimens
import com.xavior.baiyunkeys.ui.theme.BaiyunTheme
import com.xavior.baiyunkeys.ui.theme.LocalBaiyunPalette
import com.xavior.baiyunkeys.ui.transfer.TransferScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 唯一 Activity（`singleTask`：深链复用同一实例，见方案 §4.7）。
 *
 * 继承 `FragmentActivity` 是因为 `BiometricPrompt` 需要它（`ComponentActivity` 不够）。
 *
 * 入口有三条，全部汇到 [AppContainer.pendingUnlock]：
 * - 图标启动（无 data）
 * - `baiyunkeys://unlock[?door=|?quick=1]` 深链
 * - 长按图标出的静态快捷方式（本质也是深链）
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15（API 35）起 targetSdk 35 会强制 edge-to-edge，内容会画到状态栏下面。
        // 显式开启并在根布局加 safeDrawingPadding()，让新旧系统行为一致。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 只在首次创建时吃 Intent：旋转屏幕会重建并带回同一个 Intent，
        // 那时 savedInstanceState 非空，不应该再开一次锁。
        if (savedInstanceState == null) {
            consume(intent)
        }

        setContent {
            val context = LocalContext.current
            val container = remember(context) { (context.applicationContext as App).container }
            val settings by container.settings.settings.collectAsState()

            val dark = when (settings.theme) {
                ThemeMode.Auto -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }

            BaiyunTheme(darkTheme = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // 状态栏 / 手势条 / 刘海都不再遮挡内容
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding(),
                    ) {
                        AppRoot(container = container, settings = settings)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    private fun consume(intent: Intent?) {
        UnlockLink.parse(intent?.dataString)?.let { command ->
            (application as App).container.pendingUnlock.value = command
        }
    }
}

private enum class Tab(val label: String) {
    Home("首页"),
    Config("配置"),
}

private sealed interface Route {
    data class Editor(val door: DoorConfig?) : Route
    data object Transfer : Route
    data object Settings : Route
    data object Diagnostics : Route
    data object QrScan : Route
}

@Composable
private fun AppRoot(container: AppContainer, settings: AppSettings) {
    val activity = LocalActivity.current as? FragmentActivity

    // 必须等偏好真正读出来再判断：StateFlow 的初值是默认值（requireBiometric = false），
    // 直接拿它初始化会让冷启动永远"跳过"锁屏。
    // 只用 loaded 当 key：用户中途自己打开开关时不该立刻被锁住，下次冷启动才生效。
    val loaded by container.settings.loaded.collectAsState()
    var locked by remember { mutableStateOf(false) }
    LaunchedEffect(loaded) {
        if (loaded && container.settings.settings.value.requireBiometric) {
            locked = true
        }
    }

    if (settings.requireBiometric && locked && activity != null) {
        BiometricLockScreen(
            gate = remember(activity) { BiometricGate(activity) },
            onUnlocked = { locked = false },
            onDisableLock = {
                locked = false
                container.appScope.launch {
                    container.settings.update { it.copy(requireBiometric = false) }
                }
            },
        )
        return
    }

    MainContent(container = container, settings = settings)
}

/** 冷启动生物识别锁屏（对应 iOS 的 `RootView` 锁屏）。 */
@Composable
private fun BiometricLockScreen(
    gate: BiometricGate,
    onUnlocked: () -> Unit,
    onDisableLock: () -> Unit,
) {
    val palette = LocalBaiyunPalette.current
    var message by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        if (gate.isAvailable()) {
            gate.authenticate { ok, msg ->
                if (ok) onUnlocked() else message = msg
            }
        } else {
            available = false
            message = "本机未设置指纹 / 面容或锁屏密码，无法验证"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(BaiyunDimens.pagePadding.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(palette.brandSoft, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("锁", color = palette.brand, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(18.dp))
        Text("白云钥匙已锁定", color = palette.textPrimary, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            message ?: "验证身份后可访问本机门禁凭据",
            color = palette.textSecondary,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(20.dp))

        Text(
            text = "重新验证",
            color = palette.brand,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable {
                    if (gate.isAvailable()) {
                        gate.authenticate { ok, msg -> if (ok) onUnlocked() else message = msg }
                    } else {
                        message = "本机未设置指纹 / 面容或锁屏密码"
                    }
                }
                .padding(8.dp),
        )

        if (!available) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "关闭生物识别锁屏并进入",
                color = palette.danger,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable { onDisableLock() }
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun MainContent(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(Tab.Home) }
    var route by remember { mutableStateOf<Route?>(null) }

    val effectiveTab = if (settings.elderMode) Tab.Home else tab

    // ① 冷启动要一次 BLE 权限：Android 10 没有定位权限就扫不到任何设备，
    //    而且这个提示只能由用户点，没法在扫描流程里静默解决。
    val context = LocalContext.current
    var askedPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    LaunchedEffect(Unit) {
        if (!askedPermission && !BlePermissions.granted(context)) {
            askedPermission = true
            permissionLauncher.launch(BlePermissions.required())
        }
    }

    // ① 深链 / 快捷方式
    val pending by container.pendingUnlock.collectAsState()
    LaunchedEffect(pending) {
        val command = pending ?: return@LaunchedEffect
        route = null
        tab = Tab.Home
        if (command is UnlockCommand.Door) container.doors.select(command.id)

        delay(PENDING_UNLOCK_DELAY_MS)

        val options = settings.toUnlockOptions()
        when (command) {
            UnlockCommand.Quick -> scope.launch {
                container.unlock.unlockAny(container.doors.state.value.items, options)
            }

            else -> container.doors.current?.let { door ->
                container.unlock.unlockAsync(door, options)
            }
        }
        // 放在最后清空：它是 LaunchedEffect 的 key，提前清会让协程自己取消掉
        container.pendingUnlock.value = null
    }

    // ② 「打开即开锁 / 切回也开锁」
    LifecycleUnlock(container = container, settings = settings)

    // ③ 自动获取配置
    var fetching by remember { mutableStateOf(false) }
    var fetchResult by remember { mutableStateOf<AutoFetchResult?>(null) }

    val onFetch: (String, String) -> Unit = { phone, idCard ->
        scope.launch {
            fetching = true
            fetchResult = null
            runCatching {
                val udid = container.settings.ensureUdid()
                val fetched = container.autoFetch.fetch(settings, phone, idCard, udid)
                val added = container.doors.merge(fetched)
                val missingName = fetched.count { it.bluetoothName.isEmpty() }
                val suffix = if (missingName > 0) "；有 $missingName 条缺蓝牙名称，需手动补全" else ""
                AutoFetchResult("成功导入 ${fetched.size} 条，新增 $added 条$suffix", missingName > 0)
            }.onSuccess {
                fetchResult = it
            }.onFailure {
                fetchResult = AutoFetchResult(it.message ?: "获取失败", true)
            }
            fetching = false
        }
    }

    val actions = ConfigActions(
        onSelect = { id -> container.doors.select(id) },
        onEdit = { door -> route = Route.Editor(door) },
        onDelete = { id -> container.doors.delete(id) },
        onAdd = { route = Route.Editor(null) },
        onOpenTransfer = { route = Route.Transfer },
        onOpenQrScanner = { route = Route.QrScan },
        onOpenBleScan = { route = Route.Diagnostics },
        onOpenSettings = { route = Route.Settings },
        onFetch = onFetch,
        onSettingsChange = { next -> scope.launch { container.settings.set(next) } },
        onGenerateSm2 = {
            val (privateKey, publicKey) = Sm2Gm.generateKeyPair()
            scope.launch {
                container.settings.update {
                    it.copy(smPublicKey = publicKey, smPrivateKey = privateKey)
                }
            }
        },
        onRevealAutoFetch = {
            scope.launch {
                container.settings.update {
                    it.copy(autoFetchVisible = true, autoFetchShownAt = System.currentTimeMillis())
                }
            }
        },
        onHideAutoFetch = {
            scope.launch { container.settings.update { it.copy(autoFetchVisible = false) } }
        },
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            val currentRoute = route
            if (currentRoute != null) {
                when (currentRoute) {
                    is Route.Editor -> DoorEditorScreen(
                        initial = currentRoute.door,
                        onSave = { door ->
                            container.doors.save(door)
                            route = null
                        },
                        onDelete = { id ->
                            container.doors.delete(id)
                            route = null
                        },
                        onBack = { route = null },
                    )

                    Route.Transfer -> {
                        val state by container.doors.state.collectAsState()
                        TransferScreen(
                            doors = state.items,
                            onImport = { parsed -> container.doors.merge(parsed) },
                            onOpenScanner = { route = Route.QrScan },
                            onBack = { route = null },
                        )
                    }

                    Route.Settings -> SettingsScreen(
                        settings = settings,
                        onChange = { next -> scope.launch { container.settings.set(next) } },
                        onBack = { route = null },
                    )

                    Route.Diagnostics -> DiagnosticsScreen(onBack = { route = null })

                    Route.QrScan -> QrScannerScreen(
                        onResult = { text ->
                            val parsed = com.baiyunkeys.core.text.DoorTextParser.parse(text)
                            if (parsed.isNotEmpty()) container.doors.merge(parsed)
                            route = Route.Transfer
                        },
                        onBack = { route = null },
                    )
                }
            } else {
                when (effectiveTab) {
                    Tab.Home -> HomeTab(container = container, settings = settings, actions = actions)
                    Tab.Config -> ConfigScreen(
                        doors = container.doors.state.collectAsState().value.items,
                        currentId = container.doors.state.collectAsState().value.currentId,
                        settings = settings,
                        actions = actions,
                        autoFetchBusy = fetching,
                        autoFetchResult = fetchResult,
                        appVersion = BuildConfig.VERSION_NAME,
                    )
                }
            }
        }

        // 长辈模式移除「配置」Tab（对应 iOS）
        if (!settings.elderMode) {
            BottomTabs(
                current = effectiveTab,
                onSelect = {
                    route = null
                    tab = it
                },
            )
        }
    }
}

@Composable
private fun HomeTab(container: AppContainer, settings: AppSettings, actions: ConfigActions) {
    val scope = rememberCoroutineScope()
    val state by container.doors.state.collectAsState()
    val phase by container.unlock.phase.collectAsState()
    val statusText by container.unlock.statusText.collectAsState()
    val logs by container.unlock.logs.collectAsState()
    val bleState by container.ble.state.collectAsState()
    val history by container.historyStore.records.collectAsState()

    HomeScreen(
        doors = state.items,
        selected = state.current,
        phase = phase,
        statusText = statusText,
        logs = logs,
        history = history,
        bluetoothState = bleState,
        loadFailed = container.doors.loadFailed,
        hideSensitive = settings.hideSensitive,
        lockDoorSelector = settings.elderMode && settings.elderLocksDoorSelector,
        hideLogs = settings.elderMode && settings.elderHidesLogs,
        hideHistory = settings.elderMode && settings.elderHidesHistory,
        onUnlock = {
            container.doors.current?.let { door ->
                container.unlock.unlockAsync(door, settings.toUnlockOptions())
            }
        },
        onQuickUnlock = {
            scope.launch {
                container.unlock.unlockAny(
                    container.doors.state.value.items,
                    settings.toUnlockOptions(),
                )
            }
        },
        onCancel = { container.unlock.cancel() },
        onSelect = actions.onSelect,
        onEdit = actions.onEdit,
        onAdd = actions.onAdd,
        onClearHistory = { container.historyStore.clear() },
        onToggleElderMode = {
            scope.launch { container.settings.update { it.copy(elderMode = !it.elderMode) } }
        },
    )
}

/** 底部 Tab（长辈模式只有一个，直接不显示）。 */
@Composable
private fun BottomTabs(current: Tab, onSelect: (Tab) -> Unit) {
    val palette = LocalBaiyunPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.surface)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { item ->
            val selected = item == current
            Text(
                text = item.label,
                color = if (selected) palette.brand else palette.textTertiary,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clickable { onSelect(item) }
                    .padding(horizontal = 24.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * 打开即开锁 / 切回也开锁。
 *
 * - 首次 `ON_START`：`quickUnlock` 打开就正常开锁一次；
 * - 之后每次回到前台：`unlockOnResume` 打开且距上次超过 3s，就走**静默直连**（无缓存则跳过）。
 */
@Composable
private fun LifecycleUnlock(container: AppContainer, settings: AppSettings) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // 记在 remember 里而不是 effect 内部：effect 会因为偏好变化重新注册，
    // 拿局部变量当「首次」标志会导致改一个开关就误触发一次开锁。
    val startedOnce = remember { mutableStateOf(false) }
    val lastResumeAt = remember { longArrayOf(0L) }

    DisposableEffect(lifecycleOwner, settings.quickUnlock, settings.unlockOnResume) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_START) return@LifecycleEventObserver

            val door = container.doors.current
            if (!startedOnce.value) {
                startedOnce.value = true
                if (settings.quickUnlock && door != null) {
                    container.unlock.unlockAsync(door, settings.toUnlockOptions())
                }
                return@LifecycleEventObserver
            }

            if (settings.unlockOnResume && door != null) {
                val now = System.currentTimeMillis()
                if (now - lastResumeAt[0] < RESUME_THROTTLE_MS) return@LifecycleEventObserver
                lastResumeAt[0] = now
                container.appScope.launch {
                    container.unlock.unlockIfCached(
                        door = door,
                        options = settings.toUnlockOptions(silent = true),
                    )
                }
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private const val PENDING_UNLOCK_DELAY_MS = 300L
private const val RESUME_THROTTLE_MS = 3_000L
