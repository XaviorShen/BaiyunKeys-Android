package com.xavior.baiyunkeys.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.net.toUri
import com.baiyunkeys.core.deeplink.UnlockLink
import com.xavior.baiyunkeys.App
import com.xavior.baiyunkeys.MainActivity

/**
 * 快捷设置磁贴（方案 §4.7 的 P5 项，替代 iOS「操作按钮 / 轻点背面」）。
 *
 * 点一下拉起 App 并走深链开锁 —— 而不是在磁贴里做无头开锁：
 * Android 12+ 对后台启动前台服务有严格限制，磁贴这种「应用不在前台」的时机起服务容易失败，
 * 无头开锁留给桌面小组件（它能用 `PendingIntent` 合法地起服务）。
 */
@Suppress("DEPRECATION")
class UnlockTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "开锁"
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()

        val door = (application as App).container.doors.current
        val uri = door?.let { UnlockLink.build(it.id) } ?: "baiyunkeys://unlock"

        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = uri.toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+ 要求传 PendingIntent，旧重载已废弃
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
