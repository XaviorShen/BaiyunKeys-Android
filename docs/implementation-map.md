# 实现地图（方案需求 → 代码 / 测试）

> 逐条覆盖 `docs/migration-plan.md` 的 v1 范围（§0）与路线图（§7）。每条给出落地文件与验证方式。
> 状态说明：**✓ 已实现**；「待真机」= 代码与单测就绪，但需要 Android 10 真机确认实际效果（本机无设备）。

## §0 v1 范围

### 前台开锁

| 需求 | 实现 | 验证 |
|---|---|---|
| 单门锁开锁 | `UnlockOrchestrator.unlock` ✓ | `UnlockOrchestratorTest.happyPath…` |
| 快速模式（扫到任一门禁就开） | `unlockAny` + `DeviceMatcher` ✓ | `quickModePicksMatchingCandidate` |
| 静默回前台开锁 | `unlockIfCached` ✓ | `silentModeUsesCachedDeviceDirectly` / `silentModeWithoutCacheDoesNothing` |
| 握手 / 通讯密钥 / 时间同步帧 | `BleProtocol` + `DesBlock` ✓ | `BleProtocolGoldenTest`（golden vectors） |
| 回执解析 | `NotificationProcessor` ✓ | `NotificationProcessorTest`（04/08/87） |
| 超时 / 重试 / 分片 | `UnlockOrchestrator` + `NotificationProcessor` ✓ | `noReceiptRetriesUpToMaxAttempts` 等 15 条 |

### 门禁管理

| 需求 | 实现 | 验证 |
|---|---|---|
| 增 / 删 / 改 | `DoorStore` + `DoorEditorScreen` ✓ | `DoorConfigTest` / `DoorMergeTest` |
| 掩码显示 | `DoorConfig.maskedMac/maskedKey` ✓ | `maskedMacUsesFullWidthAsterisks` |
| 文本导入 / 导出 | `DoorTextParser` / `DoorTextExporter` ✓ | `parseMatchesReference` / `roundTripThroughParser` |
| 二维码导入 / 导出 | `QrCodec`（生成）+ `QrScannerScreen`（CameraX+ZXing）✓ | 待真机 |
| 复制参数 / 复制直达链接 | `ConfigScreen` 行内按钮 ✓ | 待真机 |

### 自动获取配置

| 需求 | 实现 | 验证 |
|---|---|---|
| 通道一：身份证 | `BaiyunApi.login` + `entranceGuards` + `Activation.fetchByIdCard` ✓ | `loginSendsDeviceProfileAndValidatesCode` |
| 通道二：OpenID | `BaiyunApi.guardsByOpenId` + `Activation.fetchByOpenId` ✓ | `guardsByOpenIdWalksThreeStepsAndMapsFields` |
| 通道三：OpenID + SM2 | `checkState` + `goHomeServiceLogin` + `encryptedGuards` + `Sm2Gm` ✓ | `activationDecryptsCipherAndPassesThroughPlainKey` |
| 隐藏入口 + 访问码 + 24h TTL | `HiddenEntryGate` + `AppSettings.isAutoFetchVisible` ✓ | `HiddenEntryGateTest`（7 条） |

### 偏好设置（11 项）

掩码 ✓ / 触感 ✓ / 打开即开锁 ✓ / 切回也开锁 ✓ / 失败自动重试 ✓ / 重试次数 ✓ /
扫描超时 ✓ / 保存开锁记录 ✓ / 生物识别 ✓ / 外观 ✓ / 长辈模式（4 子项）✓
→ `AppSettings` + `SettingsStore`（DataStore，键名与 iOS `AppStore.Keys` 一致）+ `SettingsScreen`。
验证：`AppSettingsTest`（默认值 / 收敛 / 换算）、`DoorCodecTest`、`HiddenEntryGateTest`。

### 平台入口

| 需求 | 实现 | 验证 |
|---|---|---|
| `baiyunkeys://unlock[?door=&quick=]` | `UnlockLink` + `MainActivity.onCreate/onNewIntent` ✓ | `UnlockLinkTest`（6 条） |
| 快捷方式（立即开锁 / 快速模式） | `res/xml/shortcuts.xml` ✓ | 待真机 |
| 生物识别锁屏（冷启动一次） | `BiometricGate` + `BiometricLockScreen` ✓ | 待真机 |
| 蓝牙扫描面板 | `DiagnosticsScreen` ✓ | 待真机 |
| 运行日志（200 行） | `UnlockLog.MAX_LINES` + `UnlockOrchestrator.log` ✓ | `logsAreCappedAndFormatted` |
| 开锁记录（50 条） | `UnlockHistoryCodec` + `HistoryStore` ✓ | `UnlockHistoryCodecTest`（6 条） |

### 数据 / 存储

| 需求 | 实现 | 验证 |
|---|---|---|
| 门禁 JSON 加密落盘（对应 Keychain） | `KeystoreBox`（AES-256-GCM）+ `DoorStore` ✓ | 待真机（Keystore 无法在 JVM 单测） |
| 偏好 DataStore | `SettingsStore` ✓ | 同上 |
| 密钥失效提示重新导入 | `DoorStore.loadFailed` → 首页红卡 ✓ | — |
| `baiyunkeys://` 之外的兼容格式 | `DoorTextExporter` 与 iOS 逐字一致 ✓ | `singleDoorFormatMatchesIos` |

## §7 路线图

| 阶段 | 状态 | 证据 |
|---|---|---|
| P0 脚手架 | ✓ | `:app:assembleDebug` 出 APK、`:core:test` 绿、CI workflow |
| P1 Core 移植 | ✓ | 120 条单测（含 golden vectors、RSA/SM2 往返、网络层 12 条） |
| P2 BLE + 状态机 | ✓（真机待验） | `UnlockOrchestratorTest` 15 场景 + `NotificationProcessorTest` 8 条 |
| P3 UI 复刻 | ✓ | 首页 / 配置 / 编辑 / 搬运 / 扫码 / 偏好 / 诊断 七屏 |
| P4 平台胶水 | ✓（真机待验） | 权限、Keystore、深链、快捷方式、生物识别、生命周期开锁 |
| P5 增强 | ✓（真机待验） | 前台服务、磁贴、Glance 小组件 |

## 与方案的已知偏差（都在 `docs/reference-findings.md` 有记录）

1. **DES 多块**：iOS 的 `DES.crypt` 只收单块，与 `commKey` 的 `slice(24,32)` 冲突；Android 按多块实现（与 web 版、方案 §4.1 一致）。
2. **工具链**：AGP 8.13.2 + compileSdk 36（AGP 9 的 built-in Kotlin 留作独立变更），依赖整体降一档。
3. **偏好键数**：iOS 实际 26 个键（23 项偏好 + 3 个数据键），方案写的 22 需修正。
4. **蓝牙名可选**：Android 能直读 MAC，`DoorConfig.isUsable` 不再要求蓝牙名。
5. **快速模式入口**：除快捷方式外，首页在有多套门禁时也提供一个「快速模式」按钮。
6. **长辈模式切换**：除首页标题长按 3 秒外，偏好里也给了显式开关（长辈模式会藏掉配置 Tab，没有显式开关容易把人锁在外面）。
7. **磁贴不做无头开锁**：Android 12+ 限制后台起前台服务，磁贴改为拉起 App 走深链；无头开锁由小组件的 `PendingIntent` 承担。
