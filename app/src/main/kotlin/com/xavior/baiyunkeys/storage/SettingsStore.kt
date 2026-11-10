package com.xavior.baiyunkeys.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.baiyunkeys.core.settings.AppSettings
import com.baiyunkeys.core.settings.FetchMethod
import com.baiyunkeys.core.settings.HiddenEntryGate
import com.baiyunkeys.core.settings.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "baiyun.settings")

/**
 * 偏好存储：DataStore Preferences（对应 iOS 的 UserDefaults）。
 *
 * **键名与 iOS `AppStore.Keys` 完全一致**（方案 §4.5），方便两端对照排查。
 * 数值一律经 [AppSettings.sanitized] 收敛，脏数据不会把扫描超时设成 0 或负数。
 */
class SettingsStore(context: Context, scope: CoroutineScope) {

    private val dataStore = context.settingsDataStore

    private val _loaded = MutableStateFlow(false)

    /**
     * 偏好是否已从磁盘读出来。
     *
     * `stateIn(..., initialValue = AppSettings())` 起步给的是**默认值**，和"用户真的这么设置"没法区分。
     * 生物识别锁屏必须等这个变 true 之后再判断，否则冷启动会先按"未开启"渲染，锁屏永远不出现。
     */
    val loaded: StateFlow<Boolean> = _loaded

    val settings: StateFlow<AppSettings> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .onEach { _loaded.value = true }
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(settings.value).sanitized()
        dataStore.edit { prefs ->
            prefs[KEY_HIDE_SENSITIVE] = next.hideSensitive
            prefs[KEY_HAPTICS] = next.haptics
            prefs[KEY_AUTO_RETRY] = next.autoRetry
            prefs[KEY_AUTO_RETRY_COUNT] = next.autoRetryCount
            prefs[KEY_SCAN_TIMEOUT] = next.scanTimeoutSeconds
            prefs[KEY_SAVE_HISTORY] = next.saveHistory
            prefs[KEY_QUICK_UNLOCK] = next.quickUnlock
            prefs[KEY_UNLOCK_ON_RESUME] = next.unlockOnResume
            prefs[KEY_REQUIRE_BIOMETRIC] = next.requireBiometric
            prefs[KEY_THEME] = next.theme.raw
            prefs[KEY_ELDER_MODE] = next.elderMode
            prefs[KEY_ELDER_LOCKS_DOOR] = next.elderLocksDoorSelector
            prefs[KEY_ELDER_HIDES_LOGS] = next.elderHidesLogs
            prefs[KEY_ELDER_HIDES_HISTORY] = next.elderHidesHistory
            prefs[KEY_SHOW_LOGS_EXPANDED] = next.showLogsExpanded
            prefs[KEY_SHOW_HISTORY_EXPANDED] = next.showHistoryExpanded
            prefs[KEY_FETCH_METHOD] = next.fetchMethod.raw
            prefs[KEY_OPEN_ID] = next.openId
            prefs[KEY_SM_PUBLIC] = next.smPublicKey
            prefs[KEY_SM_PRIVATE] = next.smPrivateKey
            prefs[KEY_SHOW_AUTO_FETCH] = next.autoFetchVisible
            prefs[KEY_SHOW_AUTO_FETCH_AT] = next.autoFetchShownAt
            prefs[KEY_UDID] = next.udid
        }
    }

    /** 整体替换（UI 直接构造新的 [AppSettings] 后调用）。 */
    suspend fun set(next: AppSettings) = update { next }

    /** 保证 udid 存在：安装期内稳定，登录接口 `deviceInfo.udid` 要它。 */
    suspend fun ensureUdid(): String {
        val current = settings.value.udid
        if (current.isNotEmpty()) return current
        val generated = HiddenEntryGate.newUdid()
        update { it.copy(udid = generated) }
        return generated
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            hideSensitive = this[KEY_HIDE_SENSITIVE] ?: defaults.hideSensitive,
            haptics = this[KEY_HAPTICS] ?: defaults.haptics,
            autoRetry = this[KEY_AUTO_RETRY] ?: defaults.autoRetry,
            autoRetryCount = this[KEY_AUTO_RETRY_COUNT] ?: defaults.autoRetryCount,
            scanTimeoutSeconds = this[KEY_SCAN_TIMEOUT] ?: defaults.scanTimeoutSeconds,
            saveHistory = this[KEY_SAVE_HISTORY] ?: defaults.saveHistory,
            quickUnlock = this[KEY_QUICK_UNLOCK] ?: defaults.quickUnlock,
            unlockOnResume = this[KEY_UNLOCK_ON_RESUME] ?: defaults.unlockOnResume,
            requireBiometric = this[KEY_REQUIRE_BIOMETRIC] ?: defaults.requireBiometric,
            theme = ThemeMode.of(this[KEY_THEME]),
            elderMode = this[KEY_ELDER_MODE] ?: defaults.elderMode,
            elderLocksDoorSelector = this[KEY_ELDER_LOCKS_DOOR] ?: defaults.elderLocksDoorSelector,
            elderHidesLogs = this[KEY_ELDER_HIDES_LOGS] ?: defaults.elderHidesLogs,
            elderHidesHistory = this[KEY_ELDER_HIDES_HISTORY] ?: defaults.elderHidesHistory,
            showLogsExpanded = this[KEY_SHOW_LOGS_EXPANDED] ?: defaults.showLogsExpanded,
            showHistoryExpanded = this[KEY_SHOW_HISTORY_EXPANDED] ?: defaults.showHistoryExpanded,
            fetchMethod = FetchMethod.of(this[KEY_FETCH_METHOD]),
            openId = this[KEY_OPEN_ID] ?: defaults.openId,
            smPublicKey = this[KEY_SM_PUBLIC] ?: defaults.smPublicKey,
            smPrivateKey = this[KEY_SM_PRIVATE] ?: defaults.smPrivateKey,
            autoFetchVisible = this[KEY_SHOW_AUTO_FETCH] ?: defaults.autoFetchVisible,
            autoFetchShownAt = this[KEY_SHOW_AUTO_FETCH_AT] ?: defaults.autoFetchShownAt,
            udid = this[KEY_UDID] ?: defaults.udid,
        ).sanitized()
    }

    private companion object {
        val KEY_HIDE_SENSITIVE = booleanPreferencesKey("hideSensitive")
        val KEY_HAPTICS = booleanPreferencesKey("haptics")
        val KEY_AUTO_RETRY = booleanPreferencesKey("autoRetryEnabled")
        val KEY_AUTO_RETRY_COUNT = intPreferencesKey("autoRetryCount")
        val KEY_SCAN_TIMEOUT = intPreferencesKey("scanTimeout")
        val KEY_SAVE_HISTORY = booleanPreferencesKey("saveHistory")
        val KEY_QUICK_UNLOCK = booleanPreferencesKey("quickUnlock")
        val KEY_UNLOCK_ON_RESUME = booleanPreferencesKey("unlockOnResume")
        val KEY_REQUIRE_BIOMETRIC = booleanPreferencesKey("requireBiometric")
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_ELDER_MODE = booleanPreferencesKey("elderMode")
        val KEY_ELDER_LOCKS_DOOR = booleanPreferencesKey("elderLocksDoorSelector")
        val KEY_ELDER_HIDES_LOGS = booleanPreferencesKey("elderHidesLogs")
        val KEY_ELDER_HIDES_HISTORY = booleanPreferencesKey("elderHidesHistory")
        val KEY_SHOW_LOGS_EXPANDED = booleanPreferencesKey("showLogsExpanded")
        val KEY_SHOW_HISTORY_EXPANDED = booleanPreferencesKey("showHistoryExpanded")
        val KEY_FETCH_METHOD = stringPreferencesKey("fetchMethod")
        val KEY_OPEN_ID = stringPreferencesKey("openId")
        val KEY_SM_PUBLIC = stringPreferencesKey("smPublicKey")
        val KEY_SM_PRIVATE = stringPreferencesKey("smPrivateKey")
        val KEY_SHOW_AUTO_FETCH = booleanPreferencesKey("showAutoFetch")
        val KEY_SHOW_AUTO_FETCH_AT = longPreferencesKey("showAutoFetchAt")
        val KEY_UDID = stringPreferencesKey("baiyun.udid")
    }
}
