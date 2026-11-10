package com.xavior.baiyunkeys.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.baiyunkeys.core.unlock.BleAdapter
import com.baiyunkeys.core.unlock.BleService
import com.baiyunkeys.core.unlock.BleSession
import com.baiyunkeys.core.unlock.BleState
import com.baiyunkeys.core.unlock.ScanFilter
import com.baiyunkeys.core.unlock.ScanHit
import com.baiyunkeys.core.unlock.ServiceDiscovery
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 原生 BLE 适配器：`BluetoothLeScanner` + `BluetoothGatt`。
 *
 * 关键点（对应方案 §4.3）：
 * - 前台扫描**不按服务过滤**，靠 MAC / 蓝牙名匹配；后台才带服务 UUID
 * - 连接用 `TRANSPORT_LE`，8s 超时
 * - 服务发现后选「同一服务里同时有 read + write 特征」的那个
 * - GATT 133 错误由 [AndroidBleSession] 记日志、由编排器重试
 * - Android 10 扫描前必须确认**系统定位服务已开启**，否则扫不到任何设备
 */
class BleAdapterImpl(private val context: Context) : BleAdapter {

    private val manager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)

    private val bluetoothAdapter: BluetoothAdapter? = manager?.adapter

    private val _state = MutableStateFlow(readAdapterState())
    override val state: StateFlow<BleState> = _state.asStateFlow()

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                _state.value = readAdapterState()
            }
        }
    }

    init {
        runCatching {
            context.registerReceiver(
                stateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            )
        }
    }

    private fun readAdapterState(): BleState {
        val state = bluetoothAdapter?.state ?: return BleState.Unsupported
        return when (state) {
            BluetoothAdapter.STATE_ON -> BleState.PoweredOn
            BluetoothAdapter.STATE_OFF -> BleState.PoweredOff
            BluetoothAdapter.STATE_TURNING_ON, BluetoothAdapter.STATE_TURNING_OFF -> BleState.Resetting
            else -> BleState.Unknown
        }
    }

    // MARK: - 扫描

    /**
     * 扫描。
     *
     * 权限不在这里逐个 if，而是进方法先调 [ensureScanPermission] / [ensureLocationEnabled]：
     * 缺权限直接抛 [ScanPermissionMissingException]，由上层弹「去设置」。
     * 因此对 lint 的 `MissingPermission` 做局部抑制（检查在 ensure* 里）。
     */
    @SuppressLint("MissingPermission")
    override fun scan(filter: ScanFilter, allowDuplicates: Boolean): Flow<ScanHit> = callbackFlow {
        val adapter = bluetoothAdapter
            ?: throw IllegalStateException("当前设备不支持蓝牙")

        ensureScanPermission()
        ensureLocationEnabled()

        val scanner = adapter.bluetoothLeScanner
            ?: throw IllegalStateException("蓝牙扫描器不可用，请先打开蓝牙")

        // Android 没有 AllowDuplicates 开关，自己按设备地址去重，语义对齐 CoreBluetooth
        val seen = mutableSetOf<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val hit = result.toHit()
                if (!allowDuplicates && !seen.add(hit.deviceId)) return
                trySendBlocking(hit)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("扫描启动失败（错误码 $errorCode）"))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val nativeFilters = filter.serviceUuids.map { uuid ->
            android.bluetooth.le.ScanFilter.Builder()
                .setServiceUuid(android.os.ParcelUuid(uuid))
                .build()
        }

        scanner.startScan(nativeFilters, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    /**
     * 扫描结果 → [ScanHit]。
     *
     * `device.name` 在 API 31+ 需要 BLUETOOTH_CONNECT：扫描前 [ensureScanPermission] 已经查过，
     * 且这里还套了 `runCatching`（个别机型会抛 SecurityException）。
     */
    @SuppressLint("MissingPermission")
    private fun ScanResult.toHit(): ScanHit = ScanHit(
        deviceId = device.address,
        name = scanRecord?.deviceName ?: runCatching { device.name }.getOrNull(),
        rssi = rssi,
        manufacturerData = scanRecord?.manufacturerData(),
    )

    /**
     * 从原始广播字节里抠出「厂商自定义数据」（AD type 0xFF）。
     *
     * Android 的 `getManufacturerSpecificData(companyId)` 要求先知道 company ID，而门锁用哪家
     * 的 ID 我们并不知道；直接扫原始 AD 结构，语义才与 iOS 的
     * `CBAdvertisementDataManufacturerDataKey` 对齐（同样包含前 2 字节 company ID）。
     */
    private fun android.bluetooth.le.ScanRecord.manufacturerData(): ByteArray? {
        val raw = bytes ?: return null
        var index = 0
        while (index < raw.size) {
            val length = raw[index].toInt() and 0xFF
            if (length == 0) break
            if (index + 1 + length > raw.size) break
            val type = raw[index + 1].toInt() and 0xFF
            if (type == AD_TYPE_MANUFACTURER && length >= 3) {
                return raw.copyOfRange(index + 2, index + 1 + length)
            }
            index += length + 1
        }
        return null
    }

    // MARK: - 连接

    @SuppressLint("MissingPermission")
    override suspend fun connect(deviceId: String, timeoutMs: Long): BleSession {
        ensureConnectPermission()
        val adapter = bluetoothAdapter ?: throw IllegalStateException("当前设备不支持蓝牙")
        if (adapter.state != BluetoothAdapter.STATE_ON) {
            throw IllegalStateException("蓝牙未开启")
        }

        val device: BluetoothDevice = adapter.getRemoteDevice(deviceId)
        val session = AndroidBleSession(context, device, timeoutMs)

        // GATT 133 是 Android 上最常见的连接失败，先关掉重连一次再交给上层重试
        return try {
            session.connect()
            session
        } catch (first: Exception) {
            session.close()
            delay(GATT_RETRY_DELAY_MS)
            val retry = AndroidBleSession(context, device, timeoutMs)
            try {
                retry.connect()
                retry
            } catch (second: Exception) {
                retry.close()
                throw second
            }
        }
    }

    // MARK: - 权限

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun ensureScanPermission() {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (!granted) throw ScanPermissionMissingException()
    }

    private fun ensureConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            throw ConnectPermissionMissingException()
        }
    }

    /**
     * Android 10 / 11 的硬性要求：定位服务不开，扫描拿不到任何结果（且不报错）。
     * 提前拦下来，好让 UI 把用户引到定位开关。
     */
    private fun ensureLocationEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val enabled = runCatching { lm.isLocationEnabled }.getOrDefault(true)
        if (!enabled) throw LocationServiceDisabledException()
    }

    private companion object {
        const val GATT_RETRY_DELAY_MS = 300L

        /** AD type 0xFF：厂商自定义数据。 */
        const val AD_TYPE_MANUFACTURER = 0xFF
    }
}

/** 缺少扫描权限。UI 据此弹「去设置」。 */
class ScanPermissionMissingException : SecurityException("缺少蓝牙扫描权限")

class ConnectPermissionMissingException : SecurityException("缺少蓝牙连接权限")

/** Android 10/11 定位服务未开启（扫描会静默失败）。 */
class LocationServiceDisabledException : IllegalStateException("系统定位服务未开启，Android 10 上无法扫描到蓝牙设备")

/**
 * 一次 GATT 连接。
 *
 * Android 的 GATT 操作是串行的：同一时刻只能有一个 read / write / discover，
 * 这里用 [opMutex] 把上层并发调用排队，回调侧用 [CompletableDeferred] 唤醒。
 */
@SuppressLint("MissingPermission")
internal class AndroidBleSession(
    private val context: Context,
    private val device: BluetoothDevice,
    private val connectTimeoutMs: Long,
) : BleSession {

    override val deviceId: String get() = device.address

    private val opMutex = Mutex()

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var connectDeferred: CompletableDeferred<Unit>? = null

    @Volatile
    private var servicesDeferred: CompletableDeferred<List<BluetoothGattService>>? = null

    @Volatile
    private var readDeferred: CompletableDeferred<ByteArray>? = null

    @Volatile
    private var writeDeferred: CompletableDeferred<Unit>? = null

    @Volatile
    private var descriptorDeferred: CompletableDeferred<Unit>? = null

    private val subscribers = mutableMapOf<UUID, MutableList<(ByteArray) -> Unit>>()

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> if (status == BluetoothGatt.GATT_SUCCESS) {
                    connectDeferred?.complete(Unit)
                } else {
                    connectDeferred?.completeExceptionally(gattError(status))
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val error = gattError(status)
                    connectDeferred?.completeExceptionally(error)
                    servicesDeferred?.completeExceptionally(error)
                    readDeferred?.completeExceptionally(error)
                    writeDeferred?.completeExceptionally(error)
                    descriptorDeferred?.completeExceptionally(error)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                servicesDeferred?.complete(gatt.services.orEmpty())
            } else {
                servicesDeferred?.completeExceptionally(gattError(status))
            }
        }

        // API 33+ 走这个重载
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                readDeferred?.complete(value)
            } else {
                readDeferred?.completeExceptionally(gattError(status))
            }
        }

        // API < 33 走这个重载
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                readDeferred?.complete(characteristic.value ?: ByteArray(0))
            } else {
                readDeferred?.completeExceptionally(gattError(status))
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeDeferred?.complete(Unit)
            } else {
                writeDeferred?.completeExceptionally(gattError(status))
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                descriptorDeferred?.complete(Unit)
            } else {
                descriptorDeferred?.completeExceptionally(gattError(status))
            }
        }

        // API 33+ 走这个重载
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            dispatch(characteristic.uuid, value)
        }

        // API < 33 走这个重载
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            dispatch(characteristic.uuid, characteristic.value ?: ByteArray(0))
        }
    }

    private fun dispatch(uuid: UUID, value: ByteArray) {
        val list = synchronized(subscribers) { subscribers[uuid]?.toList() } ?: return
        val copy = value.copyOf()
        list.forEach { it(copy) }
    }

    suspend fun connect() {
        val deferred = CompletableDeferred<Unit>()
        connectDeferred = deferred
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) throw IllegalStateException("无法建立 GATT 连接")
        withTimeoutOrNull(connectTimeoutMs) { deferred.await() }
            ?: throw IllegalStateException("连接门锁超时")
    }

    override suspend fun findService(): ServiceDiscovery = opMutex.withLock {
        val gatt = gatt ?: throw IllegalStateException("连接已关闭")

        val deferred = CompletableDeferred<List<BluetoothGattService>>()
        servicesDeferred = deferred
        if (!gatt.discoverServices()) throw IllegalStateException("服务发现启动失败")

        val services = withTimeoutOrNull(connectTimeoutMs) { deferred.await() }
            ?: throw IllegalStateException("蓝牙服务发现超时")
        if (services.isEmpty()) return@withLock ServiceDiscovery.NoServices

        // 只认「同一个服务里同时有 read + write」的那个，兼容服务 UUID 未知的门锁
        for (service in services) {
            val characteristics = service.characteristics.orEmpty()
            val read = characteristics.firstOrNull {
                it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
            }
            val write = characteristics.firstOrNull {
                it.properties and (
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                    ) != 0
            }
            if (read == null || write == null) continue

            val notify = characteristics.filter {
                it.properties and (
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_INDICATE
                    ) != 0
            }
            return@withLock ServiceDiscovery.Found(
                BleService(
                    uuid = service.uuid,
                    readUuid = read.uuid,
                    writeUuid = write.uuid,
                    notifyUuids = notify.map { it.uuid },
                    writeWithResponse = write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0,
                ),
            )
        }
        ServiceDiscovery.NoReadWriteCharacteristics
    }

    override suspend fun subscribe(uuid: UUID, onValue: (ByteArray) -> Unit) {
        synchronized(subscribers) { subscribers.getOrPut(uuid) { mutableListOf() }.add(onValue) }

        opMutex.withLock {
            val gatt = gatt ?: throw IllegalStateException("连接已关闭")
            val characteristic = findCharacteristic(gatt, uuid)
                ?: throw IllegalStateException("找不到通知特征 $uuid")

            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                throw IllegalStateException("订阅通知失败 $uuid")
            }

            // 写 CCCD 才会真正收到通知；有的门锁没有 CCCD，忽略即可
            val cccd = characteristic.getDescriptor(CCCD_UUID) ?: return@withLock
            val deferred = CompletableDeferred<Unit>()
            descriptorDeferred = deferred
            writeDescriptor(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            withTimeoutOrNull(SUBSCRIBE_TIMEOUT_MS) { deferred.await() }
        }
    }

    override suspend fun read(uuid: UUID): ByteArray = opMutex.withLock {
        val gatt = gatt ?: throw IllegalStateException("连接已关闭")
        val characteristic = findCharacteristic(gatt, uuid)
            ?: throw IllegalStateException("找不到可读特征 $uuid")

        val deferred = CompletableDeferred<ByteArray>()
        readDeferred = deferred
        if (!gatt.readCharacteristic(characteristic)) {
            throw IllegalStateException("读取请求发送失败")
        }
        withTimeoutOrNull(READ_TIMEOUT_MS) { deferred.await() }
            ?: throw IllegalStateException("读取超时")
    }

    override suspend fun write(uuid: UUID, data: ByteArray) {
        opMutex.withLock {
            val gatt = gatt ?: throw IllegalStateException("连接已关闭")
            val characteristic = findCharacteristic(gatt, uuid)
                ?: throw IllegalStateException("找不到可写特征 $uuid")

            val writeType = if (
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            ) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }

            val deferred = CompletableDeferred<Unit>()
            writeDeferred = deferred

            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+ 返回 BluetoothStatusCodes，不再是 GATT_SUCCESS
                gatt.writeCharacteristic(characteristic, data, writeType) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    characteristic.writeType = writeType
                    characteristic.value = data
                    gatt.writeCharacteristic(characteristic)
                }
            }
            if (!started) throw IllegalStateException("写入请求发送失败")

            // NO_RESPONSE 没有回调，不必等
            if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() }
                    ?: throw IllegalStateException("写入超时")
            }
        }
    }

    private fun findCharacteristic(gatt: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        for (service in gatt.services.orEmpty()) {
            service.characteristics?.firstOrNull { it.uuid == uuid }?.let { return it }
        }
        return null
    }

    private fun writeDescriptor(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    override fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        synchronized(subscribers) { subscribers.clear() }
        val error = IllegalStateException("连接已关闭")
        connectDeferred?.completeExceptionally(error)
        servicesDeferred?.completeExceptionally(error)
        readDeferred?.completeExceptionally(error)
        writeDeferred?.completeExceptionally(error)
        descriptorDeferred?.completeExceptionally(error)
    }

    private fun gattError(status: Int): IllegalStateException = when (status) {
        133 -> IllegalStateException("GATT 133：连接被系统断开，请重试")
        8 -> IllegalStateException("连接超时（GATT 8）")
        else -> IllegalStateException("GATT 错误（status=$status）")
    }

    private companion object {
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        const val READ_TIMEOUT_MS = 5_000L
        const val WRITE_TIMEOUT_MS = 5_000L
        const val SUBSCRIBE_TIMEOUT_MS = 1_500L
    }
}
