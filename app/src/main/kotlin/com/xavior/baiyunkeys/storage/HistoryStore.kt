package com.xavior.baiyunkeys.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.baiyunkeys.core.unlock.UnlockHistory
import com.baiyunkeys.core.unlock.UnlockHistoryCodec
import com.baiyunkeys.core.unlock.UnlockRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = "baiyun.history")

/**
 * 开锁历史：DataStore 持久化，最多 50 条（对应 iOS `AppStore.historyLimit`）。
 *
 * 时间戳存毫秒（序列化友好），展示时再转本地时间。
 */
class HistoryStore(context: Context, private val scope: CoroutineScope) : UnlockHistory {

    private val dataStore = context.historyDataStore

    private val _records = MutableStateFlow<List<UnlockRecord>>(emptyList())
    val records: StateFlow<List<UnlockRecord>> = _records.asStateFlow()

    init {
        scope.launch {
            dataStore.data
                .catch { emit(emptyPreferences()) }
                .map { UnlockHistoryCodec.decode(it[KEY_RECORDS].orEmpty()) }
                .collectLatest { _records.value = it }
        }
    }

    override fun add(record: UnlockRecord) {
        val next = UnlockHistoryCodec.add(_records.value, record)
        _records.value = next
        scope.launch { persist(next) }
    }

    fun clear() {
        _records.value = emptyList()
        scope.launch { dataStore.edit { it.remove(KEY_RECORDS) } }
    }

    private suspend fun persist(records: List<UnlockRecord>) {
        dataStore.edit { prefs -> prefs[KEY_RECORDS] = UnlockHistoryCodec.encode(records) }
    }

    private companion object {
        val KEY_RECORDS = stringPreferencesKey("unlockHistory")
    }
}

/**
 * 按偏好「保存开锁记录」决定是否写入。
 *
 * 关掉后新记录不再进来，但已有历史仍在（与 iOS 行为一致：开关管写入，不管清理）。
 */
class GatedUnlockHistory(
    private val delegate: UnlockHistory,
    private val enabled: () -> Boolean,
) : UnlockHistory {

    override fun add(record: UnlockRecord) {
        if (enabled()) delegate.add(record)
    }
}
