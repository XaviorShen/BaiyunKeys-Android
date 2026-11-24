package com.baiyunkeys.core.unlock

/**
 * 缺少安卓的运行时权限。界面据此给出「去设置」的入口。
 *
 * 这些类型放在 core 里，是因为编排器要认得它们，才能决定失败后把用户引去哪；
 * 具体怎么检查权限仍然是 app 层 `BleAdapterImpl` 的事。
 */
sealed class BlePermissionException(message: String) : SecurityException(message)

class ScanPermissionMissingException :
    BlePermissionException("没有蓝牙扫描权限。请到系统设置里，为「白云钥匙」打开「附近设备」或「定位」权限")

class ConnectPermissionMissingException :
    BlePermissionException("没有蓝牙连接权限。请到系统设置里，为「白云钥匙」打开「附近设备」权限")

/**
 * 系统定位服务没开。Android 12 以下，定位服务不开时扫描拿不到任何设备，而且不报错。
 * 12 及以上本 App 声明了 `neverForLocation`，AOSP 上不受这个开关影响。
 */
class LocationServiceDisabledException : IllegalStateException(MESSAGE) {
    companion object {
        const val MESSAGE = "系统定位服务没开。Android 12 以下，安卓扫描蓝牙必须打开定位（本应用不采集位置）"
    }
}

/** 扫描没能启动。[code] 是 `android.bluetooth.le.ScanCallback` 的 `SCAN_FAILED_*`。 */
class ScanStartFailedException(val code: Int) : IllegalStateException(BleErrors.describeScanFailure(code))

/** 界面需要把用户引去的系统设置页。 */
enum class SettingsTarget {
    /** 应用详情页：在那里开「附近设备」或「定位」权限。 */
    BluetoothPermission,

    /** 系统的定位开关。 */
    LocationService,
}

/** 把 `ScanCallback` 的错误码说成人话。数值是 Android 定死的，照抄是为了兼容 API 31 以下。 */
object BleErrors {
    fun describeScanFailure(code: Int): String = when (code) {
        1 -> "扫描已经在进行中，请重试（错误码 1）"
        2 -> "系统没能登记这次扫描，请重试（错误码 2）"
        3 -> "系统蓝牙出了内部错误，请重试（错误码 3）"
        4 -> "当前机型不支持这种扫描方式（错误码 4）"
        5 -> "系统蓝牙资源已满，请稍后再试（错误码 5）"
        6 -> "扫描启动得太频繁，系统暂时限制了，请等半分钟再试（错误码 6）"
        else -> "扫描启动失败（错误码 $code）"
    }
}
