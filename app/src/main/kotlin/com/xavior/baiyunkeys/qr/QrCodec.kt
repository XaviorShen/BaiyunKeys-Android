package com.xavior.baiyunkeys.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 二维码生成（ZXing core，GMS-free）。
 *
 * 与 iOS `QRCode.swift` 对齐：EC 级别 M、留 1 格白边、内容就是导出文本。
 * 扫码在下一片用 CameraX + ZXing 做，这里先把「另一台手机能扫」这条路打通。
 */
object QrCodec {

    fun encode(text: String, sizePx: Int = 720, margin: Int = 1): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to margin,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val rowStart = y * sizePx
            for (x in 0 until sizePx) {
                pixels[rowStart + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }
}
