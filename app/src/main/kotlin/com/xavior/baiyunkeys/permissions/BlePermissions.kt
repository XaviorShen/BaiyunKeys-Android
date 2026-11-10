package com.xavior.baiyunkeys.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

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
}
