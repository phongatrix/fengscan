package com.example.omrscanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.example.omrscanner.omr.SheetType
import com.example.omrscanner.scan.CaptureEnhancer
import com.example.omrscanner.storage.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Màn hình quét **không qua camera HUD** của app (flavor `dnn` + `mlkit`).
 *
 * Luồng theo yêu cầu: bỏ bước khung ngắm/HUD, vào **thẳng Google ML Kit
 * Document Scanner**. Mỗi lần người dùng bấm Next:
 *   → lưu nguyên JPEG Google trả về (Pictures/OMR_Results) → tự mở lại
 *     scanner để chụp phiếu kế tiếp.
 * Bấm Back/huỷ trong scanner → dừng vòng lặp, hiện màn này để bấm QUÉT lại
 * hoặc quay về chọn mẫu.
 *
 * Chỉ tồn tại ở 2 flavor có `CaptureEnhancer.returnsFinalImage() == true`.
 */
class ScannerOnlyActivity : AppCompatActivity() {

    private lateinit var enhancer: CaptureEnhancer
    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var btnScan: Button

    private var savedCount = 0
    private var scanInFlight = false

    /** Đã lưu xong nhưng activity chưa RESUMED -> onResume sẽ mở scanner tiếp. */
    private var pendingRelaunch = false

    /** Lần quét đầu tiên khi vào màn — chờ tới onResume mới launch. */
    private var firstScanPending = true
    private var sheetType: SheetType = SheetType.MAU_12

    private val scanLauncher: ActivityResultLauncher<IntentSenderRequest> =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            onScanResult(result)
        }

    /** Android 9-: cần WRITE_EXTERNAL_STORAGE để lưu ảnh vào Pictures công khai. */
    private val storagePermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        sheetType = runCatching {
            SheetType.valueOf(StorageHelper.loadSheetType(this) ?: SheetType.MAU_12.name)
        }.getOrDefault(SheetType.MAU_12)

        val enc = FlavorDetectors.captureEnhancer(this)
        if (enc == null || !enc.isAvailable(this)) {
            // Máy không có Google Play Services -> quay về luồng camera cũ.
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        enhancer = enc

        // Android 9 trở xuống: xin quyền ghi bộ nhớ ngoài để ảnh hiện trong Gallery
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        setContentView(buildUi())
        // Vào thẳng scanner, không hiện màn HUD camera.
        // Lần quét đầu được kích hoạt trong onResume() (đã RESUMED) — nếu gọi
        // sớm hơn, launch IntentSender có thể bị từ chối.
    }

    // ------------------------------------------------------------------
    // UI tối giản (dựng bằng code — không cần layout riêng cho flavor)
    // ------------------------------------------------------------------

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#111418"))
            setPadding(dp(24), dp(40), dp(24), dp(32))
        }

        root.addView(TextView(this).apply {
            text = "Feng Scanner v1.0 — Quét phiếu"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "Mẫu: ${sheetType.label}"
            setTextColor(Color.parseColor("#9AA4B2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(20))
        })

        tvStatus = TextView(this).apply {
            text = "Đang mở Google Scanner…"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(tvStatus)

        tvCount = TextView(this).apply {
            text = "Đã lưu: 0 phiếu"
            setTextColor(Color.parseColor("#00E676"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(28))
        }
        root.addView(tvCount)

        btnScan = Button(this).apply {
            text = "QUÉT PHIẾU"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnClickListener { startScan() }
            setPadding(dp(28), dp(12), dp(28), dp(12))
        }
        root.addView(btnScan)

        root.addView(Button(this).apply {
            text = "Chọn mẫu khác"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setOnClickListener {
                startActivity(Intent(this@ScannerOnlyActivity, TemplateSelectActivity::class.java))
                finish()
            }
        })

        return root
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // Vòng lặp quét
    // ------------------------------------------------------------------

    private fun startScan() {
        if (scanInFlight) return
        scanInFlight = true
        tvStatus.text = "Đang mở Google Scanner…"
        btnScan.isEnabled = false
        val launched = enhancer.startScan(
            this,
            scanLauncher,
            onImage = { bytes -> onScanSuccess(bytes) },
            onError = { e ->
                Log.e(TAG, "scanner error", e)
                scanInFlight = false
                btnScan.isEnabled = true
                tvStatus.text = "Lỗi scanner: ${e.message}\nBấm QUÉT PHIẾU để thử lại."
            },
        )
        if (!launched) {
            scanInFlight = false
            btnScan.isEnabled = true
            tvStatus.text = "Không mở được Google Scanner trên máy này."
        }
    }

    private fun onScanResult(result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            // Người dùng bấm Back/huỷ trong scanner -> dừng vòng lặp, không tự mở lại.
            scanInFlight = false
            btnScan.isEnabled = true
            tvStatus.text = "Đã dừng. Bấm QUÉT PHIẾU để tiếp tục."
            return
        }
        enhancer.onScannerResult(
            result,
            onImage = { bytes -> onScanSuccess(bytes) },
            onError = { e ->
                Log.e(TAG, "scanner result error", e)
                scanInFlight = false
                btnScan.isEnabled = true
                tvStatus.text = "Lỗi đọc ảnh: ${e.message}"
            },
        )
    }

    /**
     * Lưu nguyên JPEG Google trả về, rồi tự mở scanner cho phiếu kế tiếp.
     * Lỗi lưu (bộ nhớ đầy…) phải DỪNG vòng lặp và báo rõ — không được đếm
     * thành "Đã lưu" rồi quét tiếp làm mất ảnh người dùng.
     *
     * Relaunch chỉ thực hiện khi activity đã RESUMED: onActivityResult được
     * gọi TRƯỚC onResume, nếu lưu quá nhanh rồi startScan() ngay khi chưa
     * resumed thì launch có thể ném lỗi (xem MlKitCaptureEnhancer fix 1).
     */
    private fun onScanSuccess(bytes: ByteArray) {
        lifecycleScope.launch {
            val fileName = withContext(Dispatchers.IO) {
                val name = StorageHelper.makeFileName(enhancer.filePrefix())
                runCatching {
                    StorageHelper.saveInternal(this@ScannerOnlyActivity, bytes, name)
                    StorageHelper.saveToGallery(this@ScannerOnlyActivity, bytes, name)
                    name
                }.getOrNull()
            }
            scanInFlight = false
            if (fileName == null) {
                btnScan.isEnabled = true
                tvStatus.text = "Lưu ảnh THẤT BẠI (bộ nhớ đầy?).\nBấm QUÉT PHIẾU để thử lại."
                return@launch
            }
            savedCount++
            tvCount.text = "Đã lưu: $savedCount phiếu"
            tvStatus.text = "Đã lưu $fileName\nĐang mở scanner cho phiếu kế tiếp…"
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                delay(NEXT_SCAN_DELAY_MS)
                if (!isFinishing && !isDestroyed) startScan()
            } else {
                // Chưa resumed — chờ onResume() sẽ tự mở lại scanner.
                pendingRelaunch = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Khóa dọc khi đang dùng app (nhất quán với MainActivity)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        when {
            firstScanPending -> { firstScanPending = false; startScan() }
            pendingRelaunch -> { pendingRelaunch = false; startScan() }
        }
    }

    override fun onPause() {
        super.onPause()
        // Trả quyền xoay khi rời app — không để khóa runtime "bám" vào task
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun onDestroy() {
        super.onDestroy()
        scanInFlight = false
    }

    companion object {
        private const val TAG = "ScannerOnlyActivity"
        private const val NEXT_SCAN_DELAY_MS = 600L

        /** Dùng cho TemplateSelectActivity: mở thẳng màn quét (không HUD). */
        fun intent(context: Context): Intent =
            Intent(context, ScannerOnlyActivity::class.java)
    }
}
