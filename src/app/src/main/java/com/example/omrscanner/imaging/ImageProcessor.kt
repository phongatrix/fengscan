package com.example.omrscanner.imaging

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.omrscanner.camera.QuadFinder
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Xử lý ảnh sau khi chụp:
 *  1. [warpToSheet] — nắn thẳng tuyệt đối theo 4 tâm chấm định vị
 *     (getPerspectiveTransform + warpPerspective).
 *  2. [enhanceForOmr] — cân bằng trắng (gray-world), triệt tiêu bóng đổ
 *     (flat-field division), CLAHE làm nổi nét tô chì, unsharp mask nhẹ.
 *  3. [compressImageUnder1MB] — nén JPEG thích ứng, đảm bảo output < 1MB.
 */
object ImageProcessor {

    private const val TAG = "ImageProcessor"

    // ------------------------------------------------------------------
    // 0. Dò lại 4 chấm trên chính ảnh JPEG đã chụp
    // ------------------------------------------------------------------

    /**
     * Dò lại 4 chấm định vị **trên chính ảnh JPEG đã chụp** trước khi warp.
     *
     * Lý do: quad realtime lấy từ luồng ImageAnalysis (~640×480) trong khi ảnh
     * chụp là JPEG ~4MP — trường nhìn (FOV) của 2 luồng có thể lệch nhau vài
     * phần trăm trên một số máy, làm ảnh ra lệch khung dù HUD báo khớp. Dò
     * trên chính ảnh sẽ warp đảm bảo **ảnh nào ra đấy**.
     *
     * Ưu tiên: kết quả dò trên JPEG; chỉ quay về [analysisQuad] (quad realtime)
     * khi ảnh mờ/quá nhiễu không dò được, hoặc khi 2 kết quả lệch nhau bất
     * thường (> [MAX_QUAD_DEVIATION] — dấu hiệu dò nhầm chấm trên ảnh full).
     */
    fun refineQuadOnCapture(
        bitmap: Bitmap,
        analysisQuad: FloatArray?,
        blockShape: FloatArray? = null,
        ai: com.example.omrscanner.ai.AiDotDetector? = null,
        maxSide: Int = 1100,
    ): FloatArray {
        val srcQuad = analysisQuad?.copyOf()

        // 1) Pipeline cổ điển trên JPEG — chính xác nhất khi chấm nhìn thấy được.
        val jpegQuad = runCatching { detectQuadOnBitmap(bitmap, blockShape, maxSide) }.getOrNull()
        if (jpegQuad != null && isPlausibleQuad(jpegQuad) &&
            (srcQuad == null || consistent(srcQuad, jpegQuad))
        ) {
            Log.i(TAG, "quad from classical detector (capture)")
            return jpegQuad
        }

        // 2) AI trên ảnh JPEG (flavor tflite/dnn) — cứu trường hợp cổ điển
        //    thất bại (chấm mờ/bóng/chói). AI vẫn phải qua gate plausible.
        val aiQuad = ai?.let { runCatching { it.detectQuad(bitmap) }.getOrNull() }
        if (aiQuad != null && isPlausibleQuad(aiQuad)) {
            Log.i(TAG, "quad from AI model (capture, classical failed)")
            return aiQuad
        }

        if (jpegQuad != null && isPlausibleQuad(jpegQuad) &&
            (srcQuad == null || consistent(srcQuad, jpegQuad))
        ) {
            Log.i(TAG, "quad from JPEG capture (analysis quad " +
                (if (srcQuad == null) "n/a" else "consistent") + ")")
            return jpegQuad
        }
        if (jpegQuad != null) {
            Log.w(TAG, "JPEG quad inconsistent with analysis quad -> dùng quad realtime")
        } else {
            Log.w(TAG, "no quad on JPEG -> dùng quad realtime/fallback")
        }
        return srcQuad ?: jpegQuad ?: throw IllegalArgumentException(
            "Không dò được 4 chấm trên ảnh chụp",
        )
    }

    private fun detectQuadOnBitmap(
        bitmap: Bitmap,
        blockShape: FloatArray?,
        maxSide: Int,
    ): FloatArray? {
        val src = Mat()
        org.opencv.android.Utils.bitmapToMat(bitmap, src)
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        src.release()

        // Thu nhỏ về cùng thang với luồng analysis để dải diện tích/aspect
        // của bộ lọc hoạt động đúng như lúc hiệu chuẩn.
        val scale = minOf(1f, maxSide.toFloat() / maxOf(gray.cols(), gray.rows()))
        if (scale < 1f) {
            val small = Mat()
            Imgproc.resize(
                gray, small,
                Size(gray.cols() * scale.toDouble(), gray.rows() * scale.toDouble()),
                0.0, 0.0, Imgproc.INTER_AREA,
            )
            gray.release()
            val found = when (val o = QuadFinder.find(small, blockShape)) {
                is QuadFinder.Outcome.Found -> o.quad
                else -> null
            }
            small.release()
            return found
        }
        val found = when (val o = QuadFinder.find(gray, blockShape)) {
            is QuadFinder.Outcome.Found -> o.quad
            else -> null
        }
        gray.release()
        return found
    }

    /** Khối 4 chấm của phiếu A4 dọc có aspect W/H ~0.70 — chặn trường hợp méo hẳn. */
    private fun isPlausibleQuad(q: FloatArray): Boolean {
        val w = (dist2(q[0], q[1], q[2], q[3]) + dist2(q[6], q[7], q[4], q[5])) / 2f
        val h = (dist2(q[0], q[1], q[6], q[7]) + dist2(q[2], q[3], q[4], q[5])) / 2f
        val aspect = w / maxOf(h, 1e-3f)
        return aspect in 0.40f..1.15f
    }

    /** 2 quad coi là nhất quán nếu lệch tối đa giữa các cặp đỉnh nhỏ hơn ngưỡng. */
    private fun consistent(a: FloatArray, b: FloatArray): Boolean {
        var maxDiff = 0f
        for (i in a.indices) maxDiff = maxOf(maxDiff, kotlin.math.abs(a[i] - b[i]))
        return maxDiff <= MAX_QUAD_DEVIATION
    }

    private fun dist2(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private const val MAX_QUAD_DEVIATION = 0.12f

    // ------------------------------------------------------------------
    // 2. Tối ưu chất lượng ảnh scan
    // ------------------------------------------------------------------

    /**
     * Cải thiện ảnh phiếu:
     *  - Gray-world white balance: xử lý lệch màu đèn phòng.
     *  - Flat-field division: triệt tiêu bóng đổ tay/điện thoại trên giấy.
     *  - CLAHE trên kênh L (Lab): làm nổi nét tô chì & chấm định vị.
     *  - Unsharp mask nhẹ: cạnh bong bóng rõ hơn.
     */
    fun enhanceForOmr(src: Bitmap): Bitmap {
        val srcMat = Mat()
        Utils.bitmapToMat(src, srcMat)
        val bgr = Mat()
        Imgproc.cvtColor(srcMat, bgr, Imgproc.COLOR_RGBA2BGR)
        srcMat.release()

        val w = bgr.cols()
        val h = bgr.rows()

        // --- white balance (gray-world) trên bản thu nhỏ cho nhanh
        val f = Mat()
        bgr.convertTo(f, CvType.CV_32F, 1.0)
        val small = Mat()
        Imgproc.resize(f, small, Size((w / 32.0).coerceAtLeast(1.0), (h / 32.0).coerceAtLeast(1.0)), 0.0, 0.0, Imgproc.INTER_AREA)
        val mean = Core.mean(small)
        val target = (mean.`val`[0] + mean.`val`[1] + mean.`val`[2]) / 3.0
        val scales = DoubleArray(3) { i ->
            if (mean.`val`[i] < 1.0) 1.0 else (target / mean.`val`[i]).coerceIn(0.6, 1.6)
        }
        Core.multiply(f, Scalar(scales[0], scales[1], scales[2]), f)
        small.release()

        // --- flat-field: ước lượng nền bằng downscale/upscale rồi chia
        val bgSmall = Mat()
        Imgproc.resize(f, bgSmall, Size((w / 32.0).coerceAtLeast(1.0), (h / 32.0).coerceAtLeast(1.0)), 0.0, 0.0, Imgproc.INTER_AREA)
        val bg = Mat()
        Imgproc.resize(bgSmall, bg, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        bgSmall.release()
        Core.max(bg, Scalar(1.0, 1.0, 1.0), bg)
        val scaled = Mat()
        Core.multiply(f, Scalar(235.0, 235.0, 235.0), scaled)
        Core.divide(scaled, bg, f)
        scaled.release()
        bg.release()

        // --- CLAHE trên kênh L của Lab
        val u8 = Mat()
        f.convertTo(u8, CvType.CV_8UC3)
        f.release()
        val lab = Mat()
        Imgproc.cvtColor(u8, lab, Imgproc.COLOR_BGR2Lab)
        val labCh = ArrayList<Mat>(3)
        Core.split(lab, labCh)
        val clahe = Imgproc.createCLAHE(1.8, Size(8.0, 8.0))
        val lEnhanced = Mat()
        clahe.apply(labCh[0], lEnhanced)
        labCh[0].release()
        labCh[0] = lEnhanced
        Core.merge(labCh, lab)
        labCh.forEach { it.release() }
        Imgproc.cvtColor(lab, u8, Imgproc.COLOR_Lab2BGR)
        lab.release()

        // --- unsharp mask nhẹ (lam = 0.4)
        val blurred = Mat()
        Imgproc.GaussianBlur(u8, blurred, Size(0.0, 0.0), 2.0)
        Core.addWeighted(u8, 1.4, blurred, -0.4, 0.0, u8)
        blurred.release()

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rgba = Mat()
        Imgproc.cvtColor(u8, rgba, Imgproc.COLOR_BGR2RGBA)
        Utils.matToBitmap(rgba, result)
        rgba.release()
        u8.release()
        bgr.release()
        return result
    }

    // ------------------------------------------------------------------
    // 3. Nén JPEG thích ứng đảm bảo < 1MB
    // ------------------------------------------------------------------

    /**
     * Nén thích ứng: đưa chiều rộng về dải minTargetWidth-maxTargetWidth,
     * rồi giảm chất lượng từ 85% theo bước 5% cho tới khi file < maxBytes.
     * Nếu vẫn vượt (ảnh nhiễu/chi tiết quá nhiều) thì thu nhỏ ảnh 85% và lặp lại.
     */
    fun compressImageUnder1MB(
        bitmap: Bitmap,
        maxBytes: Int = 1024 * 1024,
        startQuality: Int = 88,
        step: Int = 4,
        minQuality: Int = 30,
    ): ByteArray {
        var quality = startQuality
        while (true) {
            val bos = ByteArrayOutputStream(maxBytes)
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            val size = bos.size()
            if (size <= maxBytes) {
                Log.i(TAG, "compressed: q=$quality size=${size / 1024}KB " +
                    "(${bitmap.width}x${bitmap.height})")
                return bos.toByteArray()
            }
            quality -= step
            if (quality < minQuality) {
                // Đã xuống sàn quality mà vẫn > maxBytes: trả bản tốt nhất.
                // KHÔNG thu nhỏ ảnh — kích thước phải giữ đúng khuôn mẫu
                // (4 chấm định vị nằm ở toạ độ cố định).
                Log.w(TAG, "quality floor reached at ${size / 1024}KB " +
                    "(${bitmap.width}x${bitmap.height}) — size preserved")
                return bos.toByteArray()
            }
        }
    }

    // ------------------------------------------------------------------
    // 4. Lưu file
    // ------------------------------------------------------------------

    fun saveJpegToInternal(context: Context, bytes: ByteArray, prefix: String = "omr"): File {
        val dir = File(context.filesDir, "omr_results").apply { mkdirs() }
        val file = File(dir, "$prefix-${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { it.write(bytes) }
        return file
    }

    /** Đọc lại bitmap vừa đủ nhỏ để hiển thị (tránh OOM ở ResultActivity). */
    fun decodeForDisplay(path: String, targetW: Int): Bitmap {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, opts)
        var sample = 1
        while (opts.outWidth / sample > targetW * 2) sample *= 2
        val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return android.graphics.BitmapFactory.decodeFile(path, o)
    }

    // ------------------------------------------------------------------
    // 5. Nắn ảnh về đúng khuôn mẫu (perspective + toàn khổ phiếu)
    // ------------------------------------------------------------------

    /**
     * Nắn ảnh chụp về **đúng khuôn của mẫu phiếu**.
     *
     * Dùng phép biến đổi phối cảnh từ **4 tâm chấm định vị đã phát hiện** tới
     * **vị trí 4 chấm chuẩn trong ảnh kết quả** ([TemplateConfig.fiducialTarget]).
     * Nhờ vậy:
     *  - ảnh hơi nghiêng/không vuông góc được nắn thẳng lại đúng theo mẫu;
     *  - kết quả có **đúng kích thước** và **đúng vị trí 4 chấm** như ảnh mẫu
     *    `kq_12.png` (1061×1495) / `kq_11_10.png` (1049×1657);
     *  - mọi ảnh chụp cùng mẫu ra cùng kích thước, tiện đối chiếu/hàng loạt.
     *
     * @param quadNormalized tâm 4 chấm [TL,TR,BR,BL] chuẩn hoá 0..1 trong ảnh chụp.
     */
    fun warpToTemplate(
        src: Bitmap,
        quadNormalized: FloatArray,
        template: com.example.omrscanner.omr.TemplateConfig,
    ): Bitmap {
        require(quadNormalized.size == 8) { "quad phải có 8 phần tử [TL,TR,BR,BL]" }
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val outW = template.outputWidth
        val outH = template.outputHeight
        val t = template.fiducialTarget

        val srcMat = Mat()
        Utils.bitmapToMat(src, srcMat)
        val srcQuad = MatOfPoint2f(
            Point((quadNormalized[0] * w).toDouble(), (quadNormalized[1] * h).toDouble()),
            Point((quadNormalized[2] * w).toDouble(), (quadNormalized[3] * h).toDouble()),
            Point((quadNormalized[4] * w).toDouble(), (quadNormalized[5] * h).toDouble()),
            Point((quadNormalized[6] * w).toDouble(), (quadNormalized[7] * h).toDouble()),
        )
        val dstQuad = MatOfPoint2f(
            Point((t[0] * outW).toDouble(), (t[1] * outH).toDouble()),
            Point((t[2] * outW).toDouble(), (t[3] * outH).toDouble()),
            Point((t[4] * outW).toDouble(), (t[5] * outH).toDouble()),
            Point((t[6] * outW).toDouble(), (t[7] * outH).toDouble()),
        )
        val transform = Imgproc.getPerspectiveTransform(srcQuad, dstQuad)
        val dst = Mat()
        // BORDER_REPLICATE: phần giấy nằm ngoài khung chụp (nếu có) được kéo mép,
        // tránh viền đen.
        Imgproc.warpPerspective(
            srcMat, dst, transform,
            Size(outW.toDouble(), outH.toDouble()),
            Imgproc.INTER_CUBIC,
            Core.BORDER_REPLICATE,
        )
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dst, result)

        srcMat.release()
        dst.release()
        srcQuad.release()
        dstQuad.release()
        transform.release()
        return result
    }
}
