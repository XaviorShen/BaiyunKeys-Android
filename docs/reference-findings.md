# 源码级差异记录（iOS 实现 vs 迁移方案）

> 记录日期：2026-11-10
> 方法：逐行读 `BaiyunKeys-iOS` 的 `BaiyunKeys/Core/*.swift` 与同仓库 `web/js/core/*.js`，与 `docs/migration-plan.md` 对账。
> 结论按「影响移植决策」排序。

---

## 1. ⚠️ `DES` 单块限制让通讯密钥协商在 iOS 上恒失败（多块 vs 单块）

- `BaiyunKeys/Core/DES.swift:44` —— `guard clean.count == 16`，**只接受一个 8 字节块**。
- `BaiyunKeys/Core/UnlockService.swift:359` —— `BLEProtocol.commKey(fromBody: merged.hexSlice(24, 32), ...)`，传进去的是 **32 个 hex 字符（两块）**。
- `BaiyunKeys/Core/BLEProtocol.swift:123-127` —— `commKey` 又要 `decrypted.count >= 32`（即期望解出两块）。

三者互相矛盾：`decryptBlock` 会抛 `badBlock`，被 `try?` 吞掉后 `commKey` 返回空串，于是 `timeSyncCommand` 的 `sessionKey` 为空、`DES.encryptBlock` 再抛一次。**iOS 侧 `0x09` 通讯密钥 + `0x06` 时间同步这条支路实际是死代码。**

- web 版 `web/js/core/des.js` 的 `crypt()` 是**多块循环**，能正常解两块 —— 方案 §4.1 第 8 条（`slice(24,32)` 解密、取 `slice(16,16)`）也是按多块写的。
- **Android 决策**：`DesBlock` 按**多块**实现（8 字节分组循环）。单块输入行为与 Swift 完全一致，多块输入按 web / 方案语义走。
  - 影响：Android 若拿到合法会话密钥，会真的走时间同步；iOS 此前直接跳过。
  - 验证：`core/src/test/.../DesGoldenTest.kt` 的 `multiBlockEqualsConcatenatedSingleBlocks`；DES 本身用标准向量 `key=133457799BBCDFF1 / pt=0123456789ABCDEF → ct=85E813540F0AB405` 校验过。
- **待办**：真机抓包确认门锁是否真的下发两块（若只回一块，`commKey` 的 `>= 32` 判断要放宽）。这条必须在 P2 真机联调时结案。

## 2. golden vectors 不需要 macOS runner

方案 §5 原计划在 iOS 仓库加 SPM 工具、用 macOS runner 跑 Swift。实际上 iOS 仓库的 `web/js/core/` 已经是同一套协议的 **JS 实现**，且 `des.js` 是纯 JS DES（不依赖 WebCrypto），可在 Node 直接跑。本机 Node v24 已验证。

- 生成器：`tools/vectors/gen.mjs`（读 `../BaiyunKeys-iOS/web/js/core`，可用 `REF_DIR` 覆盖）。
- 产物：`tools/vectors/vectors.json`，由 `core` 的测试资源目录直接消费。
- 唯一例外：`DoorTextParser` 没有 JS 版（只存在于 `ConfigView.swift:502-547`），其向量按 Swift 语义手推。

## 3. 偏好键是 26 个，不是 22 个

`AppStore.Keys`（`AppStore.swift:8-35`）实际 26 个键，其中 3 个是数据键（`doorState` / `unlockHistory` / `pendingUnlockDoor`），偏好部分是 23 个。另有独立键 `baiyun.udid`（`BaiyunAPI.swift:373`），不在 `AppStore.Keys` 里。方案 §4.5 的「22 个键」应以此为准。

## 4. `appVersion` 与 App 版本号无关，是硬编码的 `1.3.6`

- `BaiyunAPI.swift:79` 与 `:86` 都写死 `"appVersion": "1.3.6"`，而 iOS 工程 `MARKETING_VERSION = 2.0.0`。
- web 版 `web/js/core/baiyun-api.js:8` 同样写死 `APP_VERSION = '1.3.6'`，请求体 `os: 0`。
- **Android 决策**：`DeviceProfile.appVersion` 默认 `"1.3.6"`，**不要**用 `BuildConfig.VERSION_NAME` —— 这大概是服务端认的客户端标识。

## 5. 包名差异（不冲突，但别混）

| 平台 | 标识 |
|---|---|
| iOS | `com.xavior.baiyunkey`（**无 s**） |
| Android | `com.xavior.baiyunkeys`（**有 s**，本仓库决策） |

## 6. Android 的结构性优势

- `BluetoothDevice.address` 可直读 → 匹配优先级 ①MAC 精确 > ②蓝牙名 > ③厂商数据包含 MAC / 反序 MAC / 名字 ASCII。
- 因此 `DoorConfig.bluetoothName` 在 Android 上**不是必填**（iOS 因 CoreBluetooth 不暴露 MAC 而必填），`DoorConfig.isUsable` 已相应放宽：MAC 合法 + Key 合法 + （蓝牙名非空 **或** MAC 可扫描匹配）。
- `cachedPeripheralID` 语义在 Android 应变成「缓存 MAC 直连」。

## 7. 互通细节：逐字保留

- 导出文本标题用 `displayName`（空名 → `未命名门禁`）。
- 脱敏是**全角**星号「＊＊」（MAC）/「＊」（Key），不是 `*`。
- `DoorState.version = 2`。
- 文本解析分隔符优先级：`：` → `:` → `=` → 空格；空行分条；MAC 与 Key 都非空才收（**不校验格式**）。
- 时间 `timeHex`：年 BCD、其余十进制 `%02d`、**周日 = 0**（`java.time` 的 `dayOfWeek` 需 `% 7`）。

## 8. 主楼真机与模拟器

本机只有 API 37 的 AVD（`Medium_Phone_API_37.0`），BLE 相关验收必须在 Android 10 真机上做；模拟器只能验 UI 与编译。

## 9. 工具链版本被 androidx 卡住（实测记录）

第一次 `:app:assembleDebug` 挂在 `checkDebugAarMetadata`：Compose BOM `2026.09.00`（ui 1.12.1）、`core-ktx 1.19.1`、`lifecycle 2.11.0` 全部要求 **AGP ≥ 9.1.0 且 compileSdk ≥ 37**，而 AGP 8.13.2 的最高推荐 compileSdk 就是 36。

两条路：

| 方案 | 代价 |
|---|---|
| A. 升 AGP 9.x + compileSdk 37 | AGP 9 引入 **built-in Kotlin**，`kotlin-android` 插件的接法要改，属于独立变更 |
| **B. 锁 AGP 8.13.2 + 挑一批支持 compileSdk 36 的依赖（本轮采用）** | 少用最新的 Compose，功能无影响 |

当前锁定（见 `gradle/libs.versions.toml`）：Compose BOM `2026.06.01`（ui 1.11.4 / material3 1.4.0）、core-ktx `1.17.0`、activity-compose `1.11.0`、lifecycle `2.9.4`。
升级 AGP 9 时把这四项一起抬到最新即可，代码不用动。

## 10. P2 状态机移植对照（Swift → Kotlin）

| iOS `UnlockService.swift` | Kotlin | 说明 |
|---|---|---|
| `Phase` 枚举 | `core/unlock/UnlockPhase.kt` | 8 个阶段原样，`isBusy` 同义 |
| `LogLine` + 200 行环形缓冲 | `UnlockLog` + `UnlockOrchestrator.log` | 格式 `HH:mm:ss.SSS [LEVEL] text` 逐字保留，便于与 iOS 日志对拍 |
| `processNotification`（97 行） | `NotificationProcessor.process` | 抽成**纯函数**并返回 `ProtocolEvent` 列表，脱离蓝牙可测 |
| `matches(name:advertisement:door:)` | `DeviceMatcher.matches` | 多一档 MAC 精确匹配并排在最前（Android 独有） |
| `unlock` / `unlockAny` / `unlockIfCached` | 同名三个 `suspend fun` + `unlockAsync` | 四入口语义一致 |
| `DispatchWorkItem` 定时器 | `withTimeoutOrNull` | 超时值照搬：8s 连接/服务发现、5s 随机数、20s 回执、300ms 重试间隔 |
| `retryOrFail` / `fail` / `silentAbort` / `finish` | 同名 private 函数 | 可见性语义（静默模式下失败不写历史 / 不震动 / 回 idle）原样保留 |

两个容易漏掉的语义，已在实现里显式保留：

1. **`04` 长帧即开锁成功**：门锁在**握手阶段**就执行开锁，`cmd=04` 且长度 > 24 hex 字符时 iOS 直接 `finish(success: true, "握手成功，门锁已执行开锁")`，不等 `87` 最终回执。`87` 是另一条并行路径（有的门锁回，有的不回）。
2. **缓存必须在 teardown 之前取**：iOS 注释写得很明白 —— `finish` 里先 `store.cachePeripheral(peripheral.identifier)` 再 `teardown()`，否则 `peripheral` 已被清空。Kotlin 侧对应 `currentDeviceId`，在 `finish()` 开头取。

另外：`commKey` 分片合并（`type=89` → 尾 `5A`）依赖第 1 条里的**多块 DES**，iOS 单块实现走到这里必然解不出来 —— 这是同一条 bug 的另一面。
