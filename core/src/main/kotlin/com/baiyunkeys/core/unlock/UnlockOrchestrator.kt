package com.baiyunkeys.core.unlock

import com.baiyunkeys.core.hex.Hex
import com.baiyunkeys.core.model.DoorConfig
import com.baiyunkeys.core.model.MacOrigin
import com.baiyunkeys.core.protocol.BleProtocol
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 蓝牙开锁流程编排：直连 → 扫描 → 连接 → 找服务 → 订阅 → 读随机数 → 握手 → 等回执。
 *
 * 对照 `BaiyunKeys-iOS/BaiyunKeys/Core/UnlockService.swift`（756 行）逐段移植：
 * 委托回调改成挂起函数 + `withTimeoutOrNull`，`DispatchWorkItem` 定时器改成超时，
 * 但 **阶段、超时值、重试语义、静默模式、分片缓存、日志格式** 全部保持原样。
 *
 * 与 iOS 的一处有意不同：**找门锁先直连、后扫描**。iOS 拿不到 MAC，只能靠广播名；
 * 安卓有真实 MAC 时可以直接 `connectGatt`，不依赖广播，也不受系统定位开关影响（issue #5）。
 * 顺序是「缓存地址 → 门禁 MAC → 扫描」：
 * - 缓存地址：上次连上的设备，沿用现有的 8 秒预算；
 * - 门禁 MAC：用 [UnlockTimeouts.directConnectMs]，并跳过占位地址和按蓝牙名反推的 `00:0X:` 地址；
 * - 扫描：直连失败后的兜底，匹配规则不变。
 *
 * 快速模式不直连（要按现场信号挑最近的门禁）；静默模式只认缓存地址，与 iOS 一致。
 *
 * 四个入口：
 * - [unlock] 单门锁
 * - [unlockAny] 快速模式（候选池按现场广播先到先得）
 * - [unlockIfCached] 静默直连（无缓存悄悄返回，不写历史不震动）
 * - [unlockAsync] 供 UI 用，返回可取消的 [Job]
 */
class UnlockOrchestrator(
    private val ble: BleAdapter,
    private val history: UnlockHistory,
    private val peripheralCache: PeripheralCache,
    private val haptics: HapticsFeedback = HapticsFeedback.None,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val timeouts: UnlockTimeouts = UnlockTimeouts(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * 日志镜像出口。core 不依赖 Android，所以这里只暴露回调；
     * app 层接 `android.util.Log`，真机上 `adb logcat` 就能看到完整开锁过程。
     */
    private val logSink: ((UnlockLog) -> Unit)? = null,
) {

    // MARK: - 对外状态

    private val _phase = MutableStateFlow<UnlockPhase>(UnlockPhase.Idle)
    val phase: StateFlow<UnlockPhase> = _phase.asStateFlow()

    private val _statusText = MutableStateFlow(DEFAULT_STATUS)
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _logs = MutableStateFlow<List<UnlockLog>>(emptyList())
    val logs: StateFlow<List<UnlockLog>> = _logs.asStateFlow()

    private val _attempt = MutableStateFlow(0)
    val attempt: StateFlow<Int> = _attempt.asStateFlow()

    private val _maxAttempts = MutableStateFlow(1)
    val maxAttempts: StateFlow<Int> = _maxAttempts.asStateFlow()

    /** 蓝牙不可用时给界面的提示，null 表示正常。 */
    private val _bluetoothIssue = MutableStateFlow<String?>(null)
    val bluetoothIssue: StateFlow<String?> = _bluetoothIssue.asStateFlow()

    /**
     * 界面需要把用户引去哪个系统设置页：缺蓝牙权限，或定位服务没开。
     * null 表示不需要；下一次开锁时清空。
     */
    private val _settingsNeeded = MutableStateFlow<SettingsTarget?>(null)
    val settingsNeeded: StateFlow<SettingsTarget?> = _settingsNeeded.asStateFlow()

    // MARK: - 内部状态

    private val busy = AtomicBoolean(false)
    private var running: Job? = null
    private var activeSession: BleSession? = null
    private var currentTarget: DoorConfig? = null
    private var currentDeviceId: String? = null
    private var silentMode = false

    /** 直连失败、但同一轮里扫描连上了的门锁：记录判定时刻，冷却期内不再先直连。 */
    private val directUnsupportedAt = mutableMapOf<String, Long>()

    /** 本轮已经直连失败过的门锁：同一轮的自动重试不再直连。 */
    private var directFailedDoor: String? = null

    // MARK: - 入口

    /** 供 UI 调用：起一个可被 [cancel] 打断的开锁流程。 */
    fun unlockAsync(door: DoorConfig, options: UnlockOptions = UnlockOptions()): Job =
        scope.launch { unlock(door, options) }.also { running = it }

    /** 单门锁开锁。 */
    suspend fun unlock(door: DoorConfig, options: UnlockOptions = UnlockOptions()): UnlockResult {
        if (!busy.compareAndSet(false, true)) return UnlockResult(false, BUSY_MESSAGE)
        return try {
            setSession(target = door, silent = options.silent, maxAttempts = options.maxAttempts)
            if (!door.isUsable) {
                // 前置校验失败不写开锁记录（与 iOS 一致）
                return fail("门禁参数不完整，请先在「配置」页填写 MAC、Key 与蓝牙名称", record = false)
            }
            log("开始开锁流程 · ${door.displayName}")
            execute(Request.Single(door), options)
        } catch (e: CancellationException) {
            onCancelled()
            throw e
        } finally {
            busy.set(false)
            running = null
        }
    }

    /** 快速模式：扫描附近任一门禁，命中候选池里哪条就开哪条。 */
    suspend fun unlockAny(doors: List<DoorConfig>, options: UnlockOptions = UnlockOptions()): UnlockResult {
        if (!busy.compareAndSet(false, true)) return UnlockResult(false, BUSY_MESSAGE)
        return try {
            val pool = doors.filter { it.isUsable }
            if (pool.isEmpty()) {
                setSession(target = null, silent = options.silent, maxAttempts = options.maxAttempts)
                return fail("没有可用的门禁，无法自动开锁", record = false)
            }
            setSession(target = pool.first(), silent = options.silent, maxAttempts = options.maxAttempts)
            log("快速模式：扫描附近门禁（候选 ${pool.size} 个）")
            execute(Request.AnyOf(pool), options)
        } catch (e: CancellationException) {
            onCancelled()
            throw e
        } finally {
            busy.set(false)
            running = null
        }
    }

    /**
     * 静默开锁：只用缓存设备直连。
     *
     * 没缓存 / 参数不全时**什么都不做**（不改阶段、不写日志、不震动），与 iOS 一致。
     * 「切回也开锁」走这条路径。
     */
    suspend fun unlockIfCached(door: DoorConfig, options: UnlockOptions = UnlockOptions()): UnlockResult {
        if (busy.get() || !door.isUsable || door.cachedPeripheralId == null) {
            return UnlockResult(false, "")
        }
        if (!busy.compareAndSet(false, true)) return UnlockResult(false, BUSY_MESSAGE)
        return try {
            setSession(target = door, silent = true, maxAttempts = 1)
            log("切回也开锁 · ${door.displayName}")
            execute(Request.Cached(door), options.copy(silent = true, autoRetry = false))
        } catch (e: CancellationException) {
            onCancelled()
            throw e
        } finally {
            busy.set(false)
            running = null
        }
    }

    fun cancel() {
        if (!_phase.value.isBusy) return
        log("用户中断开锁流程", LogLevel.WARN)
        running?.cancel()
    }

    fun reset() {
        if (_phase.value.isBusy) return
        _phase.value = UnlockPhase.Idle
        _statusText.value = DEFAULT_STATUS
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    // MARK: - 流程主干

    private suspend fun execute(request: Request, options: UnlockOptions): UnlockResult {
        var attempt = 1
        while (true) {
            _attempt.value = attempt
            _phase.value = UnlockPhase.Preparing
            _statusText.value = if (attempt == 1) "正在初始化蓝牙…" else "第 $attempt 次尝试…"
            if (attempt == 1) log("初始化蓝牙适配器")

            // 兜底：扫描 / 连接 / 协商里任何一处抛异常（缺权限、GATT 崩、适配器被拔掉…），
            // 都必须落成一个明确结果，绝不能让界面卡在「进行中」不动。
            val outcome = try {
                runAttempt(request, options)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BlePermissionException) {
                _settingsNeeded.value = SettingsTarget.BluetoothPermission
                Outcome.Retryable(e.message ?: "缺少蓝牙权限")
            } catch (e: LocationServiceDisabledException) {
                _settingsNeeded.value = SettingsTarget.LocationService
                Outcome.Retryable(locationOffMessage(request.primaryDoor))
            } catch (e: ScanStartFailedException) {
                // 系统已经拒绝了这次扫描，自动重试只会撞得更狠，直接结束
                Outcome.ProtocolFailure(e.message ?: "扫描启动失败")
            } catch (e: Exception) {
                Outcome.Retryable(e.message ?: "开锁流程异常（${e::class.simpleName}）")
            }

            when (outcome) {
                is Outcome.Success -> return finish(true, outcome.message)
                is Outcome.ProtocolFailure -> return finish(false, outcome.message)
                is Outcome.SilentAbort -> {
                    teardown()
                    _phase.value = UnlockPhase.Idle
                    _statusText.value = DEFAULT_STATUS
                    silentMode = false
                    log(outcome.reason, LogLevel.DEBUG)
                    return UnlockResult(false, outcome.reason)
                }

                is Outcome.Retryable -> {                    if (attempt < options.maxAttempts) {
                        log("${outcome.message}，自动重试（$attempt/${options.maxAttempts}）", LogLevel.WARN)
                        teardown()
                        _statusText.value = "重试中（$attempt/${options.maxAttempts}）…"
                        delay(timeouts.retryDelayMs)
                        attempt++
                    } else {
                        return finish(false, outcome.message)
                    }
                }
            }
        }
    }

    private suspend fun runAttempt(request: Request, options: UnlockOptions): Outcome {
        val state = awaitBluetoothState()
        if (!state.isReady) {
            val issue = issueFor(state) ?: "蓝牙适配器状态未知，请检查蓝牙是否可用"
            _bluetoothIssue.value = issue
            return Outcome.ProtocolFailure(issue)
        }
        _bluetoothIssue.value = null
        log("蓝牙适配器已就绪")

        val door = request.primaryDoor

        // ① 直连：缓存地址 → 真实 MAC。快速模式不参与；推导值与占位地址不参与
        val target = if (request is Request.AnyOf) null else directTarget(door)
        if (target != null) {
            val viaCache = target == door.cachedPeripheralId
            log(if (viaCache) "命中缓存设备，直接连接 ${target.take(8)}…" else "按 MAC 直连 ${target.take(8)}…")
            val budget = if (viaCache) timeouts.connectMs else timeouts.directConnectMs
            when (val connected = connect(target, budget)) {
                is ConnectResult.Ok -> {
                    directUnsupportedAt.remove(door.id)
                    return withSession(connected.session, request)
                }
                is ConnectResult.Timeout -> log("直连 ${target.take(8)}… 超时，改为扫描", LogLevel.WARN)
                is ConnectResult.Failed ->
                    log("直连 ${target.take(8)}… 失败（${connected.message}），改为扫描", LogLevel.WARN)
            }
            directFailedDoor = door.id
        }

        // ② 静默模式只走直连，不进扫描
        if (options.silent) return Outcome.SilentAbort("静默模式无缓存设备，跳过")

        // ③ 扫描
        val match = when (val step = scanFor(request, options)) {
            is ScanStep.Hit -> step.match
            is ScanStep.Miss -> return Outcome.Retryable(scanMissMessage(step.seen, quick = request is Request.AnyOf))
        }
        if (request is Request.AnyOf) {
            log("快速模式命中：${match.door.displayName}（RSSI ${match.hit.rssi}）")
        } else {
            log("匹配到门锁 ${match.hit.name ?: match.hit.deviceId}（RSSI ${match.hit.rssi}）")
        }
        currentTarget = match.door
        when (val connected = connect(match.hit.deviceId, timeouts.connectMs)) {
            is ConnectResult.Timeout -> return Outcome.Retryable(CONNECT_TIMEOUT_MESSAGE)
            is ConnectResult.Failed -> return Outcome.Retryable(connected.message)
            is ConnectResult.Ok -> {
                // 门锁在场、能连上，只是直连不行：冷却期内不再先直连
                if (directFailedDoor == match.door.id) directUnsupportedAt[match.door.id] = clock.millis()
                return withSession(connected.session, request)
            }
        }
    }

    // MARK: - 扫描 / 连接

    private suspend fun scanFor(request: Request, options: UnlockOptions): ScanStep {
        _phase.value = UnlockPhase.Scanning
        _statusText.value = if (request is Request.AnyOf) "正在搜索附近门禁…" else "正在搜索门锁…"
        if (options.scanFilter.isEmpty) {
            log("开始扫描（不按服务过滤，靠蓝牙名 / MAC 匹配），超时 ${options.scanTimeoutMs / 1000}s")
        } else {
            log("开始扫描（服务 UUID 过滤，后台模式），超时 ${options.scanTimeoutMs / 1000}s")
        }

        val seen = HashSet<String>()
        val match = withTimeoutOrNull(options.scanTimeoutMs) {
            ble.scan(options.scanFilter, allowDuplicates = false)
                .mapNotNull { hit ->
                    val firstSighting = seen.add(hit.deviceId)
                    // 逐设备的日志只记前几台：周围设备一多，开头的行会被刷掉，而那正是排查要看的
                    if (firstSighting && seen.size <= MAX_LISTED_DEVICES) {
                        log("发现设备 ${hit.name ?: "未知"} RSSI=${hit.rssi}", LogLevel.DEBUG)
                    }
                    val door = when (request) {
                        is Request.Single -> request.door.takeIf { DeviceMatcher.matches(hit, it) }
                        is Request.Cached -> request.door.takeIf { DeviceMatcher.matches(hit, it) }
                        is Request.AnyOf -> request.pool.firstOrNull { DeviceMatcher.matches(hit, it) }
                    }
                    if (door == null) null else ScanMatch(door, hit)
                }
                .firstOrNull()
        }
        return if (match == null) {
            log("扫描结束：发现 ${seen.size} 台设备，没有匹配的", LogLevel.WARN)
            ScanStep.Miss(seen.size)
        } else {
            ScanStep.Hit(match)
        }
    }

    /**
     * 没扫到门锁时，按「一台设备都没看到」还是「看到了但都不匹配」给不同的提示。
     * [quick] 为 true 时是在候选池里找，不能说「这条门禁」。
     */
    private fun scanMissMessage(seen: Int, quick: Boolean): String {
        if (seen == 0) {
            return "附近没有发现任何蓝牙设备，请靠近门锁。若仍失败，部分机型需要打开系统定位服务才能扫描"
        }
        val what = if (quick) "候选里的任何一条门禁" else "这条门禁"
        return "发现 $seen 台蓝牙设备，但没有一台与${what}匹配。可以到「配置 → 打开扫描面板」手动选择门锁"
    }

    /** 定位没开时，如果这条门禁的 MAC 是按蓝牙名反推的，顺带告诉用户补填真实 MAC 可以不走扫描。 */
    private fun locationOffMessage(door: DoorConfig): String {
        val base = LocationServiceDisabledException.MESSAGE
        return if (MacOrigin.looksDerivedFromName(door.mac, door.bluetoothName)) {
            "$base。这条门禁的 MAC 是按蓝牙名反推的，没法直连；在配置里补填真实 MAC 后，可以不扫描直接连接"
        } else {
            base
        }
    }

    private suspend fun connect(deviceId: String, timeoutMs: Long): ConnectResult {
        _phase.value = UnlockPhase.Connecting
        _statusText.value = "正在连接门锁…"
        log("连接设备 $deviceId")
        return try {
            val session = withTimeoutOrNull(timeoutMs) { ble.connect(deviceId, timeoutMs) }
            if (session == null) ConnectResult.Timeout else ConnectResult.Ok(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 连接失败不清缓存：缓存里可能是扫描学到的真实地址或手动绑定的地址
            ConnectResult.Failed("连接门锁失败：${e.message ?: "未知错误"}")
        }
    }

    /** 直连的目标：缓存地址优先，其次真实 MAC。返回 null 表示本次不直连。 */
    private fun directTarget(door: DoorConfig): String? {
        if (directFailedDoor == door.id) return null
        val unsupportedSince = directUnsupportedAt[door.id]
        if (unsupportedSince != null && clock.millis() - unsupportedSince < DIRECT_RETRY_COOLDOWN_MS) return null
        door.cachedPeripheralId?.takeIf(::isConnectableAddress)?.let { return it }
        if (MacOrigin.looksDerivedFromName(door.mac, door.bluetoothName)) {
            log("MAC ${door.mac} 是按蓝牙名推算的，不是真实地址，无法直连", LogLevel.DEBUG)
            return null
        }
        return door.mac.takeIf(::isConnectableAddress)
    }

    /** 合法、且不是占位地址（全 0 / 全 F / 02:00:00:00:00:00）。 */
    private fun isConnectableAddress(value: String): Boolean {
        if (!Hex.isValidMac(value)) return false
        val compact = Hex.sanitizeKey(value)
        val placeholder = compact.all { it == '0' } || compact.all { it == 'F' } || compact == "020000000000"
        return !placeholder
    }

    private suspend fun withSession(session: BleSession, request: Request): Outcome {
        try {
            activeSession = session
            currentDeviceId = session.deviceId
            return negotiate(session, request)
        } finally {
            runCatching { session.close() }
            activeSession = null
        }
    }

    // MARK: - 协商

    private suspend fun negotiate(session: BleSession, request: Request): Outcome {
        _phase.value = UnlockPhase.Negotiating
        _statusText.value = "正在初始化蓝牙服务…"
        log("蓝牙连接成功")

        val discovery = try {
            withTimeoutOrNull(timeouts.connectMs) { session.findService() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return Outcome.Retryable("蓝牙服务发现超时")

        val service = when (discovery) {
            is ServiceDiscovery.Found -> discovery.service
            ServiceDiscovery.NoServices -> return Outcome.Retryable("未发现任何蓝牙服务，请确认门锁正在广播")
            ServiceDiscovery.NoReadWriteCharacteristics -> return Outcome.ProtocolFailure("未找到可读写的蓝牙特征")
        }
        log(
            "命中服务 ${service.uuid}（read=${service.readUuid} write=${service.writeUuid} " +
                "notify=${service.notifyUuids.size}）",
            LogLevel.DEBUG,
        )

        val notifications = Channel<String>(Channel.UNLIMITED)
        try {
            if (service.notifyUuids.isEmpty()) {
                log("服务没有可订阅特征，直接读取随机数", LogLevel.DEBUG)
            } else {
                for (uuid in service.notifyUuids) {
                    runCatching {
                        session.subscribe(uuid) { bytes -> notifications.trySend(Hex.hexFromBytes(bytes)) }
                    }
                }
            }

            // ② 读随机数
            _statusText.value = "正在与门锁握手…"
            log("读取门锁随机数")
            val seed = try {
                withTimeoutOrNull(timeouts.seedMs) { session.read(service.readUuid) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return Outcome.Retryable("读取门锁随机数超时")
            log("随机数 ${Hex.hexFromBytes(seed)}")

            // ③ 发握手
            val door = request.primaryDoor
            val header = runCatching { BleProtocol.headerFromMac(door.mac) }.getOrElse {
                return Outcome.ProtocolFailure("MAC 地址格式不合法，无法生成握手标识")
            }
            val command = runCatching {
                BleProtocol.handshakeCommand(seed, header, Hex.sanitizeKey(door.key))
            }.getOrElse {
                return Outcome.ProtocolFailure(it.message ?: "握手指令构造失败")
            }

            log("握手指令 ${Hex.hexFromBytes(command)}", LogLevel.DEBUG)
            try {
                withTimeoutOrNull(timeouts.connectMs) { session.write(service.writeUuid, command) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Outcome.Retryable("写入开锁指令失败：${e.message ?: "未知错误"}")
            }

            _phase.value = UnlockPhase.Waiting
            _statusText.value = "已发送开锁指令，等待门锁响应…"

            // ④ 等回执
            return awaitReceipt(notifications, door.key)
        } finally {
            notifications.close()
        }
    }

    private suspend fun awaitReceipt(notifications: Channel<String>, productKey: String): Outcome {
        val processor = NotificationProcessor(productKey)
        var terminal: ProtocolEvent.Terminal? = null

        val finished = withTimeoutOrNull(timeouts.ackMs) {
            while (terminal == null) {
                val hex = notifications.receive()
                for (event in processor.process(hex)) {
                    when (event) {
                        is ProtocolEvent.Log -> log(event.text, event.level)
                        is ProtocolEvent.Status -> _statusText.value = event.text
                        is ProtocolEvent.Terminal -> terminal = event
                    }
                }
            }
            true
        } ?: false

        val result = terminal
        if (!finished || result == null) return Outcome.Retryable("等待门锁回执超时")
        return if (result.success) Outcome.Success(result.message) else Outcome.ProtocolFailure(result.message)
    }

    // MARK: - 收尾

    private fun finish(success: Boolean, message: String): UnlockResult {
        // 缓存必须在 teardown 之前取，与 iOS 一致
        if (success) {
            val target = currentTarget
            val deviceId = currentDeviceId
            if (target != null && deviceId != null) peripheralCache.put(target.id, deviceId)
        }

        val visible = !silentMode || success
        if (visible) {
            recordHistory(ok = success, message = message)
            if (success) haptics.success() else haptics.failure()
        }

        teardown()

        if (visible) {
            _phase.value = if (success) UnlockPhase.Success else UnlockPhase.Failure(message)
            _statusText.value = message
            log(message, if (success) LogLevel.INFO else LogLevel.ERROR)
        } else {
            _phase.value = UnlockPhase.Idle
            _statusText.value = DEFAULT_STATUS
            log(message, LogLevel.DEBUG)
        }
        silentMode = false
        return UnlockResult(success, message)
    }

    /** 前置校验等「不该重试」的失败。[record] 为 false 时不进开锁记录。 */
    private fun fail(message: String, record: Boolean = true): UnlockResult {
        if (record && !silentMode) recordHistory(ok = false, message = message)
        teardown()
        if (silentMode) {
            _phase.value = UnlockPhase.Idle
            _statusText.value = DEFAULT_STATUS
            log(message, LogLevel.DEBUG)
        } else {
            _phase.value = UnlockPhase.Failure(message)
            _statusText.value = message
            log(message, LogLevel.ERROR)
            haptics.failure()
        }
        silentMode = false
        return UnlockResult(false, message)
    }

    private fun teardown() {
        runCatching { activeSession?.close() }
        activeSession = null
    }

    private fun onCancelled() {
        teardown()
        _phase.value = UnlockPhase.Idle
        _statusText.value = "已中断"
        silentMode = false
        busy.set(false)
    }

    private fun setSession(target: DoorConfig?, silent: Boolean, maxAttempts: Int) {
        currentTarget = target
        currentDeviceId = null
        directFailedDoor = null
        silentMode = silent
        _maxAttempts.value = maxAttempts
        _attempt.value = 0
        _bluetoothIssue.value = null
        _settingsNeeded.value = null
        _logs.value = emptyList()
    }

    private fun recordHistory(ok: Boolean, message: String) {
        val target = currentTarget ?: return
        history.add(
            UnlockRecord.now(
                clock = clock,
                doorId = target.id,
                doorName = target.displayName,
                ok = ok,
                message = message,
            ),
        )
    }

    // MARK: - 蓝牙状态

    private suspend fun awaitBluetoothState(): BleState {
        val current = ble.state.value
        if (current != BleState.Unknown) return current
        return withTimeoutOrNull(BLUETOOTH_READY_TIMEOUT_MS) {
            ble.state.first { it != BleState.Unknown }
        } ?: BleState.Unknown
    }

    private fun issueFor(state: BleState): String? = when (state) {
        BleState.PoweredOff -> "蓝牙已关闭，请在控制中心或设置中打开蓝牙"
        BleState.Unauthorized -> "未授权使用蓝牙，请到「设置 → 应用 → 权限」中允许"
        BleState.Unsupported -> "当前设备不支持蓝牙低功耗"
        BleState.Resetting -> "蓝牙正在重置，请稍候"
        BleState.PoweredOn -> null
        BleState.Unknown -> null
    }

    // MARK: - 日志

    private fun log(text: String, level: LogLevel = LogLevel.INFO) {
        val line = UnlockLog.now(clock, zone, level, text)
        _logs.update { current ->
            val next = current + line
            if (next.size > UnlockLog.MAX_LINES) next.takeLast(UnlockLog.MAX_LINES) else next
        }
        runCatching { logSink?.invoke(line) }
    }

    // MARK: - 内部类型

    private sealed interface Request {
        val primaryDoor: DoorConfig

        data class Single(val door: DoorConfig) : Request {
            override val primaryDoor: DoorConfig get() = door
        }

        data class AnyOf(val pool: List<DoorConfig>) : Request {
            override val primaryDoor: DoorConfig get() = pool.first()
        }

        data class Cached(val door: DoorConfig) : Request {
            override val primaryDoor: DoorConfig get() = door
        }
    }

    private data class ScanMatch(val door: DoorConfig, val hit: ScanHit)

    /** 扫描这一步的结果：命中了，或没命中（带上一共看到几台设备）。 */
    private sealed interface ScanStep {
        data class Hit(val match: ScanMatch) : ScanStep
        data class Miss(val seen: Int) : ScanStep
    }

    private sealed interface ConnectResult {
        data class Ok(val session: BleSession) : ConnectResult
        data object Timeout : ConnectResult
        data class Failed(val message: String) : ConnectResult
    }

    private sealed interface Outcome {
        data class Success(val message: String) : Outcome
        data class ProtocolFailure(val message: String) : Outcome
        data class Retryable(val message: String) : Outcome
        data class SilentAbort(val reason: String) : Outcome
    }

    companion object {
        const val DEFAULT_STATUS = "准备就绪"
        const val BUSY_MESSAGE = "开锁流程已在进行中"
        const val CONNECT_TIMEOUT_MESSAGE = "连接门锁超时，请靠近门锁后重试"
        private const val BLUETOOTH_READY_TIMEOUT_MS = 2_000L

        /** 判定为「不支持直连」之后，多久以内不再先直连。 */
        const val DIRECT_RETRY_COOLDOWN_MS = 10 * 60_000L

        /** 一次扫描里逐台写进日志的设备上限，超出的只计数。 */
        private const val MAX_LISTED_DEVICES = 8
    }
}
