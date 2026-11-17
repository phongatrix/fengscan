package com.example.omrscanner.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.PointF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Quản lý CameraX cho toàn bộ app.
 *
 * Trách nhiệm:
 *  - Tự dò camera sau CHÍNH (wide/primary), tránh ultra-wide (méo hình cầu)
 *    và telephoto (khó lấy góc) bằng heuristic tiêu cự + OIS + kích thước sensor.
 *  - Tự quét độ phân giải JPEG phần cứng hỗ trợ (StreamConfigurationMap) và
 *    chọn size 4:3 trong dải ~3–5MP (chi tiết bong bóng nét, không OOM).
 *  - Cấu hình CAF (CONTINUOUS_PICTURE) qua Camera2 interop.
 *  - focusOnArea(): phát lệnh AF/AE/AWB vào vùng phiếu khi phát hiện 4 chấm.
 *  - focusAndCapture(): pre-capture (khóa nét + đo sáng) rồi chụp sau delay.
 */
class CameraManagerHelper(private val context: Context) {

    /** Kết quả quét phần cứng camera. */
    data class CameraScan(
        val mainBackCameraId: String?,
        val jpegSize: Size?,
        val allJpegSizes: List<Size>,
    )

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var previewView: PreviewView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ------------------------------------------------------------------
    // 1. Tự động dò camera sau chính + độ phân giải tối ưu
    // ------------------------------------------------------------------

    fun scanMainBackCamera(): CameraScan = runCatching {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cands = mutableListOf<BackCam>()
        for (id in cm.cameraIdList) {
            val ch = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: continue
            val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val pixelArray = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
            cands += BackCam(
                id = id,
                focalMm = focal,
                sensorW = active?.width() ?: pixelArray?.width ?: 0,
                sensorH = active?.height() ?: pixelArray?.height ?: 0,
                ois = ois,
            )
        }
        if (cands.isEmpty()) return@runCatching CameraScan(null, null, emptyList())

        // Heuristic chọn "main rear wide":
        //  - Ultra-wide: tiêu cự < 3mm -> phạt nặng (méo hình cầu).
        //  - Main wide: 3.5–7.0mm (đỉnh quanh ~5.2mm) -> điểm cao.
        //  - Telephoto: > 8.5mm -> phạt theo độ dài tiêu cự.
        //  - Cộng thêm điểm cho OIS và sensor lớn.
        val best = cands.maxByOrNull { score(it) }!!
        Log.i(TAG, "Back cameras: ${cands.map { "${it.id} f=${it.focalMm}mm ${it.sensorW}x${it.sensorH} ois=${it.ois} score=${score(it)}" }}")

        // Quét độ phân giải JPEG hỗ trợ và chọn 4:3 trong dải 3–5.2MP
        // (mục tiêu ~4MP: nét từng bong bóng chì, vẫn an toàn bộ nhớ).
        val chars = runCatching { cm.getCameraCharacteristics(best.id) }.getOrNull()
        val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) as? StreamConfigurationMap
        val sizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
        Log.i(TAG, "Supported JPEG sizes: ${sizes.sortedByDescending { it.width * it.height }}")
        val chosen = pickBest43(sizes)
        Log.i(TAG, "Chosen camera=${best.id} jpegSize=$chosen")
        CameraScan(best.id, chosen, sizes.sortedByDescending { it.width * it.height })
    }.getOrDefault(CameraScan(null, null, emptyList()))

    private data class BackCam(
        val id: String,
        val focalMm: Float,
        val sensorW: Int,
        val sensorH: Int,
        val ois: Boolean,
    )

    private fun score(c: BackCam): Float {
        val focalScore = when {
            c.focalMm < 3.0f -> -3.0f                       // ultra-wide
            c.focalMm <= 3.5f -> -1.0f                      // gần vùng ultra-wide
            c.focalMm <= 7.0f -> 2.0f - abs(c.focalMm - 5.2f) / 5.0f  // main wide
            c.focalMm <= 8.5f -> 0.5f                       // 2x tele ngắn
            else -> -1.0f - (c.focalMm - 8.5f)              // tele dài
        }
        val sensorBonus = ((c.sensorW.toLong() * c.sensorH) / 4.0e7f).coerceIn(0f, 1.5f)
        return focalScore + sensorBonus + if (c.ois) 0.75f else 0f
    }

    private fun is43(s: Size): Boolean =
        abs(s.width.toDouble() / s.height - 4.0 / 3.0) < 0.03

    private fun pickBest43(sizes: List<Size>): Size? {
        if (sizes.isEmpty()) return null
        val fourThree = sizes.filter { is43(it) }
        val band = fourThree.filter {
            val px = it.width.toLong() * it.height
            px in 3_000_000..5_200_000
        }
        val pool = band.ifEmpty { fourThree.ifEmpty { sizes } }
        return pool.minByOrNull {
            abs(it.width.toLong() * it.height - TARGET_PIXELS)
        }
    }

    // ------------------------------------------------------------------
    // 2. Bind Preview + ImageAnalysis + ImageCapture
    // ------------------------------------------------------------------

    /**
     * Mở camera và bind 3 luồng: Preview, ImageAnalysis, ImageCapture.
     *
     * Cơ chế "**tự dò + fallback**": thử bind camera sau chính đã dò được
     * [CameraScan.mainBackCameraId]; nếu thiết bị này không mở được camera đó
     * (không hỗ trợ đủ 3 luồng đồng thời, camera bị k chiếm giữ,…) thì tự
     * chuyển sang [CameraSelector.DEFAULT_BACK_CAMERA] — CameraX sẽ chọn
     * camera sau hợp lệ và mở luôn. Hàm khắc phục tình trạng "bấm camera nhưng
     * màn hình đen / không bật được" trên một số dòng máy.
     */
    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        analyzer: ImageAnalysis.Analyzer,
        onBound: (CameraScan) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        this.previewView = previewView
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val scan = scanMainBackCamera()
                provider.unbindAll()

                // Thử lần lượt: camera đã dò (lấy chính xác) -> CameraX tự chọn sau chính.
                val attempts = buildList<Pair<String, CameraSelector>> {
                    scan.mainBackCameraId?.let { id ->
                        add("main-back:$id" to CameraSelector.Builder()
                            .addCameraFilter { infos ->
                                infos.filter { Camera2CameraInfo.from(it).cameraId == id }.ifEmpty { infos }
                            }.build())
                    }
                    add("default-back" to CameraSelector.DEFAULT_BACK_CAMERA)
                }
                var lastError: Throwable? = null
                for ((name, selector) in attempts) {
                    // Tạo mới các use-case cho mỗi lần thử (máy nào thất bại
                    // ở lần trước sẽ không bị dính trạng thái cũ).
                    val preview = buildPreview()
                    val analysis = buildAnalysis()
                    val capture = buildCapture(scan.jpegSize)
                    analysis.setAnalyzer(analysisExecutor, analyzer)
                    // Preview PHẢI được nối vào PreviewView trước khi bind, nếu
                    // không camera vẫn mở (bind thành công) nhưng khung hình
                    // đen vì không có Surface nào nhận dữ liệu.
                    preview.setSurfaceProvider(previewView.surfaceProvider)

                    try {
                        camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis, capture)
                        imageCapture = capture
                        Log.i(TAG, "camera opened via $name")
                        onBound(scan)
                        return@addListener
                    } catch (t: Throwable) {
                        Log.w(TAG, "bind failed via $name", t)
                        // Dọn trạng thái dở dang trước khi thử selector kế tiếp.
                        runCatching { provider.unbindAll() }
                        lastError = t
                    }
                }
                onError(lastError ?: IllegalStateException("Không thể mở camera"))
            } catch (t: Throwable) {
                Log.e(TAG, "bind failed", t)
                onError(t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun buildPreview(): Preview {
        return Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .build()
    }

    private fun buildAnalysis(): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(ANALYSIS_W, ANALYSIS_H),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        )
                    )
                    .build()
            )
            .build()
    }

    private fun buildCapture(jpegSize: Size?): ImageCapture {
        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(95)
        jpegSize?.let { size ->
            captureBuilder.setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            size,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
        }
        return captureBuilder.build()
    }

    fun unbind() {
        runCatching {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        }
        imageCapture = null
        camera = null
    }

    // ------------------------------------------------------------------
    // 3. Lấy nét vùng (3A: AF/AE/AWB) + pre-capture
    // ------------------------------------------------------------------

    /**
     * Phát lệnh lấy nét + đo sáng ĐA ĐIỂM toàn mặt phẳng phiếu
     * (tâm: AF+AE+AWB; các điểm phụ: AE+AWB để triệt tiêu mờ nhòe ở góc biên).
     * CameraX tự thực hiện chuỗi pre-capture (AE scan + AF scan).
     * Sau [autoCancelSeconds] thì trả về CAF như cũ.
     */
    fun focusOnArea(
        center: PointF,
        autoCancelSeconds: Long = 4L,
        extraPoints: List<PointF> = emptyList(),
    ) {
        val factory = previewView?.meteringPointFactory ?: return
        val action = FocusMeteringAction.Builder(
            factory.createPoint(center.x, center.y),
            FocusMeteringAction.FLAG_AF or
                FocusMeteringAction.FLAG_AE or
                FocusMeteringAction.FLAG_AWB,
        )
        // Giới hạn an toàn: tối đa 3 vùng đo sáng mỗi chế độ.
        for (p in extraPoints.take(2)) {
            action.addPoint(
                factory.createPoint(p.x, p.y),
                FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB,
            )
        }
        action.setAutoCancelDuration(autoCancelSeconds, TimeUnit.SECONDS)
        camera?.cameraControl?.startFocusAndMetering(action.build())
    }

    /**
     * Pre-capture: khóa nét/đo sáng đa điểm vào mặt phiếu, chờ [delayMs]
     * để AF/AE ổn định, sau đó chụp ảnh đầy đủ độ phân giải.
     */
    fun focusAndCapture(
        center: PointF?,
        extraPoints: List<PointF> = emptyList(),
        delayMs: Long = 600L,
        onCaptured: (jpegBytes: ByteArray, rotationDegrees: Int) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        if (center != null) focusOnArea(center, extraPoints = extraPoints)
        mainHandler.postDelayed({
            takePicture(onCaptured, onError)
        }, delayMs)
    }

    fun takePicture(
        onCaptured: (jpegBytes: ByteArray, rotationDegrees: Int) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val capture = imageCapture
        if (capture == null) {
            onError(IllegalStateException("ImageCapture chưa được bind"))
            return
        }
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()
                    onCaptured(bytes, rotation)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "takePicture failed", exception)
                    onError(exception)
                }
            }
        )
    }

    fun setTorch(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }

    companion object {
        private const val TAG = "CameraManagerHelper"
        private const val ANALYSIS_W = 640
        private const val ANALYSIS_H = 480
        private const val TARGET_PIXELS = 4_000_000L
    }
}
