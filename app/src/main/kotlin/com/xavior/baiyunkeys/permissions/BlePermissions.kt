package com.xavior.baiyunkeys.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.baiyunkeys.core.unlock.SettingsTarget

/**
 * BLE 权限按版本分支（方案 §4.3）：
 * - API 29/30：`ACCESS_FINE_LOCATION`（运行时）+ 安装时蓝牙权限
 * - API 31+：`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`
 */
object BlePermissions {

    fun required(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun granted(context: Context): Boolean = required().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 冷启动时要申请的全部权限：蓝牙那几项，加上通知（Android 13 起）。
     *
     * 通知权限只为小组件的无头开锁：进度和结果都靠通知回报，没有这个权限，
     * 点了小组件什么都不显示。
     */
    fun startupRequests(): Array<String> {
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }
        return required() + notifications
    }

    /** 冷启动时还有没有权限要申请：蓝牙权限，或通知权限。 */
    fun startupMissing(context: Context): Boolean = !granted(context) || !notificationsGranted(context)

    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 把用户引到对应的系统设置页：应用详情（在那里开权限），或定位开关。
     *
     * Android 11 起，同一项权限被拒绝两次后系统不再弹窗，只能从这里手动打开。
     * 个别机型没有这些页面，打不开就算了，不把异常抛给用户。
     */
    fun openSettings(context: Context, target: SettingsTarget) {
        val intent = when (target) {
            SettingsTarget.BluetoothPermission -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            )
            SettingsTarget.LocationService -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        }
        runCatching { context.startActivity(intent) }
    }
}
