package com.example.omrscanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.omrscanner.camera.CameraManagerHelper
import com.example.omrscanner.camera.FiducialDetectorAnalyzer
import com.example.omrscanner.camera.FiducialStage
import com.example.omrscanner.camera.FiducialState
import com.example.omrscanner.databinding.ActivityMainBinding
import com.example.omrscanner.imaging.ImageProcessor
import com.example.omrscanner.omr.SheetType
import com.example.omrscanner.omr.Templates
import com.example.omrscanner.omr.TemplateConfig
import com.example.omrscanner.storage.StorageHelper
import com.example.omrscanner.util.ImageProxyExt.decodeJpegRotated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Màn hình chính: camera + HUD căn chỉnh 4 chấm định vị + đếm ngược tự chụp
 * 3 giây (bật/tắt) khi phiếu khớp khung, sau đó chạy pipeline:
 * nắn phẳng -> tối ưu độ nét -> nén < 1MB -> lưu theo tên tùy biến.
 *
 * Ứng dụng KHÔNG đọc/phân tích nội dung phiếu (mã đề, SBD, P1/P2/P3, bubble).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val cameraHelper by lazy { CameraManagerHelper(this) }
    private lateinit var analyzer: FiducialDetectorAnalyzer
    private var toneGenerator: ToneGenerator? = null

    private var sheetType: SheetType = SheetType.MAU_12
    private var lastState: FiducialState? = null
    private var capturing = false
    private var torchOn = false
    private var autoCapture = true

    /**
     * Chống vòng lặp tự chụp: chỉ tự chụp khi phiếu **vừa được đưa vào khung**
     * (chuyển từ chưa-khớp -> khớp). Sau khi chụp/lưu xong, phiếu vẫn còn
     * trong khung nên `isAligned` vẫn true -> nếu không có cờ này thì app sẽ
     * đếm ngược rồi mở scanner lặp lại vô tận, không cho đổi phiếu.
     * Cờ được "nạp" lại khi HUD báo CHƯA khớp (người dùng nhấc/đổi phiếu).
     * Khởi tạo true để lần chụm phiếu ĐẦU TIÊN khi mở app cũng tự chụp.
     */
    private var autoArmed = true

    /**
     * Đang chờ kết quả từ scanner ngoài (Google Document Scanner). Trong lúc
     * chờ, Activity bị pause; onResume KHÔNG được hạ [capturing] vì scanner
     * vẫn đang mở -> tránh mở chồng 2 lần scan.
     */
    private var awaitingScan = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showPermissionDenied()
        }

    /** Android 9-: cần WRITE_EXTERNAL_STORAGE để lưu ảnh vào Pictures công khai. */
    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Launcher dùng chung cho flavor mlkit (Google Document Scanner). */
    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            captureEnhancer?.onScannerResult(
                result,
                onImage = { bytes ->
                    awaitingScan = false
                    handleScannedImage(bytes)
                },
                onError = { e ->
                    Log.e(TAG, "scanner error", e)
                    Toast.makeText(this, "Lỗi scanner: ${e.message}", Toast.LENGTH_LONG).show()
                    awaitingScan = false
                    capturing = false
                },
            )
        }

    private var captureEnhancer: com.example.omrscanner.scan.CaptureEnhancer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Khóa DỨNG bằng code nữa (chặn cảm biến xoay can thiệp vào camera):
        // manifest đã có screenOrientation="portrait", thêm 1 lớp chắn tại đây
        // phòng khi hệ thống/ROM tùy biến bỏ qua manifest (multi-window, foldable).
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.previewView.keepScreenOn = true
        // FILL_CENTER khớp với phép map tọa độ trong overlay (tránh lệch HUD).
        binding.previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        toneGenerator = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()

        // Cấu hình chọn ở màn TemplateSelectActivity
        sheetType = runCatching {
            SheetType.valueOf(StorageHelper.loadSheetType(this) ?: SheetType.MAU_12.name)
        }.getOrDefault(SheetType.MAU_12)
        autoCapture = StorageHelper.loadAutoCapture(this)
        binding.switchAutoCapture.isChecked = autoCapture
        binding.switchAutoCapture.setOnCheckedChangeListener { _, checked ->
            autoCapture = checked
            StorageHelper.saveAutoCapture(this, checked)
            if (!checked) binding.overlay.cancelCountdown()
            // Bật lại tự chụp khi phiếu đang nằm trong khung -> cho phép
            // chu kỳ chụp tiếp diễn ngay (không cần nhấc phiếu lên).
            if (checked) autoArmed = true
        }
        binding.radioSheetType.setOnCheckedChangeListener { _, checkedId ->
            applySheetType(if (checkedId == R.id.radioMau1011) SheetType.MAU_10_11 else SheetType.MAU_12)
            StorageHelper.saveSheetType(this, sheetType.name)
        }
        binding.radioSheetType.check(
            if (sheetType == SheetType.MAU_10_11) R.id.radioMau1011 else R.id.radioMau12,
        )
        applySheetType(sheetType)

        // Flavor hook: detector AI (tflite/dnn) + enhancer chụp (mlkit)
        captureEnhancer = FlavorDetectors.captureEnhancer(this)

        // Khung đỏ phải nằm giữa panel mẫu giấy (trên) và thanh điều khiển (dưới):
        // đo vị trí thật của 2 panel thay vì dùng hằng số, để không đè lên nhau
        // trên mọi kích thước màn hình / tai thỏ.
        binding.overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncOverlayInsets() }
        binding.topBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncOverlayInsets() }
        binding.bottomBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncOverlayInsets() }
        binding.root.post { syncOverlayInsets() }

        // Đếm ngược: bíp + rung nhẹ mỗi giây; về 0 -> khóa nét + chụp
        binding.overlay.onCountdownTick = {
            beep()
            vibrate(short = true)
        }
        binding.overlay.onCountdownFinished = {
            capture(auto = true)
        }

        binding.btnTorch.setOnClickListener {
            torchOn = !torchOn
            cameraHelper.setTorch(torchOn)
            binding.btnTorch.alpha = if (torchOn) 1f else 0.55f
        }
        binding.btnShutter.setOnClickListener { capture(auto = false) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Android 9 trở xuống: xin quyền ghi bộ nhớ ngoài để ảnh hiện trong Gallery
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /**
     * Trả quyền xoay về hệ thống khi RỜI app (về màn hình chính, sang app khác):
     * `requestedOrientation` đặt runtime có thể còn "bám" vào task trên một số
     * máy — reset ở [onPause] để xoay hoạt động lại như người dùng đã bật.
     * Lưu ý: `screenOrientation="portrait"` trong manifest chỉ ảnh hưởng các
     * màn hình của app khi đang hiển thị, KHÔNG đụng vào cài đặt hệ thống.
     */
    override fun onPause() {
        super.onPause()
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun onResume() {
        super.onResume()
        // Đang dùng app lại -> khóa dọc cho camera
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (awaitingScan) return // scanner ngoài vẫn đang mở — giữ capturing=true
        // quay lại từ màn kết quả -> sẵn sàng chấm phiếu tiếp theo
        capturing = false
    }

    override fun onDestroy() {
        super.onDestroy()
        toneGenerator?.release()
        toneGenerator = null
    }

    // ------------------------------------------------------------------

    /**
     * Áp cấu hình của mẫu đang chọn cho HUD:
     *  - khung đỏ theo tỷ lệ **toàn trang của ảnh kết quả** (bằng `outputAspect`);
     *  - 4 ô mục tiêu tại đúng vị trí 4 chấm của mẫu (`fiducialTarget`);
     *  - thumbnail hiển thị chính mẫu giấy đó.
     */
    private fun applySheetType(type: SheetType) {
        sheetType = type
        val tpl = Templates.forType(type)
        binding.overlay.sheetAspect = tpl.outputWidth.toFloat() / tpl.outputHeight
        binding.overlay.fiducialTarget = tpl.fiducialTarget
        if (this::analyzer.isInitialized) analyzer.templateBlock = tpl.fiducialTargetPagePx()
        binding.imgTemplate.setImageResource(
            if (type == SheetType.MAU_10_11) R.drawable.template_mau1011 else R.drawable.template_mau12,
        )
        binding.overlay.invalidate()
    }

    /**
     * Khung đỏ luôn nằm **dưới** panel mẫu giấy và **trên** thanh điều khiển.
     * Đo bằng `getLocationInWindow` để đúng cả khi có status bar / tai thỏ.
     */
    private fun syncOverlayInsets() {
        if (binding.overlay.height == 0) return
        val overlayLoc = IntArray(2)
        val topLoc = IntArray(2)
        val bottomLoc = IntArray(2)
        binding.overlay.getLocationInWindow(overlayLoc)
        binding.topBar.getLocationInWindow(topLoc)
        binding.bottomBar.getLocationInWindow(bottomLoc)

        val topInset = (topLoc[1] - overlayLoc[1] + binding.topBar.height).coerceAtLeast(0)
        val bottomInset = (overlayLoc[1] + binding.overlay.height -
            bottomLoc[1]).coerceAtLeast(0)

        if (topInset != binding.overlay.topInset || bottomInset != binding.overlay.bottomInset) {
            binding.overlay.topInset = topInset
            binding.overlay.bottomInset = bottomInset
            binding.overlay.invalidate()
        }
    }

    private fun startCamera() {
        binding.tvStatus.text = getString(R.string.status_starting)
        analyzer = FiducialDetectorAnalyzer(listener = { state -> onFiducialState(state) })
        analyzer.templateBlock = Templates.forType(sheetType).fiducialTargetPagePx()
        analyzer.aiDetector = FlavorDetectors.aiDetector(this)
        cameraHelper.bind(
            this,
            binding.previewView,
            analyzer,
            onBound = { scan ->
                val ver = runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull() ?: ""
                binding.tvStatus.text =
                    getString(
                        R.string.status_camera_ready,
                        scan.jpegSize?.width ?: 0, scan.jpegSize?.height ?: 0,
                    ) + "  •  v$ver"
            },
            onError = { t ->
                Log.e(TAG, "camera bind error", t)
                Toast.makeText(this, "Lỗi camera: ${t.message}", Toast.LENGTH_LONG).show()
            },
        )
        binding.overlay.analysisAspect = 0.75f // ImageAnalysis 4:3 xoay dọc
    }

    private fun onFiducialState(state: FiducialState) {
        lastState = state
        if (state.frameHeight > 0) {
            binding.overlay.analysisAspect = state.frameWidth.toFloat() / state.frameHeight
        }
        binding.overlay.updateState(state)
        binding.tvStatus.text = statusText(state)

        if (!binding.overlay.isAligned) {
            // Phiếu rời khung (nhấc lên/đổi phiếu) -> nạp lại cờ tự chụp
            autoArmed = true
            // Safety reset: khung bị lệch -> hủy đếm ngược, HUD về Đỏ/Vàng
            binding.overlay.cancelCountdown()
        } else if (autoCapture && autoArmed && !capturing &&
            !binding.overlay.isCountingDown
        ) {
            // Khung xanh LẦN ĐẦU sau khi phiếu vào khung -> đếm ngược 3s.
            // Tiêu thụ cờ ngay để không tự chụp lại khi phiếu vẫn nằm đó.
            autoArmed = false
            vibrate(short = true)
            binding.overlay.startCountdown(COUNTDOWN_SECONDS)
        }
    }

    /**
     * Thông điệp trạng thái hiển thị cho người dùng.
     *
     * Ưu tiên kết quả "khớp 4 ô mục tiêu" đúng như những gì nhìn thấy trên HUD;
     * góc nghiêng > 5° chỉ còn là gợi ý (không chặn chụp) vì cầm tay khó đạt.
     */
    private fun statusText(state: FiducialState): String = when {
        binding.overlay.isAligned -> "Đã căn chỉnh — giữ yên để tự động chụp"
        // Đã thấy 4 chấm nhưng chưa vào đúng 4 ô mục tiêu — đừng báo "Đã căn
        // chỉnh" (analyzer ALIGNED chỉ xét coverage/tilt, không xét vị trí ô).
        state.quadNormalized != null ->
            "Đã thấy 4 chấm — căn vào 4 ô nhỏ trong khung (${binding.overlay.matchedCount}/4)"
        state.stage == FiducialStage.TILTED ->
            "Đã thấy phiếu (nghiêng ${"%.0f".format(kotlin.math.abs(state.tiltDegrees))}°) — giữ máy song song mặt phiếu"
        state.stage == FiducialStage.TOO_FAR -> "Đưa camera lại gần phiếu"
        state.stage == FiducialStage.TOO_CLOSE -> "Đưa camera ra xa hơn"
        state.stage == FiducialStage.PARTIAL ->
            "${state.message} (${binding.overlay.matchedCount}/4 chấm)"
        state.stage == FiducialStage.NO_MARKERS ->
            "Chưa thấy chấm định vị — hạ thấp ánh sáng chói hoặc lau ống kính (vẫn bấm CHỤP được)"
        else -> state.message
    }

    private fun capture(auto: Boolean) {
        if (capturing) return
        binding.overlay.cancelCountdown()

        val detected = lastState?.quadNormalized
        val quad = detected ?: binding.overlay.fallbackQuadNormalized()

        // Flavor dnn/mlkit (returnsFinalImage): nút CHỤP **và** tự chụp sau 3s
        // đều mở Google Document Scanner (HÌNH 2 thay bước chụp ở HÌNH 1).
        // Flavor classic/tflite: giữ luồng CameraX như cũ.
        run {
            val enhancer = captureEnhancer
            if (enhancer != null && enhancer.isAvailable(this) &&
                (enhancer.returnsFinalImage() || !auto)
            ) {
                capturing = true
                awaitingScan = true
                binding.tvStatus.text = getString(R.string.status_processing)
                val launched = enhancer.startScan(
                    this,
                    scanLauncher,
                    onImage = { bytes ->
                        awaitingScan = false
                        handleScannedImage(bytes)
                    },
                    onError = { e ->
                        Log.e(TAG, "scanner error", e)
                        Toast.makeText(this, "Lỗi scanner: ${e.message}", Toast.LENGTH_LONG).show()
                        awaitingScan = false
                        capturing = false
                    },
                )
                if (launched) return
                // không launch được -> fallback CameraX bên dưới
                awaitingScan = false
                capturing = false
            }
        }

        if (detected == null && !auto) {
            Toast.makeText(this, R.string.toast_using_guide, Toast.LENGTH_SHORT).show()
        }
        capturing = true
        binding.tvStatus.text = getString(R.string.status_processing)

        // Lấy nét đa điểm toàn mặt phẳng phiếu: tâm (AF+AE+AWB) + 2 góc (AE+AWB)
        val center = PointF()
        binding.overlay.mapNormalizedPoint(
            (quad[0] + quad[2] + quad[4] + quad[6]) / 4f,
            (quad[1] + quad[3] + quad[5] + quad[7]) / 4f,
            center,
        )
        val cornerTopLeft = PointF()
        binding.overlay.mapNormalizedPoint(quad[0], quad[1], cornerTopLeft)
        val cornerBottomRight = PointF()
        binding.overlay.mapNormalizedPoint(quad[4], quad[5], cornerBottomRight)

        cameraHelper.focusAndCapture(
            center = center,
            extraPoints = listOf(cornerTopLeft, cornerBottomRight),
            delayMs = 600L,
            onCaptured = { bytes, rotation ->
                lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.Default) {
                        decodeJpegRotated(bytes, rotation)
                    }
                    processPipeline(bmp, detected)
                }
            },
            onError = { e ->
                Log.e(TAG, "capture error", e)
                Toast.makeText(this, "Lỗi chụp: ${e.message}", Toast.LENGTH_LONG).show()
                capturing = false
            },
        )
    }

    /**
     * Nhận ảnh từ scanner/pipeline:
     *  - flavor dnn/mlkit (HÌNH 3): ảnh Google trả về là **kết quả cuối** —
     *    lưu thẳng nguyên JPEG (không warp/enhance/nén), quay lại camera chụp
     *    phiếu kế tiếp, KHÔNG mở màn kết quả (bỏ bước ở HÌNH 4).
     *  - classic/tflite: decode và chạy pipeline cũ (warp → enhance → nén → Result).
     */
    private fun handleScannedImage(bytes: ByteArray) {
        if (captureEnhancer?.returnsFinalImage() == true) {
            capturing = true
            binding.tvStatus.text = getString(R.string.status_processing)
            val prefix = captureEnhancer?.filePrefix() ?: "OMR"
            lifecycleScope.launch(Dispatchers.IO) {
                val fileName = StorageHelper.makeFileName(prefix)
                val internal = runCatching {
                    StorageHelper.saveInternal(applicationContext, bytes, fileName)
                }.getOrNull()
                val gallery = internal?.let {
                    runCatching { StorageHelper.saveToGallery(applicationContext, bytes, fileName) }.getOrNull()
                }
                withContext(Dispatchers.Main) {
                    capturing = false
                    val msg = if (internal != null) {
                        "Đã lưu $fileName — sẵn sàng chụp phiếu kế tiếp"
                    } else {
                        "Lưu ảnh thất bại"
                    }
                    binding.tvStatus.text = msg
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    vibrate(short = true)
                }
            }
        } else {
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                processPipeline(bmp, lastState?.quadNormalized)
            }
        }
    }

    private suspend fun processPipeline(bitmap: Bitmap, quad: FloatArray?) {
        try {
            // 1) nắn ảnh về đúng khuôn mẫu. Quad ưu tiên: AI (nếu flavor có
            //    model) → dò cổ điển TRÊN ẢNH VỪA CHỤP (tránh lệch FOV giữa
            //    analysis và capture) → quad realtime → 4 tâm ô mục tiêu.
            val template = Templates.forType(sheetType)
            val capturedQuad = withContext(Dispatchers.Default) {
                runCatching {
                    ImageProcessor.refineQuadOnCapture(
                        bitmap,
                        quad,
                        template.fiducialTargetPagePx(),
                        ai = analyzer.aiDetector,
                    )
                }.getOrNull()
            } ?: quad ?: binding.overlay.fallbackQuadNormalized()
            val cropped = withContext(Dispatchers.Default) {
                ImageProcessor.warpToTemplate(bitmap, capturedQuad, template)
            }

            // 3) tối ưu: cân bằng trắng, chống bóng đổ, CLAHE, unsharp mask
            val enhanced = withContext(Dispatchers.Default) {
                ImageProcessor.enhanceForOmr(cropped)
            }

            // 4) nén < 1MB, đặt tên [Tiền tố]_[yyyyMMdd]_[HHmmss].jpg và lưu
            val prefix = StorageHelper.loadPrefix(this)
            val now = System.currentTimeMillis()
            val bytes = withContext(Dispatchers.Default) {
                ImageProcessor.compressImageUnder1MB(enhanced)
            }
            val fileName = StorageHelper.makeFileName(prefix, now = now)
            val internalFile = StorageHelper.saveInternal(this, bytes, fileName)
            val galleryUri = StorageHelper.saveToGallery(this, bytes, fileName)

            bitmap.recycle()
            if (cropped !== bitmap) cropped.recycle()
            if (enhanced !== cropped && enhanced !== bitmap) enhanced.recycle()

            startActivity(Intent(this, ResultActivity::class.java).apply {
                putExtra(ResultActivity.EXTRA_PATH, internalFile.absolutePath)
                putExtra(ResultActivity.EXTRA_FILE_NAME, fileName)
                putExtra(ResultActivity.EXTRA_GALLERY_SAVED, galleryUri != null)
                putExtra(ResultActivity.EXTRA_SHEET_TYPE, sheetType.name)
            })
        } catch (t: Throwable) {
            Log.e(TAG, "pipeline failed", t)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Lỗi xử lý: ${t.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            capturing = false
        }
    }

    // ------------------------------------------------------------------

    /** Âm "bíp" ngắn mỗi giây khi đếm ngược. */
    private fun beep() {
        runCatching { toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) }
    }

    private fun vibrate(short: Boolean) {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        val duration = if (short) 30L else 40L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    private fun showPermissionDenied() {
        binding.tvStatus.text = getString(R.string.status_permission_denied)
        Toast.makeText(this, R.string.status_permission_denied, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "MainActivity"

        /** Số giây đếm ngược tự chụp (theo tài liệu thiết kế: 3 giây). */
        const val COUNTDOWN_SECONDS = 3
    }
}
