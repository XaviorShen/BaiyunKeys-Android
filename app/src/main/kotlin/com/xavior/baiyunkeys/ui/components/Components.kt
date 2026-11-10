package com.xavior.baiyunkeys.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.xavior.baiyunkeys.ui.theme.BaiyunDimens
import com.xavior.baiyunkeys.ui.theme.LocalBaiyunPalette

/** 卡片：26dp 圆角、surfaceAlt 底色，对应 iOS 的 `Card`。 */
@Composable
fun BaiyunCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = LocalBaiyunPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.surfaceAlt, RoundedCornerShape(BaiyunDimens.cardRadius.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

/** 卡片头：标题 + 可选副标题。 */
@Composable
fun CardHeader(
    title: String,
    subtitle: String? = null,
    tint: Color? = null,
) {
    val palette = LocalBaiyunPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            color = tint ?: palette.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        subtitle?.let {
            Text(text = it, color = palette.textSecondary, fontSize = 12.sp)
        }
    }
}

/** 主按钮：品牌色实心，对应 iOS `FilledButton`。 */
@Composable
fun FilledButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val palette = LocalBaiyunPalette.current
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(BaiyunDimens.fieldRadius.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = tint ?: palette.brand,
            contentColor = Color.White,
        ),
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** 次要按钮：透明底 + 彩色文字，对应 iOS `QuietButton`。 */
@Composable
fun QuietButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val palette = LocalBaiyunPalette.current
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text(
            text = title,
            color = if (enabled) (tint ?: palette.brand) else palette.textTertiary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 输入框：16dp 圆角、带标题与提示。 */
@Composable
fun FlatField(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    hint: String? = null,
    monospaced: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    supportingError: String? = null,
) {
    val palette = LocalBaiyunPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = palette.textSecondary, fontSize = 12.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(placeholder, color = palette.textTertiary, fontSize = 13.sp) },
            textStyle = TextStyle(
                color = palette.textPrimary,
                fontSize = 14.sp,
                fontFamily = if (monospaced) FontFamily.Monospace else FontFamily.Default,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            isError = supportingError != null,
            shape = RoundedCornerShape(BaiyunDimens.fieldRadius.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = palette.brand,
                unfocusedBorderColor = palette.separator,
                focusedContainerColor = palette.surface,
                unfocusedContainerColor = palette.surface,
            ),
        )
        val note = supportingError ?: hint
        note?.let {
            Text(
                text = it,
                color = if (supportingError != null) palette.danger else palette.textTertiary,
                fontSize = 11.sp,
            )
        }
    }
}

/** 空状态占位。 */
@Composable
fun EmptyStateView(title: String, subtitle: String) {
    val palette = LocalBaiyunPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = palette.textSecondary, fontSize = 14.sp)
        Text(subtitle, color = palette.textTertiary, fontSize = 12.sp)
    }
}

/** 键值行：左边标签，右边值。 */
@Composable
fun KeyValueRow(label: String, value: String, monospaced: Boolean = true) {
    val palette = LocalBaiyunPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = palette.textSecondary, fontSize = 12.sp)
        Text(
            text = value,
            color = palette.textPrimary,
            fontSize = 12.sp,
            fontFamily = if (monospaced) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

/** 开关行：标题 + 副标题 + Switch。 */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val palette = LocalBaiyunPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = palette.textPrimary, fontSize = 14.sp)
            subtitle?.let { Text(it, color = palette.textTertiary, fontSize = 11.sp) }
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/** 步进行：`−  数值  +`，取值夹在 [range] 内。 */
@Composable
fun StepperRow(
    title: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    subtitle: String? = null,
    suffix: String = "",
    enabled: Boolean = true,
) {
    val palette = LocalBaiyunPalette.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = palette.textPrimary, fontSize = 14.sp)
            subtitle?.let { Text(it, color = palette.textTertiary, fontSize = 11.sp) }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            QuietButton(
                title = "−",
                onClick = { onValueChange((value - 1).coerceIn(range)) },
                enabled = enabled && value > range.first,
            )
            Text(
                text = "$value$suffix",
                color = if (enabled) palette.textPrimary else palette.textTertiary,
                fontSize = 14.sp,
            )
            QuietButton(
                title = "+",
                onClick = { onValueChange((value + 1).coerceIn(range)) },
                enabled = enabled && value < range.last,
            )
        }
    }
}

/** 统一的分隔线颜色。 */
@Composable
fun baiyunSeparator(): Color = LocalBaiyunPalette.current.separator

@Composable
fun bodyStyle(): TextStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
