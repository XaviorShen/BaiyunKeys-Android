# 白云钥匙 · Android 原生版

[![下载最新构建](https://img.shields.io/badge/%E2%AC%87%20%E4%B8%8B%E8%BD%BD-%E6%9C%80%E6%96%B0%E6%9E%84%E5%BB%BA-0FB981?style=for-the-badge&logo=android&logoColor=white)](https://github.com/XaviorShen/BaiyunKeys-Android/releases/download/latest/BaiyunKeys-Android.apk)
[![Android CI](https://img.shields.io/github/actions/workflow/status/XaviorShen/BaiyunKeys-Android/android.yml?branch=main&style=for-the-badge&logo=githubactions&logoColor=white&label=Android%20CI)](https://github.com/XaviorShen/BaiyunKeys-Android/actions/workflows/android.yml)
[![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge&logo=opensourceinitiative&logoColor=white)](LICENSE)
[![Android](https://img.shields.io/badge/Android-9%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#安装)

> ## ⚠️ 请先读这几条
>
> 1. **仅静态复刻**：本项目是对 [BaiyunKeys-iOS](https://github.com/YiwanGo/BaiyunKeys-iOS) 的**静态复刻**
>    （协议逐函数对拍 + UI 复刻），不是官方版本，也不含任何破解能力。
> 2. **未完成真机端到端测试**：验证止步于单元测试、模拟器 UI、真机蓝牙扫描与超时链路，
>    **尚未在真实门禁设备上完成一次成功开锁**。请自行评估风险后使用。
> 3. **由 AI 完成**：本项目代码由 **DeepSeek** 生成（协议移植、UI、测试、文档、构建脚本），
>    人工负责需求与验收。AI 产出的代码**可能有错误**，与实际设备/机型不符之处欢迎指出。
> 4. **欢迎提交 Issue / PR**：发现 bug、真机结果、机型适配、协议差异、文档纠错，
>    都请提 [Issue](../../issues) 或直接发 **Pull Request**，会尽快处理。

> ## 🚨 从 1.0.1 及更早版本升级：必须先导出备份再卸载
>
> v1.0.2 起改用**固定签名**，旧包是每次构建随机生成的 debug 签名，系统不允许覆盖安装
> （`INSTALL_FAILED_UPDATE_INCOMPATIBLE`，小米提示「与已安装应用签名不同的应用包」）。
>
> **卸载会清空应用数据**：门禁列表用 Android Keystore 加密，密钥随卸载销毁，
> 系统备份 / 换机助手都恢复不了。请先 **配置 → 导出**（文本或二维码），
> 再卸载 → 装新版 → 导入。
>
> **已经在 1.0.2 或更新的版本上：直接覆盖安装即可，不用卸载，数据保留。**
>
> 完整步骤见 [v1.0.2 发版说明](docs/release-notes/v1.0.2.md)、
> [v1.0.3 发版说明](docs/release-notes/v1.0.3.md) 与下文「更新到新版本」。

蓝牙门禁离线开锁，Kotlin + Jetpack Compose，无 GMS 依赖，单 APK。作者：[Xavior](https://github.com/XaviorShen)。

本项目是 [**BaiyunKeys-iOS**](https://github.com/YiwanGo/BaiyunKeys-iOS) 的 **Android 原生移植**。
方案设计参考自该项目：通信协议、DES 加解密、门禁数据结构、文本 / 二维码互通格式、色板与交互，
全部以 iOS 实现为唯一事实源，两端（以及配套网页版）数据互通，导入导出可以直接搬。

配套网页版：<https://baiyunkeys-web.pages.dev/>，安卓 / iOS / 电脑浏览器都能用，一套格式互通。

> Android 用户请使用 Edge 或 Chrome 打开网页版，系统自带浏览器可能存在 Web Bluetooth 兼容问题。

## 安装

**系统要求：Android 9（API 28）及以上。** 目标机型是主楼门禁旁的旧手机（Android 10），
为兼容更老的机器把地板上探到 9。

### 直接下载

点最上面那个绿色按钮，或直接访问：

```
https://github.com/XaviorShen/BaiyunKeys-Android/releases/download/latest/BaiyunKeys-Android.apk
```

这个链接**永远指向 main 分支的最新构建**（每次推送自动重新构建并覆盖），文件名固定，不会失效。

- 想找**正式版本**：去 [Releases](../../releases) 里带 `v*` 标签的条目；
- 想找**历史构建**：去 [Actions](../../actions) 下载对应运行的 artifact（保留 14 天）。

安装时需要允许「安装未知来源应用」。APK 用**固定的 release keystore** 签名
（v1.0.2 起；在此之前是每次构建随机生成的 debug key），仅用于个人侧载，
不要拿它上架或当作正式签名分发。

### 更新到新版本

> ⚠️ **只有从 1.0.1 及更早版本升级时才需要卸载一次**；1.0.2 及之后都能覆盖升级、数据保留。
> 也就是说：1.0.3 对 1.0.2 用户是普通更新，对 1.0.1 用户仍然是破坏性升级。

但只要涉及卸载，就务必先备份：

1. 配置页 → **导出**（文本或二维码），核对门禁齐全；
2. 卸载旧版（小米「应用双开 / 手机分身」是独立空间，要分别导出、分别卸载）；
3. 安装新 APK；
4. 配置页 → **导入**，恢复门禁。

**以前为什么每次都要卸载**：v1.0.1 及更早的包由 CI 现场生成 debug keystore 签名，
每次构建的证书都不同，而 Android 只允许「包名 + 签名相同」的包覆盖安装。
v1.0.2 起改用仓库固定的 release keystore（GitHub Secrets 注入），之后所有版本共用一张证书。

**卸载为什么代价这么大**：门禁用 Android Keystore（AES-256-GCM）加密落盘，密钥跟着应用一起销毁，
`allowBackup=false` 又不走系统备份——系统备份、换机助手都恢复不了，
**App 内导出的文本 / 二维码是唯一可迁移的备份**。导出内容含明文 Key，等同实体钥匙，别外传。

### 应用内检测更新

装好之后不用自己盯 Releases：每次冷启动（6 小时内最多检查一次）会去
[GitHub Releases API](https://api.github.com/repos/XaviorShen/BaiyunKeys-Android/releases/latest)
看一眼最新**正式版本**，有新版本就弹窗提示，按钮三个：

| 按钮 | 行为 |
|---|---|
| **立即更新** | 用系统浏览器打开**最新正式 Release 里 `BaiyunKeys-Android.apk` 的直链**，下载完由浏览器交给系统安装器 |
| **稍后提醒** | 关掉弹窗，同一版本 24 小时内不再打扰 |
| **前往 GitHub 下载** | 打开**项目的 Releases 页面**，自己挑版本（不是跳到某一个 tag 的详情页） |

配置页「关于」里还有一枚**检查更新按钮**，它是这件事的唯一入口，文案直接写清动作：

| 状态 | 按钮文案 | 点击 |
|---|---|---|
| 还没查过 | 检查更新 | 立刻联网检查 |
| 正在查 | 正在检查… | 禁用 |
| 已是最新 | 已是最新 · 重新检查 | 重新联网检查 |
| 有更新 | 检测到更新 · 查看详情 | 重看更新弹窗（不再联网） |
| 失败 | 检查失败 · 点此重试 | 重新联网检查 |

点它等于手动检查一次，不受 6 小时节流限制；冷启动被节流跳过时，按钮会直接沿用上次结论，不会退回「检查更新」。

App **不内置下载器**，下载与安装全部交给系统浏览器和系统安装器，
因此不需要存储权限，也不用申请「安装未知来源应用」的一次性授权。

> 检测只认带 `v*` 标签的**正式 Release**；滚动 `latest` 那个自动构建不参与版本比较，
> 仓库一个正式版本都没发过时，会显示「已是最新」。

### 自己构建

见下面的「跑起来」。

## 跑起来

JDK 21 + Android SDK 36。命令行：

```bash
./gradlew :app:assembleDebug      # 产物 app/build/outputs/apk/debug/
./gradlew :core:test              # 协议层单测，纯 JVM，不需要模拟器
./gradlew :app:lintDebug          # lint
```

Android Studio 直接打开根目录，Gradle 同步后 Run 即可。
`local.properties` 需要自己写 `sdk.dir=`（该文件不入库）。

**签名**：本地构建需要一个固定的 keystore，否则打出来的包装不进/盖不住已安装版本。
把 `baiyunkeys-release.jks` 放进 `signing/`，再写一份 `signing/keystore.properties`：

```properties
storePassword=你的密码
keyAlias=baiyunkeys
keyPassword=你的密码
```

`signing/` 已在 `.gitignore` 里；没有这份材料时本地构建会退回 debug key 方便跑通，
而 CI 上一旦缺签名材料会**直接失败**（不允许悄悄发出一个随机签名的包）。

真机要求：蓝牙（必须）、相机（扫码导入）、指纹 / 面容或锁屏密码（生物识别锁屏，可选）、
通知（Android 13 起可选；小组件开锁的进度和结果靠通知回报，不给的话点了小组件没有任何显示）。

## 用法

配置 → 新增门禁，填 MAC / Key / 蓝牙名 / 一个自己认得的门禁名。回首页点中间那个大圆按钮。

**MAC 尽量填真实地址** —— Android 可以不扫描、直接按 MAC 连接门锁（iOS 拿不到 MAC，只能靠广播名，所以那边蓝牙名必填）。
门禁的 MAC 以 `00:0` 开头、后面正好是蓝牙名的 8 位尾号时，说明它是**按蓝牙名反推出来的**，不是真实地址：
握手照常可用，但没法直连，只能靠扫描和蓝牙名匹配。补上真实 MAC 就能直连。

**扫不到门锁时怎么排查**：开锁失败后，运行日志可以整份复制（「展开」旁边有「复制」）。失败原因是缺权限或
定位没开时，按钮旁边会出现「去设置」。「配置 → 打开扫描面板」能看到系统定位开关、蓝牙权限，以及附近每台设备
对当前门禁的匹配结果；门锁在列表里但认不出来时，点「绑定」可以把它直接记为这条门禁的直连地址。

### 门禁参数怎么拿

用抓包工具（Charles / Fiddler / Whistle / Reqable 之类）挂上代理，打开微信里的**「平安白云」小程序**，
进到你有权限的门禁列表页面，抓 `get_guard_list_by_phone` 这个接口的响应，里面每个门禁一条记录都能拿到：

- **蓝牙名**：广播名，形如 `BY0AABBCCDD`（`BY` + 8 位 hex + 1 位后缀）。MAC 是真实地址时选填；MAC 是按蓝牙名
  反推的值时必填
- **Key**（`productKey`）：16 ~ 32 位 hex，等同实体钥匙，别外传。抓到更长（例如 112 位 hex），
  属于加密下发的密文，需配套 SM2 私钥才能解回明文
- **MAC**：尽量填真实地址（接口里的 `macNum`）。握手只用后 4 字节，但真实地址还能让 App 不扫描直接连

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
| **应用内检测更新** | 冷启动查 GitHub 正式 Release（6h 节流），弹窗提示，「立即更新」交系统浏览器下载 |
| **文件级加密存储** | 门禁数据用 Android Keystore（AES-256-GCM）加密落盘，对应 iOS 的 Keychain |

其余与 iOS 一致：单门锁开锁、快速模式、失败自动重试、扫描超时、掩码显示、触感反馈、
打开即开锁、切回也开锁、运行日志（200 行）、开锁记录（50 条、新的在前）。

## 与 iOS 的差异

| 项 | iOS | Android |
|---|---|---|
| 蓝牙名 | 必填（CoreBluetooth 不给 MAC） | 选填（有真实 MAC 可直连）；MAC 是按蓝牙名反推的值时必填 |
| 找门锁的方式 | 扫描，按广播名认门锁 | **先按 MAC 直连，失败再扫描**（issue #5） |
| 后台入口 | App Intent「后台开锁」 | 前台上限服务 + 小组件 |
| 硬件入口 | 操作按钮 / 轻点背面 / Siri | 快捷设置磁贴 / 桌面小组件 |
| 定位权限 | 不需要 | **必须**（API 31 以下 BLE 扫描的系统前提） |
| DES | 实现只处理单块（`commKey` 协商实际走不通） | 按多块实现，与 web 版一致 |
| 快捷方式 | 快捷指令 | 长按图标的静态快捷方式 |

## 自动构建

`.github/workflows/android.yml`，三个 job：

| 触发 | 做什么 | 产物 |
|---|---|---|
| push / PR / 手动 | `:core:test` → `:app:lintDebug` → `:app:assembleDebug` | Actions artifact（保留 14 天） |
| push 到 `main` | 上面全部 + 更新滚动 Release `latest` | `BaiyunKeys-Android.apk`（README 按钮指向这里） |
| 打 `v*` tag | `:app:assembleRelease` + 生成 Release Notes | 正式 Release 的 `BaiyunKeys-Android.apk` |

发版只需：

```bash
git tag v1.0.0
git push origin v1.0.0
```

## 测试

`core` 是纯 JVM 模块（零 `android.*` 依赖），协议层全部在这里对拍：**203 条单测**，覆盖
Hex / DES / SM2 / RSA 分块 / 握手帧 / 回执状态机 / 门禁编解码（含逐条容错）/ 导入导出 / 偏好收敛 / 深链 /
开锁编排 / 直连优先与推导 MAC 识别 / GATT 服务排序与订阅值 / 失败原因分流 / 取消 / 版本比较 / GitHub Release 解析。

**golden vectors 不依赖 macOS**：生成器读 iOS 仓库里的 `web/js/core/*.js`（与 Swift 逐函数同构的第三份实现），
在 Node 上产出 `vectors.json` 提交入库。iOS 改协议 → 重跑生成器 → 本仓库测试立刻变红。

```bash
node tools/vectors/gen.mjs
# 或显式指定 iOS 仓库路径：
REF_DIR="/path/to/BaiyunKeys-iOS/web/js/core" node tools/vectors/gen.mjs
```

## 与其它实现的静态比对

除与 iOS 版逐函数对拍外，还拿两份公开的 Android 实现做了逐字节比对：

| 项目 | 走的流程 | 比对结果 |
|---|---|---|
| [RinkTika/BaiyunKeys](https://github.com/RinkTika/BaiyunKeys) | 完整流程（握手 → 通讯密钥 → 时间同步 → 回执） | 握手帧、DES 密钥处理、`openResult`、`timeHex` **逐字节一致** |
| [yy1643/baiyunkey--](https://github.com/yy1643/baiyunkey--) | 简化流程（握手 → 校验 0x04 回执） | 握手帧一致；0x04 回执的校验方式不同（见下） |

比对中发现并处理的两点：

1. **通讯密钥帧的校验和范围**：两份实现都只把随机数的**前 4 字节**计入 sum/xor，我们原来用了全长。
   已修正（当前流程并不调用该指令，属于潜在坑，不是现网 bug）。
2. **0x04 回执的判定**：yy1643 会解密 `[10..17]` 并要求状态字节 `payload[2] == 0` 才算成功；
   iOS 版（本项目的事实源）把长帧直接当成功。真机没验过之前不擅自改判定，
   但已把**长度字节 / 校验和 / 解密后的状态字节**写进运行日志——真机跑一次就能看出该信谁。

另外确认：`commKey` / `timeSync` 两条指令在 iOS 版里**从未被调用**（只有定义），本项目保持一致，
属于协议预留，不是漏实现。

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
| **网络（`INTERNET`）** | **仅用于检测 GitHub 新版本**。普通权限，安装时自动授予，不会弹运行时授权框；首次启动会说明一次用途 |

网络方面：只有检测更新一处会联网，其余全部本地离线，**没有任何后台联网行为**。

| 场景 | 请求 | 携带的信息 | 能不能关 |
|---|---|---|---|
| **检测更新**（冷启动 6h 一次 / 关于页手动） | `api.github.com` 查最新正式 Release | IP、User-Agent；无账号、无 cookie、无门禁数据 | ✅ 设置 → 联网与隐私 → 关闭「检查更新」，冷启动零请求（手动检查仍会发一次） |

- 关掉「检查更新」后，App 冷启动不再产生任何网络请求；关于页仍可手动检查（那是你主动要的）。
- 门禁数据用 Keystore 加密后存在应用私有目录，**不上传**；更新检查也不会读取任何本地数据。
- GitHub 在国内经常连不上，此时表现为「请求超时」，不影响任何离线功能。

**本仓库不包含任何真实门禁凭据**，示例中的 MAC / Key 均为占位值。

## 已知限制与验证边界

**已验证**（代码 + 单元测试 + 真机）：

- 203 条单元测试全绿；协议层与 iOS web 版 golden vectors 逐字节对拍，诊断页在真机上算出的握手帧与 vector 一致；
- 真机（Android 9、Android 17 各一台）跑通：安装启动、蓝牙扫描（收到真实广播、10s 超时、失败回执与历史记录）、
  Keystore 加密落盘、掩码显示、深链、小组件 / 快捷方式注册；
- 生物识别锁屏：**Android 9 走指纹、Android 17 走 `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` 组合**，
  两条路径都验证到「冷启动上锁 → 系统弹窗 → 指纹通过 → 进入」，且每次冷启动都会重新上锁；
- 无凭据设备会显示「关闭生物识别锁屏并进入」兜底，点击后放行（不会把人锁在 App 外）。

**未验证**（诚实声明）：

- **真实门禁未开过一次锁**：连上 → 读随机数 → 握手 → 收到 `0x04` 回执这一段没条件验，目前只到「扫描 + 超时 + 失败回执」；
- **连上门锁前后的改动未在真机验证**：按 MAC 直连、直连失败后回退扫描、订阅时按特征属性选值、已知厂商服务优先，
  这几处改动（issue #5）都只有单元测试，没连过真实门锁；
- Android 11+ 上 2D 人脸（Class 2）与设备密码的具体弹窗交互、各厂 3D 人脸未逐台实测；
- 磁贴 / 小组件只确认注册成功，没有实际点击；Android 14 起缺蓝牙权限时不进前台的兜底、
  Android 13 起的通知权限申请，都只过了编译和 lint；
- 扫码导入（相机链路）、文本导入（剪贴板）未在真机走完整流程。

**设计取舍**：

- 小组件显示的门禁名在渲染时现读，改门禁后要等系统下一次刷新；
- 找门锁的顺序是「上次连上的地址 → 门禁 MAC 直连 → 扫描」；蓝牙名在 Android 是选填，iOS 必填；
- **门禁数据读不全时不丢原始数据**：某条解不开时只跳过那一条，其余照常用，并把原始密文另存一份
  （`doorState.v2.recover`，仍是加密的），之后的写入不会把它覆盖掉；写操作串行，避免主线程和开锁流程互相覆盖；
- **更新检测只比正式 Release**（`v*` tag）：滚动 `latest` 那个自动构建的 tag 是字面量 `latest`，
  没法参与版本比较；仓库一个正式版本都没发过时，关于页会显示「已是最新」；
- **下载与安装完全交给系统浏览器**：App 拿不到安装结果，也不做静默安装，因此不申请存储权限与安装权限；
- 开屏检测 6 小时节流、同一版本 24 小时内最多提示一次，避免每次冷启动都打扰；
- **签名**：v1.0.2 起固定用仓库的 release keystore（GitHub Secrets 注入，`signing/` 已 gitignore）。
  在此之前是 AGP 现场生成的 debug keystore，每次 CI 构建都是新证书，所以 1.0.1 → 1.0.2
  必须卸载一次；**这也是最后一次**。私钥同时需离线备份，丢了就再也发不出能覆盖升级的包。

## 授权与合规

上游项目 [BaiyunKeys-iOS](https://github.com/YiwanGo/BaiyunKeys-iOS) 采用 **MIT 许可**
（`Copyright (c) 2026 YiwanGo`）。MIT 明确授予「使用、复制、修改、合并、发布、分发、再许可和/或销售」的权利，
唯一义务是**在副本或实质性部分中保留版权声明与许可声明**。因此：

- 本项目对 iOS 版做**复刻与修改，是上游许可允许的**；
- 本项目的 `LICENSE` 保留了上游版权行，README 的「致谢」也注明了来源；
- MIT 不是 copyleft，不要求衍生作品必须开源；本项目出于尊重同样以 MIT 发布。

需要说清的边界：

- MIT 覆盖的是**上游作者的代码**，不覆盖第三方平台/服务（例如门禁背后的官方接口与协议本身）；
- 「白云钥匙」等名称、图标归原项目所有。**本项目不是官方版本**，与相关公司/品牌无任何隶属、合作或背书关系。

## 免责声明与法律责任

1. **用途**：本项目只做一件事——把**你本人已获授权**的门禁凭据在本机离线复用。不含破解、绕过鉴权、提权、
   密钥窃取、抓包工具或任何破解辅助功能。
2. **合规责任在使用者**：请只在**你有权进入**的门禁上使用，并遵守当地法律与场所规定。
   因使用本项目产生的任何法律责任，由使用者自行承担。
3. **风险自担**：本软件可能因协议变动、设备差异、系统限制而失效，甚至导致门禁不可用、数据异常、
   设备异常耗电等后果。作者与贡献者**不对任何直接或间接损失承担责任**。
4. **无担保**：软件按「原样」（AS IS）提供，不附带任何明示或默示担保，包括但不限于适销性、特定用途适用性与非侵权。
5. **数据**：除「检测更新」会访问 GitHub API 外，其余功能全部本地离线完成；
   门禁数据用 Android Keystore 加密存放于应用私有目录，不上传任何服务器。本仓库**不包含任何真实门禁凭据**，示例值均为占位。

部分特殊功能默认不显示，如何开启可能需要您自行阅读源码。

## 版权与 DMCA / 侵权处理

- **代码版权**：上游 `BaiyunKeys-iOS` © 2026 YiwanGo；Android 移植部分 © 2026 **Xavior**（GitHub [@XaviorShen](https://github.com/XaviorShen)）。条款见 [`LICENSE`](LICENSE)。
- **商标与非官方声明**：「白云钥匙」「平安白云」等名称、标识归各自权利人所有，本项目仅在描述性意义上提及，
  **不代表官方、不构成背书**。
- **侵权投诉（DMCA / 下架请求）**：若你是权利人或其授权代理，认为本仓库内容侵犯你的合法权益，请任选一种方式：

  1. 在本仓库提交 [Issue](../../issues)（标题注明「侵权投诉」），说明**具体文件 / 代码位置**与权利依据；
  2. 通过 GitHub 官方的 [DMCA Takedown 流程](https://docs.github.com/site-policy/content-removal-policies/dmca-takedown-policy) 提交。

  收到内容明确、依据有效的通知后，维护者会在核实后**尽快删除或修改相关内容**。
- **我方立场**：本项目无意侵害任何第三方权利。如果上游作者或相关权利人希望本项目调整、改名或下架，
  我们愿意配合处理。

## 第三方组件

| 组件 | 用途 | 许可 |
|---|---|---|
| [BouncyCastle](https://www.bouncycastle.org/)（lightweight API） | SM2 / RSA / DES | Bouncy Castle License（MIT 风格） |
| [OkHttp](https://square.github.io/okhttp/) | 官方接口 HTTP | Apache-2.0 |
| [ZXing core](https://github.com/zxing/zxing) | 二维码生成与解码 | Apache-2.0 |
| AndroidX / Jetpack Compose / CameraX / Glance / Biometric | UI、相机、小组件、生物识别 | Apache-2.0 |

## 致谢

- [**BaiyunKeys-iOS**](https://github.com/YiwanGo/BaiyunKeys-iOS) —— 本项目的方案来源与协议事实源，
  Android 版的协议、数据结构与交互均以其为基准移植。感谢作者以 MIT 许可开放。
- **[DeepSeek](https://www.deepseek.com/)** —— 本项目的代码实现（协议移植、UI、单元测试、CI 与文档）由 DeepSeek 完成。
  如在使用中遇到问题，欢迎提交 Issue 或 PR。

## License

[MIT](LICENSE)


