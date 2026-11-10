package com.xavior.baiyunkeys.ui.components

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baiyunkeys.core.unlock.UnlockPhase
import com.xavior.baiyunkeys.ui.theme.LocalBaiyunPalette

/**
 * 首页大按钮（方案 §4.6 的动画参数 1:1 移植）：
 * - 3 个涟漪圆环：直径 208dp，`scale 0.85→1.45`、`alpha 0.9→0`、easeOut 2.1s 无限循环，每个延迟 0.7s
 * - 中心光晕：236dp，品牌色 10% 透明度
 * - 按钮本体：196dp 圆形，按下 `scale 0.94`（spring）
 */
@Composable
fun UnlockButton(
    phase: UnlockPhase,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = LocalBaiyunPalette.current
    val busy = phase.isBusy
    val clickable = enabled && !busy

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "pressScale",
    )

    val transition = rememberInfiniteTransition(label = "ripple")

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(236.dp)) {

            // 涟漪圆环 ×3，每个错开 0.7s
            repeat(RING_COUNT) { index ->
                val spec = infiniteRepeatable<Float>(
                    animation = tween(durationMillis = RING_DURATION_MS, easing = EaseOut),
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(
                        offsetMillis = index * RING_STAGGER_MS,
                        offsetType = StartOffsetType.Delay,
                    ),
                )
                val ringScale by transition.animateFloat(
                    initialValue = 0.85f,
                    targetValue = 1.45f,
                    animationSpec = spec,
                    label = "ringScale$index",
                )
                val ringAlpha by transition.animateFloat(
                    initialValue = 0.9f,
                    targetValue = 0f,
                    animationSpec = spec,
                    label = "ringAlpha$index",
                )
                Box(
                    modifier = Modifier
                        .size(RING_SIZE.dp)
                        .graphicsLayer {
                            scaleX = ringScale
                            scaleY = ringScale
                            alpha = ringAlpha * RING_ALPHA_WEIGHT
                        }
                        .background(palette.brand, CircleShape),
                )
            }

            // 中心光晕
            Box(
                modifier = Modifier
                    .size(GLOW_SIZE.dp)
                    .background(palette.brand.copy(alpha = 0.10f), CircleShape),
            )

            // 按钮本体
            Box(
                modifier = Modifier
                    .size(BUTTON_SIZE.dp)
                    .graphicsLayer {
                        scaleX = pressScale
                        scaleY = pressScale
                    }
                    .background(
                        brush = Brush.radialGradient(
                            listOf(palette.brand, palette.brand.copy(alpha = 0.82f)),
                        ),
                        shape = CircleShape,
                    )
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = clickable,
                        onClick = onClick,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = glyphFor(phase),
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Text(
            text = labelFor(phase),
            color = when (phase) {
                is UnlockPhase.Failure -> palette.danger
                UnlockPhase.Success -> palette.brand
                else -> palette.textSecondary
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun glyphFor(phase: UnlockPhase): String = when (phase) {
    UnlockPhase.Success -> "✓"
    is UnlockPhase.Failure -> "!"
    UnlockPhase.Idle -> "开"
    else -> "…"
}

private fun labelFor(phase: UnlockPhase): String = when (phase) {
    UnlockPhase.Idle -> "轻点开锁"
    UnlockPhase.Success -> "已开锁"
    is UnlockPhase.Failure -> "重试"
    else -> "进行中"
}

private const val RING_COUNT = 3
private const val RING_SIZE = 208
private const val GLOW_SIZE = 236
private const val BUTTON_SIZE = 196
private const val RING_DURATION_MS = 2100
private const val RING_STAGGER_MS = 700
private const val RING_ALPHA_WEIGHT = 0.25f
