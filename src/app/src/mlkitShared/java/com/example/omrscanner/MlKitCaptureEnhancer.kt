package com.example.omrscanner

import android.app.Activity
import android.content.Context
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import com.example.omrscanner.ai.AiDotDetector
import com.example.omrscanner.scan.CaptureEnhancer
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Flavor **mlkit** — nút CHỤP mở **Google ML Kit Document Scanner** (AI dò mép
 * trang + nắn phẳng của Google Play Services), ảnh trả về tiếp tục chạy
 * pipeline warp theo 4 chấm của app. Dò realtime trên HUD vẫn dùng pipeline
 * cổ điển (ML Kit không có API dò chấm realtime).
 */
class MlKitCaptureEnhancer(
    private val context: Context,
    private val prefix: String = "OMR",
) : CaptureEnhancer {

    /** Tiền tố tên file lưu: "FS" cho FengScan (dnn), "OMR" cho mlkit. */
    override fun filePrefix(): String = prefix

    override fun isAvailable(ctx: Context): Boolean = runCatching {
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    override fun startScan(
        activity: Activity,
        launcher: ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>,
        onImage: (ByteArray) -> Unit,
        onError: (Throwable) -> Unit,
    ): Boolean {
        if (!isAvailable(activity)) return false
        return runCatching {
            val options = GmsDocumentScannerOptions.Builder()
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setGalleryImportAllowed(false)
                .setPageLimit(1)
                .build()
            GmsDocumentScanning.getClient(options)
                .getStartScanIntent(activity)
                .addOnSuccessListener { sender ->
                    // launch() nằm NGOÀI runCatching phía trên — phải tự bắt lỗi:
                    // nếu activity chưa RESUMED, ActivityResultRegistry ném
                    // IllegalStateException và không bắt sẽ CRASH app.
                    try {
                        launcher.launch(
                            androidx.activity.result.IntentSenderRequest.Builder(sender).build(),
                        )
                    } catch (t: Throwable) {
                        onError(t)
                    }
                }
                .addOnFailureListener { e -> onError(e) }
            true
        }.getOrDefault(false)
    }

    override fun onScannerResult(
        result: ActivityResult,
        onImage: (ByteArray) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            onError(IllegalStateException("ML Kit scanner đã bị huỷ"))
            return
        }
        runCatching {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(data)
            val uri = scanResult?.pages?.firstOrNull()?.imageUri
                ?: throw IllegalStateException("ML Kit không trả về trang nào")
            // Đọc nguyên JPEG gốc của ML Kit (đã qua Enhance của Google nếu
            // người dùng chọn) — KHÔNG decode lại, giữ nguyên chất lượng.
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Không đọc được ảnh scan")
            onImage(bytes)
        }.onFailure { onError(it) }
    }

    /** Ảnh scanner là kết quả cuối — lưu thẳng, bỏ qua warp/enhance/Result. */
    override fun returnsFinalImage(): Boolean = true
}
