package com.xavior.baiyunkeys

import android.app.Application
import com.baiyunkeys.core.deeplink.UnlockCommand
import com.baiyunkeys.core.net.BaiyunApi
import com.baiyunkeys.core.net.OkHttpTransport
import com.baiyunkeys.core.unlock.BleAdapter
import com.baiyunkeys.core.unlock.HapticsFeedback
import com.baiyunkeys.core.unlock.UnlockHistory
import com.baiyunkeys.core.unlock.UnlockOrchestrator
import com.xavior.baiyunkeys.ble.BleAdapterImpl
import com.xavior.baiyunkeys.haptics.AndroidHaptics
import com.xavior.baiyunkeys.net.AutoFetchService
import com.xavior.baiyunkeys.storage.DoorStore
import com.xavior.baiyunkeys.storage.GatedUnlockHistory
import com.xavior.baiyunkeys.storage.HistoryStore
import com.xavior.baiyunkeys.storage.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 应用入口。规模小，不引 Hilt：所有依赖在 [AppContainer] 里手写装配。
 *
 * 对应 iOS 侧 `@Environment(AppStore.self)` 的注入点。
 */
class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** 手写 DI 容器。 */
class AppContainer(private val app: Application) {

    /** 应用级协程作用域（偏好流、历史写入等）。 */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val appContext get() = app.applicationContext

    /** 原生 BLE 适配器（`BluetoothLeScanner` + `BluetoothGatt`）。 */
    val ble: BleAdapter by lazy { BleAdapterImpl(appContext) }

    /** 门禁列表：Keystore 加密落盘，同时实现外设缓存（Android 上是 MAC 直连）。 */
    val doors: DoorStore by lazy { DoorStore(appContext) }

    /** 偏好：DataStore Preferences，键名与 iOS 一致。 */
    val settings: SettingsStore by lazy { SettingsStore(appContext, appScope) }

    /** 开锁历史：DataStore 持久化，最多 50 条。 */
    val historyStore: HistoryStore by lazy { HistoryStore(appContext, appScope) }

    /** 历史写入受偏好「保存开锁记录」控制。 */
    val history: UnlockHistory by lazy {
        GatedUnlockHistory(historyStore) { settings.settings.value.saveHistory }
    }

    /** 触感反馈：总开关直接读偏好。 */
    val haptics: HapticsFeedback by lazy {
        AndroidHaptics(appContext) { settings.settings.value.haptics }
    }

    /** 开锁流程编排器。选项在调用时从偏好现取（见 MainActivity）。 */
    val unlock: UnlockOrchestrator by lazy {
        UnlockOrchestrator(
            ble = ble,
            history = history,
            peripheralCache = doors,
            haptics = haptics,
            // 真机排查用：应用内日志同时打到 logcat（`adb logcat -s BaiyunKeys`）
            logSink = { line -> android.util.Log.d("BaiyunKeys", line.format()) },
        )
    }

    /**
     * 深链 / 快捷方式带进来的待处理开锁请求。
     *
     * 对应 iOS 的 `pendingUnlockDoorID`：Activity 写入，首页消费后立刻清空。
     */
    val pendingUnlock = MutableStateFlow<UnlockCommand?>(null)

    /** 官方接口客户端（三条通道共用）。 */
    val api: BaiyunApi by lazy { BaiyunApi(OkHttpTransport()) }

    /** 「自动获取配置」编排。 */
    val autoFetch: AutoFetchService by lazy { AutoFetchService(api) }
}
