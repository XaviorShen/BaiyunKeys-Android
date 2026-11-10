package com.xavior.baiyunkeys.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.xavior.baiyunkeys.App
import com.xavior.baiyunkeys.MainActivity
import com.xavior.baiyunkeys.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 后台无头开锁（方案 §4.7 的 P5 项，对应 iOS「后台开锁」App Intent）。
 *
 * 用前台服务（`connectedDevice` 类型）跑完整开锁流程，不开界面，结果用通知回报。
 * 入口：桌面小组件的「开锁」按钮、以及任何拿到 service intent 的地方。
 */
class UnlockForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as App).container
        val doorId = intent?.getStringExtra(EXTRA_DOOR_ID)

        startForeground(NOTIFICATION_ID, buildNotification("正在开锁…", null))

        scope.launch {
            val settings = container.settings.settings.value
            val doors = container.doors.state.value.items
            val door = doorId?.let { id -> doors.firstOrNull { it.id == id } } ?: container.doors.current

            val result = if (door == null) {
                com.baiyunkeys.core.unlock.UnlockResult(false, "没有可用的门禁")
            } else {
                container.unlock.unlock(door, settings.toUnlockOptions())
            }

            showResult(title = door?.displayName ?: "白云钥匙", result.success, result.message)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun showResult(title: String, success: Boolean, message: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(message, success, title))
    }

    private fun buildNotification(
        text: String,
        success: Boolean?,
        title: String = "白云钥匙",
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(success?.let { R.drawable.ic_stat_unlock } ?: R.drawable.ic_stat_unlock)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(success == null)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "开锁", NotificationManager.IMPORTANCE_LOW).apply {
                description = "后台开锁的进度与结果"
            },
        )
    }

    companion object {
        const val EXTRA_DOOR_ID = "door_id"
        private const val CHANNEL_ID = "baiyun.unlock"
        private const val NOTIFICATION_ID = 1001

        /** 拉起一次后台开锁。[doorId] 为空时开当前门禁。 */
        fun start(context: Context, doorId: String? = null) {
            val intent = Intent(context, UnlockForegroundService::class.java)
                .putExtra(EXTRA_DOOR_ID, doorId)
            context.startForegroundService(intent)
        }
    }
}
