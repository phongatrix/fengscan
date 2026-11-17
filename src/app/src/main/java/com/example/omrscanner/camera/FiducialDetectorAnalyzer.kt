package com.example.omrscanner.camera

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.omrscanner.util.ImageProxyExt.toGrayMat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import org.opencv.core.Mat

/** Trạng thái căn chỉnh phiếu dùng để đổi màu HUD. */
enum class FiducialStage {
    NO_MARKERS,   // chưa thấy chấm nào
    PARTIAL,      // thấy chấm nhưng chưa ghép được tứ giác hợp lệ
    TOO_FAR,      // phiếu quá nhỏ trong khung
    TOO_CLOSE,    // phiếu tràn khung
    TILTED,       // góc nghiêng > ngưỡng
    ALIGNED,      // đủ 4 chấm, góc OK -> sẵn sàng auto-capture
}

/**
 * Kết quả phân tích một khung hình (tọa độ đã chuẩn hóa 0..1 theo khung
 * phân tích đã xoay về hướng màn hình).
 */
data class FiducialState(
    val stage: FiducialStage,
    /** [tlx,tly, trx,try, brx,bry, blx,bly] — tâm 4 chấm góc, hoặc null. */
    val quadNormalized: FloatArray?,
    val tiltDegrees: Float,
    /** Diện tích tứ giác / diện tích khung hình. */
    val coverage: Float,
    val message: String,
    val timestampMs: Long,
    /** Kích thước khung phân tích (đã xoay về hướng màn hình). */
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = timestampMs.hashCode()
}

/**
 * ImageAnalysis.Analyzer phát hiện 4 chấm đen định vị (fiducial markers)
 * ở 4 góc phiếu trả lời, theo thời gian thực.
 *
 * Pipeline dò chấm đặt trong [QuadFinder] (dùng chung với ảnh JPEG đã chụp)
 * để HUD và ảnh kết quả luôn cùng một logic.
 */
class FiducialDetectorAnalyzer(
    private val listener: (FiducialState) -> Unit,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : ImageAnalysis.Analyzer {

    private val busy = AtomicBoolean(false)

    /**
     * Hình học khối 4 chấm của mẫu đang chọn, ở **hệ pixel trang** (đẳng hướng)
     * (`TemplateConfig.fiducialTargetPagePx()`) — dùng để ghép góc theo hình
     * học mẫu thay vì quadrant thuần. Được MainActivity cập nhật khi đổi mẫu.
     */
    @Volatile
    var templateBlock: FloatArray? = null

    /**
     * Detector AI (tùy flavor — null với classic/mlkit). Quad của AI được
     * ưu tiên nếu qua gate hình học của QuadFinder, không thì rơi về pipeline
     * cổ điển.
     */
    @Volatile
    var aiDetector: com.example.omrscanner.ai.AiDotDetector? = null

    override fun analyze(image: ImageProxy) {
        // Bỏ qua khung mới nếu khung trước chưa xử lý xong (giữ HUD mượt).
        if (!busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        val state = try {
            val gray = image.toGrayMat(displayOriented = true)
            try {
                detect(gray)
            } finally {
                gray.release()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "analyze failed", t)
            FiducialState(
                FiducialStage.NO_MARKERS, null, 0f, 0f,
                "Lỗi xử lý khung hình", SystemClock.uptimeMillis(),
            )
        } finally {
            image.close()
        }
        mainHandler.post { listener(state) }
        busy.set(false)
    }

    // ------------------------------------------------------------------

    private fun detect(gray: Mat): FiducialState {
        val w = gray.cols()
        val h = gray.rows()
        val now = SystemClock.uptimeMillis()

        val aiQuad = aiDetector?.let { runCatching { it.detectQuad(gray) }.getOrNull() }
        return when (val outcome = QuadFinder.find(gray, templateBlock, aiQuad)) {
            QuadFinder.Outcome.NoMarkers -> FiducialState(
                FiducialStage.NO_MARKERS, null, 0f, 0f,
                "Đặt phiếu trả lời vào khung ngắm", now, w, h,
            )

            QuadFinder.Outcome.Incomplete -> FiducialState(
                FiducialStage.PARTIAL, null, 0f, 0f,
                "Căn chỉnh đủ 4 chấm định vị ở 4 góc phiếu", now, w, h,
            )

            is QuadFinder.Outcome.Found -> {
                val stage = when {
                    outcome.coverage < 0.10f -> FiducialStage.TOO_FAR
                    outcome.coverage > 0.85f -> FiducialStage.TOO_CLOSE
                    abs(outcome.tiltDegrees) > TILT_THRESHOLD_DEG -> FiducialStage.TILTED
                    else -> FiducialStage.ALIGNED
                }
                val message = when (stage) {
                    FiducialStage.TOO_FAR -> "Đưa camera lại gần phiếu"
                    FiducialStage.TOO_CLOSE -> "Đưa camera ra xa hơn"
                    FiducialStage.TILTED ->
                        "Giữ camera thẳng góc với phiếu (lệch %.1f°)".format(abs(outcome.tiltDegrees))
                    else -> "Đã căn chỉnh — giữ yên để tự động chụp"
                }
                FiducialState(
                    stage, outcome.quad, outcome.tiltDegrees, outcome.coverage,
                    message, now, outcome.frameWidth, outcome.frameHeight,
                )
            }
        }
    }

    companion object {
        private const val TAG = "FiducialDetector"

        /** Đề bài: góc nghiêng < 5° mới coi là chuẩn (chỉ là gợi ý hiển thị). */
        const val TILT_THRESHOLD_DEG = 5f
    }
}
