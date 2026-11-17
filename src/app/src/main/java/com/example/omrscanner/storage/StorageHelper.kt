package com.example.omrscanner.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Quản lý lưu trữ & đặt tên ảnh chụp:
 *  - Tên file sinh tự động: `[Tiền tố]_[yyyyMMdd]_[HHmmss].jpg`
 *    (tiền tố trống -> mặc định "OMR"; ký tự không hợp lệ được thay bằng '_').
 *  - Android 10+ (Scoped Storage): ghi qua MediaStore vào
 *    `Pictures/OMR_Results/` — hiện trong Thư viện, không cần quyền storage.
 *  - Android 9 trở xuống: ghi vào thư mục riêng của app
 *    `Android/data/<pkg>/files/Pictures/OMR_Results/` (không cần quyền).
 */
object StorageHelper {

    private const val TAG = "StorageHelper"
    private const val PREFS = "omr_settings"
    /** Thư mục đích: Documents/FengScan (theo yêu cầu FengScan). */
    private const val GALLERY_DIR = "FengScan"

    private const val KEY_PREFIX = "prefix"
    private const val KEY_SHEET_TYPE = "sheet_type"
    private const val KEY_AUTO_CAPTURE = "auto_capture"

    // ------------------------------------------------------------------
    // Đặt tên file
    // ------------------------------------------------------------------

    /** `[Tiền tố]_[yyyyMMdd]_[HHmmss].jpg`, ví dụ `Toan12A1_20261112_083015.jpg`. */
    fun makeFileName(
        prefix: String,
        suffix: String = "",
        now: Long = System.currentTimeMillis(),
    ): String {
        val safePrefix = prefix.trim()
            .ifEmpty { "OMR" }
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(40)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        return "${safePrefix}_$timestamp$suffix.jpg"
    }

    // ------------------------------------------------------------------
    // Ghi ảnh
    // ------------------------------------------------------------------

    /**
     * Ghi JPEG vào **`Documents/FengScan/`**:
     *  - Android 10+ (Q+): qua MediaStore RELATIVE_PATH = Documents/FengScan.
     *  - Android 9- (≤P): ghi trực tiếp vào /sdcard/Documents/FengScan
     *    (cần WRITE_EXTERNAL_STORAGE) + MediaScannerConnection để hiện trong
     *    ứng dụng quản lý file/Gallery; fallback thư mục riêng của app.
     * @return Uri của MediaStore nếu ghi qua MediaStore, ngược lại null.
     */
    fun saveToGallery(context: Context, bytes: ByteArray, fileName: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + GALLERY_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            try {
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
                uri
            } catch (t: Throwable) {
                Log.e(TAG, "saveToGallery failed: $fileName", t)
                null
            }
        } else {
            // Android 9 (Q-): ghi vào thư mục công khai Pictures/OMR_Results
            // (cần quyền WRITE_EXTERNAL_STORAGE) và báo MediaScanner để ảnh
            // hiện ngay trong Thư viện. Nếu chưa cấp quyền/ghi lỗi -> fallback
            // thư mục riêng của app (không hiện trong Gallery).
            val publicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                GALLERY_DIR,
            ).apply { mkdirs() }
            try {
                val f = File(publicDir, fileName)
                f.outputStream().use { it.write(bytes) }
                MediaScannerConnection.scanFile(
                    context, arrayOf(f.absolutePath), arrayOf("image/jpeg"), null,
                )
                return Uri.fromFile(f)
            } catch (t: Throwable) {
                Log.e(TAG, "saveToGallery(pre-Q) failed: $fileName", t)
                val appDir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), GALLERY_DIR,
                ).apply { mkdirs() }
                try {
                    File(appDir, fileName).outputStream().use { it.write(bytes) }
                } catch (t2: Throwable) {
                    Log.e(TAG, "saveToGallery(app dir) failed: $fileName", t2)
                }
                null
            }
        }
    }

    /** Ghi file nội bộ (bản hiển thị nhanh trên màn kết quả). */
    fun saveInternal(context: Context, bytes: ByteArray, fileName: String): File {
        val dir = File(context.filesDir, "omr_results").apply { mkdirs() }
        val file = File(dir, fileName)
        file.outputStream().use { it.write(bytes) }
        return file
    }

    // ------------------------------------------------------------------
    // Cài đặt
    // ------------------------------------------------------------------

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Tiền tố tên ảnh; trống -> "OMR". */
    fun loadPrefix(context: Context): String = prefs(context).getString(KEY_PREFIX, "") ?: ""

    fun savePrefix(context: Context, prefix: String) {
        prefs(context).edit().putString(KEY_PREFIX, prefix).apply()
    }

    /** Có lưu thêm ảnh gốc đã nắn phẳng (`_raw.jpg`) hay không. */
    fun loadSheetType(context: Context): String? = prefs(context).getString(KEY_SHEET_TYPE, null)

    fun saveSheetType(context: Context, typeName: String) {
        prefs(context).edit().putString(KEY_SHEET_TYPE, typeName).apply()
    }

    /** Bật/tắt tự chụp sau 3 giây đếm ngược. */
    fun loadAutoCapture(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_CAPTURE, true)

    fun saveAutoCapture(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CAPTURE, enabled).apply()
    }
}
