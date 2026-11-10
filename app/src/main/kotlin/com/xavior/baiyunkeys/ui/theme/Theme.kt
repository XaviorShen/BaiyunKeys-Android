package com.xavior.baiyunkeys.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 色板 1:1 移植自 `BaiyunKeys-iOS/BaiyunKeys/Design/Theme.swift`（见方案 §4.6）。
 *
 * 圆角：卡片 26dp、输入框 16dp、页边距 20dp。
 */
data class BaiyunPalette(
    val brand: Color,
    val brandSoft: Color,
    val info: Color,
    val infoSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val warn: Color,
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val separator: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
) {
    companion object {
        val Light = BaiyunPalette(
            brand = Color(0xFF0FB981),
            brandSoft = Color(0xFFE7F8F1),
            info = Color(0xFF2F6BFF),
            infoSoft = Color(0xFFEAF0FF),
            danger = Color(0xFFE5484D),
            dangerSoft = Color(0xFFFDECEC),
            warn = Color(0xFFC77D14),
            background = Color(0xFFF2F4F7),
            surface = Color(0xFFFFFFFF),
            surfaceAlt = Color(0xFFF5F7FA),
            separator = Color(0xFFE7EBF0),
            textPrimary = Color(0xFF0F1419),
            textSecondary = Color(0xFF69747F),
            textTertiary = Color(0xFF9AA3AD),
        )

        val Dark = BaiyunPalette(
            brand = Color(0xFF2DD4A0),
            brandSoft = Color(0xFF0F2E26),
            info = Color(0xFF5B8CFF),
            infoSoft = Color(0xFF131E3A),
            danger = Color(0xFFFF6369),
            dangerSoft = Color(0xFF33191B),
            warn = Color(0xFFF5B14C),
            background = Color(0xFF0A0C10),
            surface = Color(0xFF151A21),
            surfaceAlt = Color(0xFF1D232C),
            separator = Color(0xFF252C36),
            textPrimary = Color(0xFFF3F6F9),
            textSecondary = Color(0xFF99A4B0),
            textTertiary = Color(0xFF6C7783),
        )
    }
}

object BaiyunDimens {
    const val cardRadius = 26
    const val fieldRadius = 16
    const val pagePadding = 20
}

val LocalBaiyunPalette = staticCompositionLocalOf { BaiyunPalette.Light }

@Composable
fun BaiyunTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) BaiyunPalette.Dark else BaiyunPalette.Light
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.brand,
            onPrimary = palette.background,
            background = palette.background,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            onSurfaceVariant = palette.textSecondary,
            error = palette.danger,
        )
    } else {
        lightColorScheme(
            primary = palette.brand,
            onPrimary = Color.White,
            background = palette.background,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            onSurfaceVariant = palette.textSecondary,
            error = palette.danger,
        )
    }

    CompositionLocalProvider(LocalBaiyunPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
    }
}
