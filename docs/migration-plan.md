# 白云钥匙 · Android 迁移方案（Kotlin）

- 版本：v1.0（2026-11-10）
- 状态：方案定稿，待 P0 开工
- 上游：`BaiyunKeys-iOS`（Swift / SwiftUI，iOS 17+）
- 目标：Android 原生 App（Kotlin + Jetpack Compose），功能对齐 iOS 版（iOS 专有能力除外），与 iOS / Web 版数据互通

---

## 0. 目标与范围

### v1 范围（已确认）

- 前台开锁：单门锁 / 快速模式（扫描附近任一门禁）/ 静默回前台开锁
- 门禁管理：增删改、掩码显示、文本与二维码导入导出、复制参数、复制直达链接
- 自动获取配置：三通道（手机号+身份证 / 手机号+OpenID / 手机号+OpenID+SM2 加密版）
- 偏好设置：掩码、触感、打开即开锁、切回也开锁、失败自动重试、重试次数、扫描超时、保存开锁记录、生物识别、外观、长辈模式
- 快捷方式（ShortcutManager）：立即开锁 / 快速模式
- URL scheme：`baiyunkeys://unlock`、`baiyunkeys://unlock?door=<id>`
- 生物识别锁屏（冷启动一次）
- 蓝牙扫描面板、运行日志（200 行）、开锁记录（50 条）

### v1 不做（P5 再补）

- 磁贴（TileService）
- 桌面小组件（Glance）
- 后台无头开锁（前台服务）
- 更新检查（不移植）

### 非目标

- iOS / watchOS（保持 Swift 原生）
- Windows / 桌面（Web 版已覆盖）

---

## 1. 技术选型

| 维度 | 选型 | 说明 |
|---|---|---|
| 语言 / UI | Kotlin 2.x + Jetpack Compose（Material3） | 声明式模型与 SwiftUI 心智最接近 |
| 架构 | 单 Activity + Compose Navigation + ViewModel + StateFlow | 对应 SwiftUI 的 `@Observable + Environment` |
| 并发 | Kotlin Coroutines + Flow | 替代委托回调 + `DispatchWorkItem` 定时器 |
| minSdk / targetSdk | **29 / 35**（compileSdk 36；targetSdk 36 亦可） | 主楼设备为 Android 10（API 29） |
| 构建 | Gradle Kotlin DSL + Version Catalog | `libs.versions.toml` 统一版本 |
| BLE | **原生 `BluetoothLeScanner` / `BluetoothGatt`**（自写适配器） | 不引第三方 BLE 库：握手时序特殊，且国内 ROM 适配知识全在原生 API |
| 加密 | **BouncyCastle lightweight API**（`bcprov-jdk18on`） | 只用 `org.bouncycastle.crypto.*`，不注册 JCE Provider，避免与平台内置 BC 冲突 |
| 网络 | OkHttp + kotlinx.serialization（`JsonElement`） | 接口返回结构动态，递归解析 |
| 扫码 | CameraX + ZXing core（GMS-free） | 国内机型无 GMS，不用 ML Kit 非捆绑版 |
| 二维码生成 | ZXing core `QRCodeWriter` | 与扫码共用依赖 |
| 生物识别 | androidx.biometric | `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` |
| 安全存储 | Android Keystore（AES-GCM）+ SharedPreferences | 门禁 JSON 加密落盘，对应 Keychain 语义 |
| 偏好存储 | DataStore Preferences | 对应 UserDefaults |
| 触感 | `Vibrator` / `HapticFeedbackConstants` | 对应 UINotificationFeedback |
| DI | 手写 `AppContainer`（Application 级） | 规模小，不引 Hilt |
| 测试 | JUnit4 + kotlin.test（core 纯 JVM 模块桌面跑） | 无 Robolectric 依赖 |
| CI | GitHub Actions（ubuntu-latest） | `./gradlew assembleDebug` 出 APK artifact |

---

## 2. 工程结构

```
BaiyunKeys-Android/
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── docs/
│   └── migration-plan.md              # 本文档
├── core/                              # 纯 Kotlin/JVM 模块，零 Android 依赖
│   ├── src/main/kotlin/com/baiyunkeys/core/
│   │   ├── hex/Hex.kt
│   │   ├── protocol/BleProtocol.kt
│   │   ├── crypto/DesBlock.kt, Sm2Gm.kt, RsaChunk.kt
│   │   ├── model/DoorConfig.kt, DoorState.kt, UnlockRecord.kt
│   │   ├── text/DoorTextParser.kt
│   │   ├── net/BaiyunApi.kt, Activation.kt, DeviceProfile.kt
│   │   └── unlock/BleAdapter.kt, UnlockOrchestrator.kt, UnlockPhase.kt, UnlockLog.kt
│   └── src/test/                      # golden vectors + 假适配器测试
├── app/                               # Android 应用
│   ├── src/main/kotlin/com/xavior/baiyunkeys/
│   │   ├── MainActivity.kt            # URL scheme / 生命周期 / 生物锁
│   │   ├── App.kt                     # Application + AppContainer
│   │   ├── ui/theme/Theme.kt          # 色板 1:1 移植
│   │   ├── ui/components/             # Card/Toast/Badge/FilledButton/StepperRow/FlatToggle…
│   │   ├── ui/screens/home/           # HomeScreen + HomeViewModel
│   │   ├── ui/screens/config/         # ConfigScreen + ConfigViewModel
│   │   ├── ui/screens/editor/         # DoorEditorScreen
│   │   ├── ui/screens/import/         # ImportSheet / ExportSheet
│   │   ├── ui/screens/scan/           # QrScannerScreen（CameraX）、BluetoothScanSheet
│   │   ├── ble/BleAdapterImpl.kt      # BluetoothLeScanner / BluetoothGatt 实现
│   │   ├── storage/PrefsStore.kt, DoorStore.kt, HistoryStore.kt
│   │   ├── security/BiometricGate.kt
│   │   └── qr/QrCodec.kt
│   └── src/main/AndroidManifest.xml
└── tools/vectors/                     # golden vectors 生成器（Swift）+ 产物 JSON
```

**分层铁律**：`core` 不 import 任何 `android.*`；BLE 以接口注入；所有平台能力（存储 / 生物识别 / 扫码）在 `app` 层实现。`./gradlew :core:test` 在桌面 JVM 上跑完协议与状态机测试。

---

## 3. Swift → Kotlin 映射表

| Swift 文件 | Kotlin 目标 | 难度 | 移植要点 |
|---|---|---|---|
| `Hex.swift` | `core/hex/Hex.kt` | ★ | `hexSlice` 语义、`normalize` 奇数补 `0`、`formatMac`、`reversedPairs`、`ascii` 逐函数直译 |
| `DES.swift` | `core/crypto/DesBlock.kt` | ★ | BouncyCastle `DESEngine`；密钥取前 8 字节；单块 ECB/NoPadding |
| `SM2.swift`（583 行） | `core/crypto/Sm2Gm.kt` | ★ | `SM2Engine(Mode.C1C3C2)` 替换手写大数；保留「带/不带 04 前缀各试一次」 |
| `RSA.swift` | `core/crypto/RsaChunk.kt` | ★ | `PKCS1Encoding(RSAEngine())`；30 字节分块；每块后追加 `RSA_STRTOK@@@`；PEM 用 BC `PEMParser` 解析 |
| `BLEProtocol.swift` | `core/protocol/BleProtocol.kt` | ★★ | 帧偏移、校验和、BCD 时间逐字节等价（见 §4.1） |
| `DoorConfig.swift` | `core/model/DoorConfig.kt` | ★ | `data class`；`maskedMac/maskedKey` 原样 |
| `DoorTextParser`（在 ConfigView 内） | `core/text/DoorTextParser.kt` | ★ | 行格式逐字兼容 |
| `BaiyunAPI.swift` | `core/net/BaiyunApi.kt` | ★★ | 三通道流程；递归收集 `data_list`；`deviceInfo` 字段（见 §4.8） |
| `Activation.swift` | `core/net/Activation.kt` | ★ | 直译；`Sm2DecryptHook` 删除，直接构造注入 |
| `UpdateChecker.swift` | — | — | **不移植** |
| `AppStore.swift` | `app/storage/*` + ViewModel | ★★ | 偏好 → DataStore；门禁 → Keystore 加密；历史 50 条；`pendingUnlockDoorID` |
| `UnlockService.swift`（756 行） | `core/unlock/UnlockOrchestrator.kt` | ★★★ | **重写**：委托回调 → 协程 + Flow；Phase / 超时 / 重试 / 静默 / 候选池语义原样（见 §4.4） |
| `BluetoothScanner.swift` | `app/ble/` 扫描模式 | ★ | 同一适配器的「只看」模式，RSSI 动态刷新 |
| `Theme.swift` | `ui/theme/Theme.kt` | ★ | 色板 1:1（见 §4.6） |
| `Components.swift` | `ui/components/*` | ★★ | Card / Toast / Badge / 按钮 / 步进 / 开关逐组件复刻 |
| `RootView.swift` | `MainActivity` + `RootScaffold` | ★★★ | Tab、生物锁、长辈模式、隐藏入口、pending 处理（见 §4.6） |
| `HomeView.swift` | `ui/screens/home/` | ★★★ | 大按钮、参数卡、日志卡、历史卡、顶栏动作（去掉检查更新） |
| `ConfigView.swift` | `ui/screens/config/` | ★★★ | 门禁列表、自动获取、导入导出、偏好、关于 |
| `DoorEditorView.swift` | `ui/screens/editor/` | ★★ | 表单校验、MAC / Key 规范化 |
| `UnlockButton.swift` | `ui/components/UnlockButton.kt` | ★★ | 涟漪动画参数（见 §4.6） |
| `QRScannerView.swift`（VisionKit） | `ui/screens/scan/QrScannerScreen.kt` | ★★ | CameraX `PreviewView` + `ImageAnalysis` + ZXing 解码 |
| `BluetoothScanSheet.swift` | `ui/screens/scan/BluetoothScanSheet.kt` | ★ | RSSI 排序、4 格信号图标 |
| `UnlockIntent.swift` / `UnlockInBackgroundIntent.swift` | 快捷方式 | ★★ | Android 无 App Intents：v1 用 `ShortcutManager`（打开 App 开锁）；后台无头开锁 P5 用前台服务 |

---

## 4. 关键设计

### 4.1 协议层：逐字节等价

```
握手帧：  A5 14 05 | header[4] | 00 01 07 | DES(8B) | 00 5A
          header = MAC 后 4 字节
          DES 明文块 = [sum&0xFF, sum>>8, random0..3, 00, 00]
          sum = Σrandom + ΣproductKeyBytes
          sealed: frame[len-2] = ~(Σ所有字节) & 0xFF

负载帧：  A5 16 01 | ids[4] | 00 00 | sumBody | xorBody | type | DES(8B) | 00 5A
          ids = 蓝牙名第 4~11 位（4 个 2 字符切片）
          sumBody = Σbody & 0xFF；xorBody = body[0] ^ body[1] ^ …
          type: 0x09 通讯密钥 / 0x06 时间同步
```

**必须保留的细节**（golden vectors 断言点）：

1. `hexSlice(start, len)` 按**字符**切片，越界返回空串；
2. `normalize` 奇数长度**前补 `0`**；
3. DES 密钥只取前 16 个 hex 字符（8 字节）；
4. `deviceIdParts` 取清洗后字符串的 `[3,5,7,9]` 位置各 2 字符；
5. `header(fromMac)` = 12 hex → 6 字节 → **丢弃前 2 字节**；
6. `macFromBluetoothName` = `"000" + nibble + tail(8)` 再 `formatMac`；
7. `openResult`：帧长 ≥ 36 hex 字符，body = `slice(20,16)`，解密后 `slice(4,2)` 为 `00/02` 即成功；
8. `commKey`：合并后 `slice(24,32)` 解密，取 `slice(16,16)`；
9. `timeHex`：年 BCD（`(year-2000)` 十位/个位各 4 bit），月日时分秒十进制 `%02d`，**周日=0**（Calendar 周日=1 需减 1）；
10. 通知解析：`cmd=04` 握手回执（短帧取 `slice(14,4)` 拼 `000B` 提示）、`cmd=08` 协商拒绝（`E36F` 提示）、`type=87` 最终回执、`89/86/82` 分片缓存按 `5A` 尾合并。

### 4.2 加密层：BouncyCastle 用法

```kotlin
// DES：单块 ECB/NoPadding
val engine = DESEngine()
engine.init(true, KeyParameter(keyBytes))          // keyBytes = productKey 前 8 字节
val out = ByteArray(8); engine.processBlock(block, 0, out, 0)

// SM2：C1C3C2，兼容带/不带 04 前缀
val engine = SM2Engine(SM2Engine.Mode.C1C3C2)
engine.init(false, ECPrivateKeyParameters(privD, sm2Domain))
val plain = engine.processBlock(cipher, 0, cipher.size)

// RSA：PKCS#1 v1.5，30 字节分块
val cipher = PKCS1Encoding(RSAEngine()).apply { init(true, pubKey) }
```

**注意**：RSA 加密是**非确定性**的（PKCS#1 v1.5 随机填充），golden vectors 对 RSA 只能做「结构校验」（分块数、每块长度、后缀位置），不能做字节比对；DES / SM2 / 帧构造是确定性的，可字节比对。

### 4.3 BLE 适配器

```kotlin
// core/unlock/BleAdapter.kt
interface BleAdapter {
    val state: StateFlow<BleState>                       // PoweredOn/Off/Unauthorized/Unsupported
    fun scan(filter: ScanFilter?, allowDuplicates: Boolean): Flow<ScanHit>
    suspend fun connect(deviceId: String, timeoutMs: Long): BleSession
}

data class ScanHit(
    val deviceId: String,          // Android = MAC 地址
    val name: String?,
    val rssi: Int,
    val manufacturerData: ByteArray?,
)

interface BleSession : AutoCloseable {
    val deviceId: String
    suspend fun findService(): BleService?               // 同一服务内同时有 read + write 特征
    suspend fun subscribe(uuid: UUID, onValue: (ByteArray) -> Unit)
    suspend fun read(uuid: UUID): ByteArray
    suspend fun write(uuid: UUID, data: ByteArray)
}
```

实现要点（`BleAdapterImpl`）：

- **扫描**：前台不按服务过滤（靠名字 / MAC 匹配）；后台 / 服务场景带已知服务 UUID 过滤（与 iOS 后台路径一致）；
- **匹配优先级**（Android 独有优势）：① `BluetoothDevice.address` 与门禁 MAC 精确匹配 → ② 蓝牙名等值 → ③ 厂商数据包含 MAC / 反序 MAC / 名字 ASCII；
- **连接**：`connectGatt(context, false, callback, TRANSPORT_LE)`，8s 超时；
- **服务发现**：`discoverServices()` 后遍历，选「同服务内 read + write 特征」的那个；通知特征全部订阅；
- **写**：优先 `WRITE_TYPE_DEFAULT`（有响应），否则 `WRITE_TYPE_NO_RESPONSE`；
- **133 错误**：`onConnectionStateChange` 收到 status 133 时 `close()` + 延迟 300ms 重连（计入重试次数）；
- **OEM 适配**：扫描期间保持屏幕常亮（`FLAG_KEEP_SCREEN_ON`）。

#### 权限模型（按版本分支）

| 版本 | 扫描权限 | 连接权限 | 备注 |
|---|---|---|---|
| API 29–30（Android 10/11） | `ACCESS_FINE_LOCATION`（运行时） | `BLUETOOTH` / `BLUETOOTH_ADMIN`（安装时） | **系统定位服务必须开启**，否则扫不到设备 |
| API 31+（Android 12+） | `BLUETOOTH_SCAN`（`neverForLocation`） | `BLUETOOTH_CONNECT` | 不再需要定位权限 |

Manifest 关键声明：

```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.USE_BIOMETRIC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.INTERNET" />
```

**Android 10 专项**：扫描前检查 `LocationManager.isLocationEnabled`，关闭时弹窗引导去 `Settings.ACTION_LOCATION_SOURCE_SETTINGS`；权限被拒时给出「去设置」入口。

### 4.4 开锁状态机（协程化）

```kotlin
sealed interface UnlockPhase {
    data object Idle : UnlockPhase
    data object Preparing : UnlockPhase
    data object Scanning : UnlockPhase
    data object Connecting : UnlockPhase
    data object Negotiating : UnlockPhase
    data object Waiting : UnlockPhase
    data object Success : UnlockPhase
    data class Failure(val message: String) : UnlockPhase
    val isBusy: Boolean get() = this is Preparing || this is Scanning ||
        this is Connecting || this is Negotiating || this is Waiting
}

class UnlockOrchestrator(
    private val ble: BleAdapter,
    private val history: UnlockHistory,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val _phase = MutableStateFlow<UnlockPhase>(UnlockPhase.Idle)
    val phase: StateFlow<UnlockPhase> = _phase.asStateFlow()
    private val _logs = MutableStateFlow<List<UnlockLog>>(emptyList())

    suspend fun unlock(door: DoorConfig, opts: UnlockOptions = UnlockOptions()): UnlockResult
}
```

流程（与 Swift 语义一一对应）：

```
preparing → [缓存直连?] → connecting → negotiating(发现服务/订阅/读随机数)
          → waiting(已发握手) → success / failure
```

- **超时**：连接 / 服务发现 8s、读随机数 5s、回执 20s、扫描超时用户可配（3–60s，默认 10s）；
- **重试**：扫描 / 连接 / 超时类失败可重试（1–20 次），协议类失败（握手拒绝、回执码异常）直接终结；
- **四种入口**：`unlock(door)` 单门锁、`unlockAny()` 快速模式（候选池按 RSSI 先到先得）、`unlockIfCached()` 静默直连（无缓存悄悄回 idle，不写历史不震动）、P5 后台服务回调；
- **日志**：200 行环形缓冲，格式 `HH:mm:ss.SSS [LEVEL] text`；
- **取消**：`CancellationException` 贯穿，`teardown()` 在 `finally` 中执行。

### 4.5 存储

| 数据 | 方案 | 对应 Swift |
|---|---|---|
| 门禁列表（DoorState JSON） | **Keystore AES-GCM 加密**后存 SharedPreferences | Keychain（AfterFirstUnlock） |
| 偏好（22 个键） | DataStore Preferences，**键名原样保留** | UserDefaults |
| 开锁历史（50 条） | DataStore（JSON） | UserDefaults |
| `pendingUnlockDoorID` | DataStore | UserDefaults |
| `baiyun.udid` | DataStore | UserDefaults |

Keystore 方案：`KeyGenParameterSpec` 生成 AES-256-GCM 密钥（`setUserAuthenticationRequired(false)`），加密 JSON 后 Base64 存盘；密钥失效（部分 ROM 重装 / 恢复）时提示重新导入，不静默丢数据。

### 4.6 UI 映射

**主题色板（1:1 移植）**：

| Token | Light | Dark |
|---|---|---|
| brand | `#0FB981` | `#2DD4A0` |
| brandSoft | `#E7F8F1` | `#0F2E26` |
| info | `#2F6BFF` | `#5B8CFF` |
| infoSoft | `#EAF0FF` | `#131E3A` |
| danger | `#E5484D` | `#FF6369` |
| dangerSoft | `#FDECEC` | `#33191B` |
| warn | `#C77D14` | `#F5B14C` |
| background | `#F2F4F7` | `#0A0C10` |
| surface | `#FFFFFF` | `#151A21` |
| surfaceAlt | `#F5F7FA` | `#1D232C` |
| separator | `#E7EBF0` | `#252C36` |
| textPrimary | `#0F1419` | `#F3F6F9` |
| textSecondary | `#69747F` | `#99A4B0` |
| textTertiary | `#9AA3AD` | `#6C7783` |

圆角：卡片 26dp、输入框 16dp、页边距 20dp。

**大按钮动画**（`UnlockButton.kt`）：

- 3 个涟漪圆环：直径 208dp，`scale 0.85→1.45`、`alpha 0.9→0`、`easeOut 2.1s` 无限循环、每个延迟 0.7s；
- 中心光晕：236dp、tint 10% 透明度；
- 按钮本体：196dp 圆形，按下 `scale 0.94`（spring）；
- 状态图标：`lock.open.fill` / `dot.radiowaves` / `checkmark` / `exclamationmark`，文案「轻点开锁 / 进行中 / 已开锁 / 重试」。

**Root 结构**（`MainActivity` + `RootScaffold`）：

- 底部 Tab：首页 / 配置（长辈模式时移除配置 Tab）；
- 生物识别锁屏：冷启动一次，`BiometricPrompt`，失败文案映射（取消 / 锁定 / 未录入 / 不支持 / 未设密码）；
- 长辈模式：首页顶栏图标**长按 3 秒**切换，弹窗勾选「禁用门禁选择器 / 隐藏日志 / 隐藏历史」；拦截切配置 Tab；
- 快速模式：长按 3 秒切换，弹窗说明；
- **隐藏入口**：配置 Tab 连点 10 次 → 访问码 `123456789` → 显示「自动获取配置」；显示中连点 5 次隐藏；24h TTL 自动翻回隐藏；
- `pendingUnlockDoorID` 处理：`onResume` 读取 → 选中门禁 → 延迟 300ms 开锁；
- 「打开即开锁」/「切回也开锁」：`Lifecycle.Event.ON_START` + 3s 节流。

**组件清单**（`ui/components/`）：`Card`、`CardHeader`、`Toast`、`Badge`、`FilledButton`、`QuietButton`、`FlatToggle`、`StepperRow`、`PrefMenuRow`、`EmptyStateView`、`UnlockButton`、`SignalBars`。

**与 iOS 版的差异**：

- 首页顶栏去掉「检查更新」按钮（保留蓝牙状态、扫码导入）；
- 配置页「关于」卡片：iOS 版本 → Android 版本（`Build.VERSION.RELEASE`），仓库链接改为 Android 仓库；
- 触感：`Vibrator` 两档（成功 / 失败），比 iOS 三档略简。

### 4.7 平台入口（v1 范围）

| iOS 能力 | Android 替代 | v1 |
|---|---|---|
| `baiyunkeys://unlock?door=` | `intent-filter` + `singleTask` + `onNewIntent` | ✅ |
| 快捷指令「立即开锁」 | `ShortcutManager` 静态快捷方式（打开 App 并自动开锁） | ✅ |
| 快捷指令「后台开锁」 | 前台服务（`connectedDevice` 类型） | ⏳ P5 |
| 操作按钮 / 轻点背面 | 快捷设置磁贴（`TileService`） | ⏳ P5 |
| 桌面小组件 | Glance AppWidget | ⏳ P5 |
| Siri | 无对应 | — |

**URL scheme 实现**：`MainActivity` `launchMode="singleTask"`；`onCreate` / `onNewIntent` 解析 `door` 参数写入 `pendingUnlockDoorID`；`onResume` 接管。

**快捷方式**：`res/xml/shortcuts.xml` 静态声明「立即开锁」「快速模式」，点击启动 `MainActivity` 并带 extra。

### 4.8 网络层与 `deviceInfo.os`

三通道原样移植：

1. **身份证通道**（已失效，保留）：`POST /baiyunuser/account/login/v1`（`www.pinganbaiyun.cn`）；
2. **OpenID 通道**：`check_state` → `get_rsa_public_key` → `get_guard_list_by_phone`（`xcx.pinganbaiyun.cn`）；
3. **OpenID + SM2 通道**：`check_state` → `go_home_service_login` → `get_guard_list_by_phone` → 本地 SM2 解密。

**`deviceInfo.os` 是什么**：登录接口请求体里的设备描述对象，官方 App 用它向服务端声明客户端环境（`osVersion` / `brand` / `model` / `os` / `udid` / `appVersion` / `imsi` / `wifiMac`）。`os` 是操作系统枚举，推测 `0 = iOS`、`1 = Android`；服务端可能用于风控、统计或功能开关。我们无法确认枚举映射，但 iOS 客户端发 `0` 是已验证可用的。

**决策**：默认发 `0`（与 iOS 客户端完全一致，保证服务端行为一致），封装为常量，预留切换：

```kotlin
data class DeviceProfile(
    val osVersion: String,
    val brand: String,
    val model: String,
    val udid: String,
    val appVersion: String = "1.3.6",
    val imsi: String = "46015",
    val wifiMac: String = "02:00:00:00:00:00",
    val os: Int = OS_IOS,          // 预留：0=iOS（与 iOS 客户端一致），1=Android（待实测）
) {
    companion object {
        const val OS_IOS = 0
        const val OS_ANDROID = 1
    }
}
```

调试构建可加开关发 `1` 做对照实验，观察服务端返回是否变化；若未来服务端对 Android 客户端有差异化逻辑，再按实测调整。

---

## 5. Golden Vectors 策略（双实现项目的生命线）

**生成器**：在 iOS 仓库加一个 SPM 命令行工具（`tools/vectors`），复用 `BLEProtocol.swift`、`DES.swift`、`SM2.swift`、`Hex.swift`、`RSA.swift`、`DoorTextParser`，输出 `vectors.json`。在 GitHub Actions 的 **macOS runner** 上运行（公开仓库免费），产物提交到本仓库 `tools/vectors/`。

**向量清单**：

| 类别 | 内容 | 断言方式 |
|---|---|---|
| Hex | `normalize/formatMac/isValidMac/isValidKey/reversedPairs/ascii/hexSlice` | 表驱动，逐条相等 |
| DES | 多组 key/block 加解密 | 字节相等 |
| 握手帧 | 固定 productKey/mac/random → 完整帧 hex | 字节相等 |
| 通讯密钥帧 | 固定 random/name/key → 完整帧 hex | 字节相等 |
| 时间同步帧 | 固定 timeBytes/sessionKey/name → 完整帧 hex | 字节相等 |
| `timeHex` | 固定时间 + 固定时区 → hex | 相等（生成器固定 TZ） |
| 回执解析 | 固定帧 → code/message | 相等 |
| `commKey` / `decrypt` | 固定 body → 结果 | 相等 |
| SM2 | 固定私钥 + 密文（带/不带 04）→ 明文 hex | 相等 |
| RSA | PEM + 明文 → 分块结构 | **结构校验**（非确定性） |
| DoorTextParser | 样例文本 → 门禁列表 | 相等 |

**纪律**：iOS 侧协议一改 → 重新生成 vectors → 本仓库测试变红 → 同步改 Kotlin。

---

## 6. 测试策略

1. **core 单测**（桌面 JVM，`./gradlew :core:test`）：Hex、DoorTextParser、BleProtocol（golden）、DES/SM2 往返、RSA 结构；
2. **假适配器状态机测试**：脚本化通知序列（正常开锁 / 握手拒绝 `000B` / 协商拒绝 `E36F` / 分片乱序合并 / 超时重试 / 静默模式无缓存），断言 Phase 转移与历史写入；
3. **真机验收清单**：单门锁、快速模式、静默回前台开锁、长辈模式、URL scheme 冷/热启、二维码导入导出、生物锁、自动获取三通道；
4. **日志对拍**：与 iOS 版同场景日志逐行比对（帧 hex 必须一致）。

---

## 7. 路线图

| 阶段 | 内容 | 验收标准 |
|---|---|---|
| **P0 脚手架** | Gradle 工程、CI 出 debug APK、core 模块空跑 | `./gradlew assembleDebug` 绿 |
| **P1 Core 移植** | Hex/DES/SM2/RSA/BLEProtocol/DoorTextParser/BaiyunApi/Activation + golden vectors | 全部向量对拍通过 |
| **P2 BLE + 状态机** | `BleAdapterImpl`、`UnlockOrchestrator`、假适配器测试 | 真机握手成功，日志与 iOS 一致 |
| **P3 UI 复刻** | 主题/组件/首页/配置/编辑/导入导出/扫码/蓝牙面板 | 功能对齐，视觉还原 |
| **P4 平台胶水** | 权限、生物识别、Keystore 存储、URL scheme、快捷方式、生命周期开锁 | 真机全流程通过 |
| **P5 增强（不在 v1）** | 前台服务后台开锁、磁贴、Glance 小组件 | 按需 |

---

## 8. 风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| **Android 10 定位开关未开** | BLE 扫描扫不到任何设备 | 扫描前检测 `isLocationEnabled`，弹窗引导开启；权限被拒给「去设置」入口 |
| 国内 ROM 后台 / 扫描限制 | 后台开锁失败、扫描不到 | 前台服务 + 屏幕常亮 + 引导加白名单；真机覆盖 2–3 家 ROM |
| GATT 133 错误 | 连接失败 | 关闭重连 + 延迟重试，计入重试次数 |
| BouncyCastle 与平台 BC 冲突 | 运行时异常 | 只用 lightweight API，不注册 Provider |
| Keystore 密钥失效 | 门禁数据不可读 | 检测解密失败 → 提示重新导入，不静默丢数据 |
| 相机权限 / ROM 差异 | 扫码不可用 | 权限拒绝时降级为文本导入 |
| 协议双实现漂移 | 开锁失败 | golden vectors + 变更纪律 |
| 主楼设备 Android 10 性能 | 动画卡顿 | 涟漪动画用 `infiniteTransition`，避免每帧分配 |

---

## 9. 工作量估算（单人）

| 阶段 | 估算 |
|---|---|
| P0 | 1–2 天 |
| P1 | 3–5 天 |
| P2 | 3–4 天 |
| P3 | 5–8 天 |
| P4 | 3–5 天 |
| **v1 合计** | **3–4 周**（真机联调是瓶颈） |
| P5（可选） | 2–4 天 |

---

## 10. 决策记录

| # | 决策 | 结论 | 备注 |
|---|---|---|---|
| 1 | minSdk | **29（Android 10）** | 主楼门禁设备为 Android 10 |
| 2 | 包名 | **`com.xavior.baiyunkeys`** | |
| 3 | v1 范围 | 前台开锁 + 快捷方式；不含磁贴 / 小组件 / 后台无头开锁 | P5 再补 |
| 4 | 自动获取配置 | 保留三通道（身份证 / OpenID / OpenID+SM2） | |
| 5 | 更新检查 | **不移植** | 首页顶栏去掉检查更新按钮 |
| 6 | `deviceInfo.os` | 默认 `0`（与 iOS 一致），常量预留可切 `1` | 见 §4.8 |
| 7 | 技术栈 | Kotlin + Compose + 原生 BLE + BouncyCastle | 不引第三方 BLE 库 |
| 8 | 仓库 | 本仓库独立，iOS 仓库不动 | 协议以 iOS 为唯一事实源 |
