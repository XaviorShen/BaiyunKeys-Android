package com.xavior.baiyunkeys.storage

import android.content.Context
import androidx.core.content.edit
import com.baiyunkeys.core.model.DoorCodec
import com.baiyunkeys.core.model.DoorConfig
import com.baiyunkeys.core.model.DoorMerge
import com.baiyunkeys.core.model.DoorState
import com.baiyunkeys.core.unlock.PeripheralCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 门禁列表存储：Keystore 加密后存 SharedPreferences（对应 iOS 的 Keychain + `DoorState`）。
 *
 * 对外暴露 [StateFlow]，UI 直接 collect。所有写操作都同步落盘。
 *
 * 两道保险：
 * - **读不全时不丢原始数据**：有条目解不开时只丢那几条，并把旧密文另存一份（[KEY_RECOVER]）。
 *   否则下一次写入就会用残缺的列表覆盖 [KEY_DATA]，被丢掉的门禁再也找不回来。
 * - **写操作串行**：读-改-写分布在主线程和开锁协程里，不加锁会互相覆盖
 *   （比如刚记下的直连地址被一次「切换门禁」冲掉，下次开锁又得重新扫描）。
 */
class DoorStore(
    context: Context,
    private val box: KeystoreBox = KeystoreBox(),
) : PeripheralCache {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 解密失败时置 true：UI 要提示「数据读不出来，请重新导入」，而不是假装没有门禁。 */
    var loadFailed: Boolean = false
        private set

    /** 有条目解不出来、被丢掉时的条数；0 表示全部读出。 */
    var unreadableCount: Int = 0
        private set

    private val lock = Any()

    private val _state = MutableStateFlow(load())

    val state: StateFlow<DoorState> = _state.asStateFlow()

    val current: DoorConfig? get() = _state.value.current

    /** 新增或更新一条门禁；返回落盘后的最终值（含新生成的 id）。 */
    fun save(door: DoorConfig): DoorConfig = synchronized(lock) {
        val snapshot = _state.value
        val existing = snapshot.items.indexOfFirst { it.id == door.id }
        val items = if (existing >= 0) {
            snapshot.items.toMutableList().also { it[existing] = door }
        } else {
            snapshot.items + door
        }
        val currentId = snapshot.currentId ?: door.id
        commit(snapshot.copy(items = items, currentId = currentId))
        door
    }

    fun delete(id: String) {
        synchronized(lock) {
            val snapshot = _state.value
            val items = snapshot.items.filterNot { it.id == id }
            val currentId = if (snapshot.currentId == id) items.firstOrNull()?.id else snapshot.currentId
            commit(snapshot.copy(items = items, currentId = currentId))
        }
    }

    fun select(id: String) {
        synchronized(lock) {
            val snapshot = _state.value
            if (snapshot.items.none { it.id == id }) return
            commit(snapshot.copy(currentId = id))
        }
    }

    fun replaceAll(state: DoorState) {
        synchronized(lock) { commit(state) }
    }

    /** 批量导入：按 MAC + Key 去重（语义见 `DoorMerge`），返回新增条数。 */
    fun merge(incoming: List<DoorConfig>): Int = synchronized(lock) {
        val result = DoorMerge.merge(_state.value, incoming)
        if (result.added > 0 || result.state != _state.value) commit(result.state)
        result.added
    }

    // MARK: - PeripheralCache

    override fun put(doorId: String, deviceId: String) {
        synchronized(lock) {
            val snapshot = _state.value
            if (snapshot.items.none { it.id == doorId }) return
            commit(
                snapshot.copy(
                    items = snapshot.items.map {
                        if (it.id == doorId) it.copy(cachedPeripheralId = deviceId) else it
                    },
                ),
            )
        }
    }

    override fun clear(doorId: String) {
        synchronized(lock) {
            val snapshot = _state.value
            if (snapshot.items.none { it.id == doorId && it.cachedPeripheralId != null }) return
            commit(
                snapshot.copy(
                    items = snapshot.items.map {
                        if (it.id == doorId) it.copy(cachedPeripheralId = null) else it
                    },
                ),
            )
        }
    }

    // MARK: - 落盘

    /** 把新状态写进内存和磁盘。调用方必须持有 [lock]。 */
    private fun commit(next: DoorState) {
        _state.value = next
        val json = DoorCodec.encode(next)
        runCatching {
            prefs.edit {
                putString(KEY_DATA, box.encrypt(json.toByteArray(Charsets.UTF_8)))
            }
        }
    }

    private fun load(): DoorState {
        val encrypted = prefs.getString(KEY_DATA, null) ?: return DoorState()
        return try {
            val plain = box.decrypt(encrypted).toString(Charsets.UTF_8)
            val report = DoorCodec.decodeWithReport(plain)
            if (report.failed > 0) {
                unreadableCount = report.failed
                keepRecoverCopy(encrypted)
            }
            report.state
        } catch (e: Exception) {
            // Keystore 密钥失效：保留密文、标记失败，让用户重新导入
            loadFailed = true
            keepRecoverCopy(encrypted)
            DoorState()
        }
    }

    /**
     * 读不全时把这份密文另存到 [KEY_RECOVER]。之后任何一次写入覆盖了 [KEY_DATA]，
     * 这份副本还在，被丢掉的门禁就还有机会找回来。
     *
     * 只在启动读数据的那一次调用，每次启动最多写一回。
     */
    private fun keepRecoverCopy(encrypted: String) {
        runCatching { prefs.edit { putString(KEY_RECOVER, encrypted) } }
    }

    /** 丢弃恢复副本：确认门禁列表没问题之后可以调用。 */
    fun clearRecoverCopy() {
        prefs.edit { remove(KEY_RECOVER) }
    }

    private companion object {
        const val PREFS_NAME = "baiyun.doors"
        const val KEY_DATA = "doorState.v2"
        const val KEY_RECOVER = "doorState.v2.recover"
    }
}
