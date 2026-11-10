package com.xavior.baiyunkeys.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.provideContent
import androidx.glance.Button
import androidx.glance.layout.Alignment as GlanceAlignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xavior.baiyunkeys.App
import com.xavior.baiyunkeys.service.UnlockForegroundService

/**
 * 桌面小组件（方案 §4.7 的 P5 项，对应 iOS 桌面小组件）。
 *
 * 显示当前门禁名，点「开锁」直接起前台服务做**无头开锁**，不用打开 App。
 * 数据是渲染时现读的（Glance 每次刷新都会重新执行 `provideGlance`）。
 */
class UnlockWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetBody(context) }
    }
}

@Composable
private fun WidgetBody(context: Context) {
    val container = (context.applicationContext as App).container
    val door = container.doors.current

    val serviceIntent = Intent(context, UnlockForegroundService::class.java)
        .putExtra(UnlockForegroundService.EXTRA_DOOR_ID, door?.id)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalAlignment = GlanceAlignment.CenterHorizontally,
        verticalAlignment = GlanceAlignment.CenterVertically,
    ) {
        Text(
            text = "白云钥匙",
            style = TextStyle(fontSize = 11.sp, color = ColorProvider(Color(0xFF69747F))),
        )
        Spacer(modifier = GlanceModifier.height(2.dp))
        Text(
            text = door?.displayName ?: "未配置门禁",
            style = TextStyle(
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = ColorProvider(Color(0xFF0F1419)),
            ),
        )
        Spacer(modifier = GlanceModifier.height(6.dp))
        Button(
            text = "开锁",
            onClick = actionStartService(
                intent = serviceIntent,
                isForegroundService = true,
            ),
        )
    }
}

/** 小组件广播接收器（Glance 要求）。 */
class UnlockWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UnlockWidget()
}
