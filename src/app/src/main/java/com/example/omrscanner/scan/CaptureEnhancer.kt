package com.example.omrscanner.scan

import android.app.Activity
import android.content.Context
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest

/**
 * Nâng cấp luồng CHỤP theo flavor:
 *  - `mlkit` + `dnn`: nút CHỤP (và tự chụp sau 3s) mở **Google ML Kit Document
 *    Scanner**; ảnh Google trả về là **kết quả cuối cùng** — lưu thẳng, quay lại
 *    camera chụp phiếu kế tiếp, bỏ qua warp/enhance/nén/màn kết quả
 *    ([returnsFinalImage] = true).
 *  - `classic` / `tflite`: không có enhancer (null) — giữ luồng CameraX cũ.
 */
interface CaptureEnhancer {

    /** Có dùng được trên máy này không (Play services, model đã tải…). */
    fun isAvailable(context: Context): Boolean

    /**
     * Bắt đầu luồng quét thay cho `takePicture`.
     * @return true nếu đã launch (kết quả về qua [onScannerResult]);
     *         false nếu không khả dụng — caller fallback CameraX.
     */
    fun startScan(
        activity: Activity,
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        onImage: (ByteArray) -> Unit,
        onError: (Throwable) -> Unit,
    ): Boolean

    /** Nhận kết quả từ launcher đã đăng ký trong MainActivity. */
    fun onScannerResult(
        result: ActivityResult,
        onImage: (ByteArray) -> Unit,
        onError: (Throwable) -> Unit,
    )

    /**
     * true = ảnh scanner là **kết quả cuối** (JPEG gốc của Google): lưu thẳng,
     * không warp/enhance/nén, không mở màn kết quả. false = ảnh trả về để
     * chạy tiếp pipeline cũ của app.
     */
    fun returnsFinalImage(): Boolean = false

    /**
     * Tiền tố tên file lưu khi quét (mặc định "OMR"; flavor FengScan dùng "FS").
     */
    fun filePrefix(): String = "OMR"
}
