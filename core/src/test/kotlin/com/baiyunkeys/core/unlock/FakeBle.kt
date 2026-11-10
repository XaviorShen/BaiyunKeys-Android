package com.baiyunkeys.core.unlock

import com.baiyunkeys.core.crypto.DesBlock
import com.baiyunkeys.core.hex.Hex
import java.util.UUID
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/** 测试用假门锁：把「设备的行为」写成脚本，不用蓝牙也能跑完整状态机。 */
object TestFrames {

    /** 握手成功回执：`cmd=04` 且长度 > 24 hex 字符。 */
    fun handshakeOk(): String = "A50004" + "00".repeat(15)

    /**
     * 握手失败回执（短帧）：返回码取自 `slice(14,4)` 并做字节交换。
     * `"000B"` → 帧里放 `"0B00"`。
     */
    fun handshakeReject(word: String = "000B"): String =
        "A50004" + "0".repeat(8) + word.substring(2, 4) + word.substring(0, 2)

    /** 协商拒绝回执：`cmd=08`，返回码取尾部倒数第 6~3 个字符。 */
    fun negotiationReject(word: String = "E36F"): String = "A50008" + "0".repeat(8) + word + "00"

    /** 最终开锁回执：`type=87` + 尾 `5A`，体内含回执码。 */
    fun receipt(code: String, productKey: String): String {
        val plain = "0000" + code + "0".repeat(10)
        val body = DesBlock.encryptBlock(productKey, plain)
        return "A51405" + "0".repeat(8) + "0000" + "87" + body + "005A"
    }

    /** 通讯密钥片段（type=89），尾不是 5A 表示还没结束。 */
    fun commKeyFragment(): String = "A51601" + "AABBCCDD" + "0000" + "89" + "0".repeat(16) + "00"

    /** 时间同步片段（type=86）。 */
    fun timeSyncFragment(): String = "A51601" + "AABBCCDD" + "0000" + "86" + "0".repeat(16) + "5A"

    /** 黑名单片段（type=82）。 */
    fun blacklistFragment(): String = "A51601" + "AABBCCDD" + "0000" + "82" + "0".repeat(16) + "5A"
}

val TEST_SERVICE: BleService
    get() = BleService(
        uuid = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB"),
        readUuid = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB"),
        writeUuid = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB"),
        notifyUuids = listOf(UUID.fromString("0000FFF3-0000-1000-8000-00805F9B34FB")),
        writeWithResponse = true,
    )

val TEST_SEED: ByteArray get() = byteArrayOf(0x3C, 0x91.toByte(), 0x0F, 0xA7.toByte())

class FakeBleSession(
    override val deviceId: String,
    var discovery: ServiceDiscovery = ServiceDiscovery.Found(TEST_SERVICE),
    var seed: ByteArray = TEST_SEED,
    var readError: Throwable? = null,
    /** 收到写入后要「回」哪些帧。 */
    var responder: ((ByteArray) -> List<String>)? = null,
) : BleSession {

    val written = mutableListOf<ByteArray>()
    val subscribed = mutableListOf<UUID>()
    var closedTimes = 0
        private set

    private val subscribers = mutableListOf<(ByteArray) -> Unit>()

    override suspend fun findService(): ServiceDiscovery = discovery

    override suspend fun subscribe(uuid: UUID, onValue: (ByteArray) -> Unit) {
        subscribed += uuid
        subscribers += onValue
    }

    override suspend fun read(uuid: UUID): ByteArray {
        readError?.let { throw it }
        return seed
    }

    override suspend fun write(uuid: UUID, data: ByteArray) {
        written += data.copyOf()
        responder?.invoke(data)?.forEach { hex ->
            val bytes = Hex.bytesFromHex(hex)
            subscribers.toList().forEach { it(bytes) }
        }
    }

    override fun close() {
        closedTimes++
    }
}

class FakeBleAdapter(
    private val hits: List<ScanHit> = emptyList(),
    initialState: BleState = BleState.PoweredOn,
) : BleAdapter {

    override val state: MutableStateFlow<BleState> = MutableStateFlow(initialState)

    var scanCalls: Int = 0
        private set
    val connectedTo = mutableListOf<String>()
    var session: FakeBleSession? = null
    var connectError: Throwable? = null

    /** 扫描时直接抛异常（模拟缺权限 / 适配器被关掉）。 */
    var scanError: Throwable? = null

    override fun scan(filter: ScanFilter, allowDuplicates: Boolean): Flow<ScanHit> = flow {
        scanCalls++
        scanError?.let { throw it }
        for (hit in hits) {
            emit(hit)
            delay(5)
        }
        // 没有命中也别把流结束掉：真实扫描会一直跑，直到超时
        awaitCancellation()
    }

    override suspend fun connect(deviceId: String, timeoutMs: Long): BleSession {
        connectedTo += deviceId
        connectError?.let { throw it }
        return session ?: FakeBleSession(deviceId).also { session = it }
    }
}

class FakeHistory : UnlockHistory {
    val records = mutableListOf<UnlockRecord>()
    override fun add(record: UnlockRecord) {
        records += record
    }
}

class FakePeripheralCache : PeripheralCache {
    val cached = mutableMapOf<String, String>()
    val cleared = mutableListOf<String>()

    override fun put(doorId: String, deviceId: String) {
        cached[doorId] = deviceId
    }

    override fun clear(doorId: String) {
        cleared += doorId
        cached.remove(doorId)
    }
}

class RecordingHaptics : HapticsFeedback {
    var successes = 0
    var failures = 0

    override fun success() {
        successes++
    }

    override fun failure() {
        failures++
    }
}
