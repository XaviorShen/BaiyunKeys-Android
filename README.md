# 白云钥匙 · Android 原生版

蓝牙门禁离线开锁，Kotlin + Jetpack Compose，无 GMS 依赖，单 APK。

本项目是 [**BaiyunKeys-iOS**](https://github.com/YiwanGo/BaiyunKeys-iOS) 的 **Android 原生移植**。
方案设计参考自该项目：通信协议、DES 加解密、门禁数据结构、文本 / 二维码互通格式、色板与交互，
全部以 iOS 实现为唯一事实源，两端（以及配套网页版）数据互通，导入导出可以直接搬。

配套网页版：<https://baiyunkeys-web.pages.dev/>，安卓 / iOS / 电脑浏览器都能用，一套格式互通。

> Android 用户请使用 Edge 或 Chrome 打开网页版，系统自带浏览器可能存在 Web Bluetooth 兼容问题。

## 安装

**系统要求：Android 9（API 28）及以上。** 目标机型是主楼门禁旁的旧手机（Android 10），
为兼容更老的机器把地板上探到 9。

产物是 **debug APK**（未签名发布版）：

- 从 GitHub Actions 的最新构建中下载 `baiyunkeys-debug-apk`（见「自动构建」）；
- 或按下面的「跑起来」自己构建。

## 跑起来

JDK 21 + Android SDK 36。命令行：

```bash
./gradlew :app:assembleDebug      # 产物 app/build/outputs/apk/debug/
./gradlew :core:test              # 协议层单测，纯 JVM，不需要模拟器
./gradlew :app:lintDebug          # lint
```

Android Studio 直接打开根目录，Gradle 同步后 Run 即可。
`local.properties` 需要自己写 `sdk.dir=`（该文件不入库）。

真机要求：蓝牙（必须）、相机（扫码导入）、指纹 / 面容或锁屏密码（生物识别锁屏，可选）。

## 用法

配置 → 新增门禁，填 MAC / Key / 蓝牙名 / 一个自己认得的门禁名。回首页点中间那个大圆按钮。

**蓝牙名在 Android 是选填** —— Android 能直接读到外设 MAC，扫描时按 MAC 匹配即可（iOS 的 CoreBluetooth
不暴露 MAC，所以那边必填）。填了能提高命中率。

### 门禁参数怎么拿

用抓包工具（Charles / Fiddler / Whistle / Reqable 之类）挂上代理，打开微信里的**「平安白云」小程序**，
进到你有权限的门禁列表页面，抓 `get_guard_list_by_phone` 这个接口的响应，里面每个门禁一条记录都能拿到：

- **蓝牙名**：广播名，形如 `BY0AABBCCDD`（`BY` + 8 位 hex + 1 位后缀），选填
- **Key**（`productKey`）：16 ~ 32 位 hex，等同实体钥匙，别外传。抓到更长（例如 112 位 hex），
  属于加密下发的密文，需配套 SM2 私钥才能解回明文
- **MAC**：能填就填，握手只用后 4 字节

也可以走「自动获取配置」：配置页标题**连点 10 次** → 输入访问码 → 出现三通道表单
（身份证 / 微信 OpenID / OpenID + SM2），从官方接口直接把已授权的门禁拉下来。
该入口默认隐藏，显示 24 小时后自动收回；显示期间连点 5 次可手动隐藏。

## Android 独有能力

相对 iOS 版，这一版把 Android 的平台能力都接上了：

| 能力 | 说明 |
|---|---|
| **快捷设置磁贴** | 下拉通知栏一键开锁（对应 iOS 的「操作按钮 / 轻点背面」） |
| **桌面小组件** | Glance 实现，显示当前门禁，点一下直接起服务无头开锁 |
| **后台无头开锁** | 前台服务（`connectedDevice`）跑完整握手，结果用通知回报，不开界面 |
| **深链 + 静态快捷方式** | `baiyunkeys://` scheme + 长按图标出的「立即开锁 / 快速模式」 |
| **生物识别锁屏** | 冷启动验证指纹 / 3D 人脸，没有就退回锁屏密码；两者都无则给兜底入口，不会锁死 |
| **扫码导入** | CameraX + ZXing 实时扫描，全部本地解码，不需要 Google 服务 |
| **长辈模式** | 首页标题长按 3 秒切换：隐藏配置 Tab、锁死门禁选择器、隐藏日志与记录 |
| **自动获取配置三通道** | 身份证 / OpenID / OpenID + SM2 密钥对，含 SM2 密文解密 |
| **文件级加密存储** | 门禁数据用 Android Keystore（AES-256-GCM）加密落盘，对应 iOS 的 Keychain |

其余与 iOS 一致：单门锁开锁、快速模式、失败自动重试、扫描超时、掩码显示、触感反馈、
打开即开锁、切回也开锁、运行日志（200 行）、开锁记录（50 条、新的在前）。

## 与 iOS 的差异

| 项 | iOS | Android |
|---|---|---|
| 蓝牙名 | 必填（CoreBluetooth 不给 MAC） | 选填（可直接读 MAC） |
| 后台入口 | App Intent「后台开锁」 | 前台上限服务 + 小组件 |
| 硬件入口 | 操作按钮 / 轻点背面 / Siri | 快捷设置磁贴 / 桌面小组件 |
| 定位权限 | 不需要 | **必须**（API 31 以下 BLE 扫描的系统前提） |
| DES | 实现只处理单块（`commKey` 协商实际走不通） | 按多块实现，与 web 版一致 |
| 快捷方式 | 快捷指令 | 长按图标的静态快捷方式 |

## 自动构建

`.github/workflows/android.yml`：

- 每次 push / PR / 手动触发：跑 `:core:test`（协议层对拍）→ `:app:lintDebug` → 构建 debug APK → 上传产物；
- 打 `v*` tag：额外构建一版 APK 并附到 Release。

## 测试

`core` 是纯 JVM 模块（零 `android.*` 依赖），协议层全部在这里对拍：**122 条单测**，覆盖
Hex / DES / SM2 / RSA 分块 / 握手帧 / 回执状态机 / 门禁编解码 / 导入导出 / 偏好收敛 / 深链 / 开锁编排。

**golden vectors 不依赖 macOS**：生成器读 iOS 仓库里的 `web/js/core/*.js`（与 Swift 逐函数同构的第三份实现），
在 Node 上产出 `vectors.json` 提交入库。iOS 改协议 → 重跑生成器 → 本仓库测试立刻变红。

```bash
node tools/vectors/gen.mjs
# 或显式指定 iOS 仓库路径：
REF_DIR="/path/to/BaiyunKeys-iOS/web/js/core" node tools/vectors/gen.mjs
```

## URL scheme

App 注册了 `baiyunkeys://` scheme，两种格式：

- `baiyunkeys://unlock` —— 开当前默认门禁
- `baiyunkeys://unlock?door=<门禁 id>` —— 开指定门禁
- `baiyunkeys://unlock?quick=1` —— 快速模式（扫到哪个已知门禁就开哪个）

`门禁 id` 是本机生成，只在本机可用。配置页每条门禁的「链接」按钮直接复制这条 URL，
可以贴到 NFC 标签、桌面书签或自动化工具里。

## 权限与隐私

| 权限 | 用途 |
|---|---|
| 蓝牙 / 蓝牙扫描 / 蓝牙连接 | 连接门锁（按系统版本分支） |
| **精确定位** | Android 12 之前，系统强制要求 BLE 扫描必须有定位权限**且定位开关打开**，否则静默扫不到任何设备。App 只读「定位是否开启」这一个布尔值，**不获取任何坐标** |
| 相机 | 扫码导入 |
| 生物识别 | 冷启动锁屏 |
| 前台服务 | 后台无头开锁 |
| 通知 | 后台开锁的进度与结果回报 |

网络方面：除「自动获取配置」会按需访问官方接口外，其余功能全部本地离线完成。
门禁数据用 Keystore 加密后存在应用私有目录，不上传。

**本仓库不包含任何真实门禁凭据**，示例中的 MAC / Key 均为占位值。

## 已知限制

- 真机验证只覆盖到 Android 9 的一台旧机型；Android 11+ 的生物识别组合弹窗、各厂 2D / 3D 人脸未实测。
- 门锁端到端（连上 → 读随机数 → 握手 → 收到 `0x04` 回执）需要实际门禁才能验，目前验证到「扫描 + 超时 + 失败回执」链路。
- 小组件显示的门禁名在渲染时现读，改门禁后要等系统下一次刷新。
- SM2 私钥与 OpenID 存在 DataStore 明文里（跟随 iOS 的 UserDefaults 取舍），门禁 Key 本身是 Keystore 加密的。

## 免责声明

只是把你本人已获授权的门禁凭据在本地离线复用，不含破解 / 绕过 / 提权，也不上传任何东西到服务器。
请仅在有权进入的门禁上使用，违规后果自负。

## 致谢

- [**BaiyunKeys-iOS**](https://github.com/YiwanGo/BaiyunKeys-iOS) —— 本项目的方案来源与协议事实源，
  Android 版的协议、数据结构与交互均以其为基准移植。

## License

[MIT](LICENSE)
