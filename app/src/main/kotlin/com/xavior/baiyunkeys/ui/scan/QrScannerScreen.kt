package com.xavior.baiyunkeys.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.xavior.baiyunkeys.ui.components.QuietButton
import com.xavior.baiyunkeys.ui.theme.BaiyunDimens
import com.xavior.baiyunkeys.ui.theme.LocalBaiyunPalette
import java.util.concurrent.Executors

/**
 * 二维码扫描（CameraX + ZXing，GMS-free）。
 *
 * 对应 iOS `QRScannerView`（VisionKit）。扫到的文本交给上层走 `DoorTextParser`，
 * 所以二维码内容与「文本导入」完全同构，iOS 生成的本机也能读。
 */
@Composable
fun QrScannerScreen(
    onResult: (String) -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalBaiyunPalette.current
    val context = LocalContext.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var error by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = ok }

    LaunchedEffect(granted) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(BaiyunDimens.pagePadding.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QuietButton(title = "← 返回", onClick = onBack)
        Text("扫描二维码", color = palette.textPrimary, fontSize = 22.sp)

        if (!granted) {
            Text(
                "需要相机权限才能扫码。也可以回到「传输 → 文本」直接粘贴。",
                color = palette.textSecondary,
                fontSize = 12.sp,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .background(Color.Black, RoundedCornerShape(BaiyunDimens.cardRadius.dp)),
                contentAlignment = Alignment.Center,
            ) {
                CameraPreview(
                    modifier = Modifier.fillMaxSize(),
                    onDecoded = { text -> onResult(text) },
                    onError = { error = it },
                )
                // 取景框
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .border(2.dp, palette.brand, RoundedCornerShape(16.dp)),
                )
            }
            error?.let { Text(it, color = palette.danger, fontSize = 12.sp) }
            Text(
                "对准另一台设备「传输 → 二维码」里的画面",
                color = palette.textTertiary,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun CameraPreview(
    modifier: Modifier,
    onDecoded: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
        }
    }
    val handled = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            handled.set(true)
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                runCatching {
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { imageAnalysis ->
                            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                val text = decodeQr(imageProxy, reader)
                                imageProxy.close()
                                if (text != null && handled.compareAndSet(false, true)) {
                                    previewView.post { onDecoded(text) }
                                }
                            }
                        }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }.onFailure { onError("相机启动失败：${it.message}") }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
    )
}

/** 从 `ImageProxy` 的 Y 平面直接喂 ZXing，不做 Bitmap 转换。 */
private fun decodeQr(image: ImageProxy, reader: MultiFormatReader): String? = runCatching {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val source = PlanarYUVLuminanceSource(
        bytes,
        image.width,
        image.height,
        0,
        0,
        image.width,
        image.height,
        false,
    )
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    reader.decodeWithState(bitmap).text
}.getOrNull()
