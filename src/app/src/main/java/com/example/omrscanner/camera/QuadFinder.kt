package com.example.omrscanner.camera

import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Bộ dò 4 chấm định vị — **dùng chung** cho 2 nơi:
 *  1. [FiducialDetectorAnalyzer]: khung ImageAnalysis realtime (HUD).
 *  2. [com.example.omrscanner.imaging.ImageProcessor.refineQuadOnCapture]:
 *     dò lại trên chính ảnh JPEG đã chụp trước khi warp.
 *
 * Việc dùng chung đảm bảo HUD và ảnh kết quả luôn cùng một logic dò chấm
 * (pipeline đã kiểm định bằng tools/calibrate_layout.py):
 *   GaussianBlur -> Otsu (cap 130) -> morph open -> xóa đường kẻ dài
 *   -> contour -> lọc area/aspect/solidity/fill -> ghép 4 góc theo quadrant.
 */
object QuadFinder {

    /** Kết quả dò trên một ảnh xám. */
    sealed interface Outcome {
        /** Không có ứng viên chấm nào. */
        data object NoMarkers : Outcome

        /** Có chấm nhưng không ghép được tứ giác hợp lệ (< 4 điểm distinct / méo). */
        data object Incomplete : Outcome

        /** Đủ 4 chấm hợp lệ — quad chuẩn hóa theo [Found.frameWidth]/[Found.frameHeight]. */
        data class Found(
            val quad: FloatArray,
            val tiltDegrees: Float,
            val coverage: Float,
            val frameWidth: Int,
            val frameHeight: Int,
        ) : Outcome
    }

    fun find(gray: Mat, blockShape: FloatArray? = null, aiQuad: FloatArray? = null): Outcome {
        val w = gray.cols()
        val h = gray.rows()
        if (w < 8 || h < 8) return Outcome.NoMarkers

        // AI (CNN keypoint) chạy TRƯỚC nếu flavor có model: quad của model vẫn
        // phải qua các gate hình học (loại quad méo hẳn) thì mới nhận.
        if (aiQuad != null && aiQuad.size == 8) {
            val qw = (distN(aiQuad, 0, 1) + distN(aiQuad, 3, 2)) / 2f * w
            val qh = (distN(aiQuad, 0, 3) + distN(aiQuad, 1, 2)) / 2f * h
            val aspect = qw / max(qh, 1e-3f)
            val coverage = qw * qh / (w.toFloat() * h)
            if (aspect in 0.40f..1.15f && coverage in 0.02f..0.98f &&
                !isDegenerate(aiQuad)
            ) {
                if (DEBUG_LOG) Log.d(TAG, "AI quad accepted")
                return Outcome.Found(
                    aiQuad.copyOf(), tiltOf(aiQuad), coverage, w, h,
                )
            }
            if (DEBUG_LOG) Log.d(TAG, "AI quad rejected by gates")
        }

        val blur = Mat()
        val bin = Mat()
        val opened = Mat()
        val kernel3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        try {
            Imgproc.GaussianBlur(gray, blur, Size(3.0, 3.0), 0.0)
            // Otsu có thể bị kéo lên cao khi nền giấy xám -> chốt trần ngưỡng 130
            // (chấm định vị luôn gần đen).
            val otsu = Imgproc.threshold(
                blur, bin, 0.0, 255.0,
                Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU,
            )
            Imgproc.threshold(blur, bin, minOf(otsu, THRESH_CAP), 255.0, Imgproc.THRESH_BINARY_INV)
            Imgproc.morphologyEx(bin, opened, Imgproc.MORPH_OPEN, kernel3)
            opened.copyTo(bin)
            removeLongLines(bin, w)

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(bin, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            hierarchy.release()

            val frameArea = w.toFloat() * h
            val minArea = MIN_AREA_RATIO * frameArea
            val maxArea = MAX_AREA_RATIO * frameArea
            val candidates = ArrayList<Corner>(8)
            for (c in contours) {
                val area = Imgproc.contourArea(c)
                if (area < minArea || area > maxArea) continue
                val br = Imgproc.boundingRect(c)
                if (br.width < 3 || br.height < 3) continue
                val aspect = br.width.toFloat() / br.height
                if (aspect < 0.62f || aspect > 1.60f) continue
                val hullIdx = MatOfInt()
                Imgproc.convexHull(c, hullIdx)
                // hull chứa INDEX vào điểm contour -> ánh xạ ngược về điểm
                val contourPts = c.toArray()
                val hullMat = MatOfPoint()
                hullMat.fromArray(*hullIdx.toArray().map { contourPts[it.toInt()] }.toTypedArray())
                val hullArea = Imgproc.contourArea(hullMat)
                hullMat.release()
                hullIdx.release()
                val solidity = area / max(hullArea, 1.0)
                if (solidity < 0.88) continue
                val fill = area / (br.width.toFloat() * br.height)
                if (fill < 0.68) continue
                val m = Imgproc.moments(c)
                if (m.m00 <= 0.0) continue
                candidates += Corner((m.m10 / m.m00).toFloat(), (m.m01 / m.m00).toFloat())
            }
            if (DEBUG_LOG) Log.d(TAG, "frame ${w}x$h contours=${contours.size} markers=${candidates.size}")
            if (candidates.isEmpty()) return Outcome.NoMarkers

            // Ghép 4 góc: ưu tiên KHỚP HÌNH HỌC THEO MẪU (chống ô vuông trang trí
            // đánh cắp vị trí góc); không khớp được thì quay về quadrant cực trị.
            val quad = if (blockShape != null && blockShape.size == 8) {
                fitToBlock(candidates, blockShape, w, h) ?: assembleByQuadrant(candidates)
            } else {
                assembleByQuadrant(candidates)
            }
            if (quad == null) return Outcome.Incomplete
            val (tl, tr, br, bl) = quad

            val quadW = ((dist(tl, tr) + dist(bl, br)) / 2f).coerceAtLeast(1f)
            val quadH = ((dist(tl, bl) + dist(tr, br)) / 2f).coerceAtLeast(1f)
            val coverage = quadW * quadH / frameArea
            val aspect = quadW / quadH
            // Chỉ loại tứ giác méo hẳn (dò nhầm cụm chấm khác), chấp nhận ảnh chéo.
            if (aspect < 0.35f || aspect > 1.30f) return Outcome.Incomplete
            if (coverage < MIN_COVERAGE) return Outcome.Incomplete

            val normalized = floatArrayOf(
                tl.x / w, tl.y / h,
                tr.x / w, tr.y / h,
                br.x / w, br.y / h,
                bl.x / w, bl.y / h,
            )
            return Outcome.Found(normalized, topEdgeTilt(tl, tr, bl, br), coverage, w, h)
        } finally {
            blur.release()
            bin.release()
            opened.release()
            kernel3.release()
        }
    }

    // ------------------------------------------------------------------
    // Ghép 4 góc
    // ------------------------------------------------------------------

    /** Khoảng cách giữa 2 đỉnh của quad normalized (đơn vị normalized). */
    private fun distN(q: FloatArray, i: Int, j: Int): Float {
        val dx = q[i * 2] - q[j * 2]
        val dy = q[i * 2 + 1] - q[j * 2 + 1]
        return sqrt(dx * dx + dy * dy)
    }

    /** Tilt (độ) của cạnh trên của quad normalized. */
    private fun tiltOf(q: FloatArray): Float {
        val a = atan2(
            (q[5] - q[1]).toDouble(),
            (q[4] - q[0]).toDouble(),
        )
        var deg = Math.toDegrees(a).toFloat()
        deg = ((deg + 90f) % 180f + 180f) % 180f - 90f
        return deg
    }

    /** Quad suy biến: 2 đỉnh bất kỳ chùng nhau/gần như trùng. */
    private fun isDegenerate(q: FloatArray): Boolean {
        for (a in 0 until 4) for (b in a + 1 until 4) {
            if (distN(q, a, b) < 0.05f) return true
        }
        return false
    }

    private data class CornerQuad(
        val tl: Corner, val tr: Corner, val br: Corner, val bl: Corner,
    )

    /** Quadrant cực trị — nhanh, đúng khi không có ứng viên nhiễu nào "cực" hơn. */
    private fun assembleByQuadrant(c: List<Corner>): CornerQuad? {
        val tl = c.minByOrNull { it.x + it.y } ?: return null
        val br = c.maxByOrNull { it.x + it.y } ?: return null
        val tr = c.maxByOrNull { it.x - it.y } ?: return null
        val bl = c.minByOrNull { it.x - it.y } ?: return null
        return if (setOf(tl, tr, br, bl).size < 4) null else CornerQuad(tl, tr, br, bl)
    }

    /**
     * **Khớp hình học theo mẫu** — "RANSAC mini" không cần model:
     *
     * Với mỗi cặp ứng viên (a=TL, b=TR) đủ rộng, dựng phép tương tự
     * (scale + rotation) từ khối 4 chấm chuẩn [blockShape] — ở **hệ pixel của
     * trang** (`TemplateConfig.fiducialTargetPagePx()`, đẳng hướng) — rồi
     * **dự đoán** vị trí BR/BL, tìm ứng viên gần dự đoán nhất. Chọn bộ 4 có
     * tổng sai số nhỏ nhất.
     *
     * LƯU Ý ĐƠN VỊ: shape phải là page-px (x*W, y*H). Nếu dùng normalized
     * theo trục riêng (x/W, y/H) thì phép similarity sẽ nén trục y theo tỷ lệ
     * W/H và chọn nhầm chấm giữa trang (bug đã gặp, đã fix).
     *
     * Nhờ đó các ô vuông trang trí (■) rải rác trên phiếu — vốn có thể "cực"
     * hơn chấm thật theo quadrant — sẽ bị loại vì không nằm đúng vị trí mà
     * mẫu quy định. Phiếu sai mẫu sẽ cho khớp kém -> rơi về quadrant.
     */
    private fun fitToBlock(
        candidates: List<Corner>,
        shape: FloatArray,
        w: Int,
        h: Int,
    ): CornerQuad? {
        val sTl = Corner(shape[0], shape[1])
        val sTr = Corner(shape[2], shape[3])
        val sBr = Corner(shape[4], shape[5])
        val sBl = Corner(shape[6], shape[7])
        val base = dist(sTl, sTr)
        if (base <= 1e-4f) return null

        val baseAng = atan2(sTr.y - sTl.y, sTr.x - sTl.x).toFloat()
        var best: CornerQuad? = null
        var bestScore = Float.MAX_VALUE

        for (a in candidates) {
            for (b in candidates) {
                if (a === b) continue
                val d = dist(a, b)
                // Cạnh trên khối chấm phải chiếm ít nhất 10% bề rộng khung
                if (d < w * 0.10f) continue
                val scale = d / base
                val ang = atan2(b.y - a.y, b.x - a.x).toFloat() - baseAng
                val cos = kotlin.math.cos(ang)
                val sin = kotlin.math.sin(ang)
                fun mapTo(sx: Float, sy: Float): Corner {
                    val dx = sx - sTl.x
                    val dy = sy - sTl.y
                    return Corner(
                        a.x + scale * (dx * cos - dy * sin),
                        a.y + scale * (dx * sin + dy * cos),
                    )
                }
                val tol = d * 0.18f
                val pBr = mapTo(sBr.x, sBr.y)
                val pBl = mapTo(sBl.x, sBl.y)
                val kB = nearest(pBr, candidates, tol, exclude = listOf(a, b)) ?: continue
                val kL = nearest(pBl, candidates, tol, exclude = listOf(a, b, kB)) ?: continue
                val score = dist(kB, pBr) + dist(kL, pBl)
                if (score < bestScore) {
                    bestScore = score
                    best = CornerQuad(a, b, kB, kL)
                }
            }
        }
        return best
    }

    private fun nearest(p: Corner, pool: List<Corner>, tol: Float, exclude: List<Corner>): Corner? {
        var best: Corner? = null
        var bestD = tol
        for (c in pool) {
            if (exclude.any { it === c }) continue
            val d = dist(c, p)
            if (d < bestD) { bestD = d; best = c }
        }
        return best
    }

    /** Tách dấu góc khỏi đường viền trang / đường kẻ bảng dài. */
    private fun removeLongLines(bin: Mat, frameW: Int) {        val len = max(40.0, frameW * 0.06)
        val hk = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(len, 1.0))
        val vk = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, len))
        val hLines = Mat()
        val vLines = Mat()
        val lines = Mat()
        val inv = Mat()
        val out = Mat()
        Imgproc.morphologyEx(bin, hLines, Imgproc.MORPH_OPEN, hk)
        Imgproc.morphologyEx(bin, vLines, Imgproc.MORPH_OPEN, vk)
        Core.bitwise_or(hLines, vLines, lines)
        Core.bitwise_not(lines, inv)
        Core.bitwise_and(bin, inv, out)
        out.copyTo(bin)
        hk.release()
        vk.release()
        hLines.release()
        vLines.release()
        lines.release()
        inv.release()
        out.release()
    }

    /** Góc nghiêng (độ) trung bình của cạnh trên & cạnh dưới, chuẩn hóa [-90,90). */
    private fun topEdgeTilt(tl: Corner, tr: Corner, bl: Corner, br: Corner): Float {
        val a1 = atan2((tr.y - tl.y).toDouble(), (tr.x - tl.x).toDouble())
        val a2 = atan2((br.y - bl.y).toDouble(), (br.x - bl.x).toDouble())
        var deg = Math.toDegrees((a1 + a2) / 2.0).toFloat()
        deg = ((deg + 90f) % 180f + 180f) % 180f - 90f
        return deg
    }

    private data class Corner(val x: Float, val y: Float)

    private fun dist(a: Corner, b: Corner): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private const val TAG = "FiducialDetector"
    private const val DEBUG_LOG = true
    private const val THRESH_CAP = 130.0
    private const val MIN_AREA_RATIO = 0.00004
    private const val MAX_AREA_RATIO = 0.02

    /** Tứ giác phải chiếm tối thiểu này của khung để coi là phiếu thật. */
    private const val MIN_COVERAGE = 0.02f

    /** Ngưỡng aspect của khối chấm cho phép (W/H) — phiếu A4 dọc ~0.70. */
    const val QUAD_ASPECT_MIN = 0.35f
    const val QUAD_ASPECT_MAX = 1.30f
}
