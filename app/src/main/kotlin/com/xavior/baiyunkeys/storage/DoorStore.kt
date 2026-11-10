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
 */
class DoorStore(
    context: Context,
    private val box: KeystoreBox = KeystoreBox(),
) : PeripheralCache {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 解密失败时置 true：UI 要提示「数据读不出来，请重新导入」，而不是假装没有门禁。 */
    var loadFailed: Boolean = false
        private set

    private val _state = MutableStateFlow(load())

    val state: StateFlow<DoorState> = _state.asStateFlow()

    val current: DoorConfig? get() = _state.value.current

    /** 新增或更新一条门禁；返回落盘后的最终值（含新生成的 id）。 */
    fun save(door: DoorConfig): DoorConfig {
        val snapshot = _state.value
        val existing = snapshot.items.indexOfFirst { it.id == door.id }
        val items = if (existing >= 0) {
            snapshot.items.toMutableList().also { it[existing] = door }
        } else {
            snapshot.items + door
        }
        val currentId = snapshot.currentId ?: door.id
        commit(snapshot.copy(items = items, currentId = currentId))
        return door
    }

    fun delete(id: String) {
        val snapshot = _state.value
        val items = snapshot.items.filterNot { it.id == id }
        val currentId = if (snapshot.currentId == id) items.firstOrNull()?.id else snapshot.currentId
        commit(snapshot.copy(items = items, currentId = currentId))
        clear(id)
    }

    fun select(id: String) {
        val snapshot = _state.value
        if (snapshot.items.none { it.id == id }) return
        commit(snapshot.copy(currentId = id))
    }

    fun replaceAll(state: DoorState) {
        commit(state)
    }

    /** 批量导入：按 MAC + Key 去重（语义见 `DoorMerge`），返回新增条数。 */
    fun merge(incoming: List<DoorConfig>): Int {
        val result = DoorMerge.merge(_state.value, incoming)
        if (result.added > 0 || result.state != _state.value) commit(result.state)
        return result.added
    }

    // MARK: - PeripheralCache

    override fun put(doorId: String, deviceId: String) {
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

    override fun clear(doorId: String) {
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

    // MARK: - 落盘

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
            DoorCodec.decode(plain)
        } catch (e: Exception) {
            // Keystore 密钥失效：保留密文、标记失败，让用户重新导入
            loadFailed = true
            DoorState()
        }
    }

    private companion object {
        const val PREFS_NAME = "baiyun.doors"
        const val KEY_DATA = "doorState.v2"
    }
}
