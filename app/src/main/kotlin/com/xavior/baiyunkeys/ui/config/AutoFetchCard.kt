package com.xavior.baiyunkeys.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baiyunkeys.core.settings.AppSettings
import com.baiyunkeys.core.settings.FetchMethod
import com.xavior.baiyunkeys.ui.components.BaiyunCard
import com.xavior.baiyunkeys.ui.components.CardHeader
import com.xavior.baiyunkeys.ui.components.FilledButton
import com.xavior.baiyunkeys.ui.components.FlatField
import com.xavior.baiyunkeys.ui.components.QuietButton
import com.xavior.baiyunkeys.ui.theme.LocalBaiyunPalette

/**
 * 「自动获取配置」卡片，对应 iOS `ConfigView.autoFetchCard`。
 *
 * 三种通道（身份证 / OpenID / OpenID+SM2）共用一张表：切换方式只换下面那几个输入框。
 */
@Composable
fun AutoFetchCard(
    settings: AppSettings,
    busy: Boolean,
    result: AutoFetchResult?,
    onSettingsChange: (AppSettings) -> Unit,
    onFetch: (phone: String, idCard: String) -> Unit,
    onGenerateSm2: () -> Unit,
) {
    val palette = LocalBaiyunPalette.current
    var phone by remember { mutableStateOf("") }
    var idCard by remember { mutableStateOf("") }

    val canFetch = when (settings.fetchMethod) {
        // 加密版不传 xx 字段服务器就不加密下发，等效 openid 方式，用不上（与 iOS 一致置灰）
        FetchMethod.OpenIdSm2 -> false
        FetchMethod.IdCard -> phone.length >= 11 && idCard.length >= 15
        FetchMethod.OpenId -> phone.length >= 11 && settings.openId.length >= 8
    }

    BaiyunCard {
        CardHeader(title = "自动获取配置", subtitle = "从官方拉取已授权门禁")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FetchMethod.entries.forEach { method ->
                FilterChip(
                    selected = settings.fetchMethod == method,
                    onClick = { onSettingsChange(settings.copy(fetchMethod = method)) },
                    label = { Text(method.label.substringBefore("（"), fontSize = 11.sp) },
                )
            }
        }

        FlatField(
            title = "手机号",
            value = phone,
            onValueChange = { phone = it },
            placeholder = "请输入绑定的手机号",
            keyboardType = KeyboardType.Phone,
        )

        when (settings.fetchMethod) {
            FetchMethod.IdCard -> {
                FlatField(
                    title = "身份证号",
                    value = idCard,
                    onValueChange = { idCard = it },
                    placeholder = "请输入身份证号码",
                    hint = "会直接发给官方服务器换取门禁参数，不经过第三方",
                )
            }

            FetchMethod.OpenId, FetchMethod.OpenIdSm2 -> {
                FlatField(
                    title = "微信 OpenID",
                    value = settings.openId,
                    onValueChange = { onSettingsChange(settings.copy(openId = it)) },
                    placeholder = "请输入本人的微信 OpenID",
                    hint = "本机会记住，下次免重填",
                )
            }
        }

        if (settings.fetchMethod == FetchMethod.OpenIdSm2) {
            FlatField(
                title = "SM2 公钥",
                value = settings.smPublicKey,
                onValueChange = { onSettingsChange(settings.copy(smPublicKey = it)) },
                placeholder = "04 开头，130 字符 hex",
                monospaced = true,
            )
            FlatField(
                title = "SM2 私钥",
                value = settings.smPrivateKey,
                onValueChange = { onSettingsChange(settings.copy(smPrivateKey = it)) },
                placeholder = "64 字符 hex",
                hint = "私钥等同实体钥匙，只存在本机",
                monospaced = true,
            )
            QuietButton(title = "生成一对 SM2 密钥", onClick = onGenerateSm2, tint = palette.info)
            Text(
                "把公钥告诉服务器，服务器加密下发 productKey，本机用私钥解。公钥换新后需要重新获取。",
                color = palette.textTertiary,
                fontSize = 11.sp,
            )
        }

        FilledButton(
            title = if (busy) "获取中…" else "获取配置",
            onClick = { onFetch(phone, idCard) },
            enabled = canFetch && !busy,
            modifier = Modifier.fillMaxWidth(),
            tint = palette.info,
        )

        result?.let {
            Text(
                text = it.message,
                color = if (it.isError) palette.danger else palette.brand,
                fontSize = 12.sp,
            )
        }
    }
}

/** 自动获取的结果反馈（成功条数 / 失败原因）。 */
data class AutoFetchResult(val message: String, val isError: Boolean)
