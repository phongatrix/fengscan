package com.example.omrscanner.ai

import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Template decode heatmap CNN — dùng chung cho cả TFLite và OpenCV DNN.
 *
 * Model: input 1×96×128 (gray 0..1), output 4 heatmap 48×64 (stride 2),
 * kênh [TL, TR, BR, BL]. Decode: argmax mỗi kênh + centroid 3×3 (sub-pixel).
 *
 * Vì input 128×96 có cùng tỷ lệ 4:3 dọc với khung analysis, quad normalized
 * trong không gian model == quad normalized trong không gian ảnh gốc.
 */
object HeatmapDecoder {

    const val IW = 128
    const val IH = 96
    const val OW = IW / 2   // 64 (heatmap stride 2)
    const val OH = IH / 2   // 48
    const val NCH = 4

    /** Resize ảnh xám về 96×128 (INTER_AREA) — trả Mat CV_8UC1 mới. */
    fun toModelGray(src: Mat): Mat {
        val small = Mat()
        Imgproc.resize(src, small, Size(IW.toDouble(), IH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        return small
    }

    fun toModelGray(bitmap: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        val small = toModelGray(gray)
        gray.release()
        return small
    }

    /**
     * Buffer NHWC [1,96,128,1] float32 (TFLite) từ Mat 96×128 gray.
     */
    fun grayToNhwc(small: Mat): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(4 * IH * IW).order(ByteOrder.nativeOrder())
        val data = ByteArray(IH * IW)
        small.get(0, 0, data)
        for (v in data) buf.putFloat((v.toInt() and 0xFF) / 255f)
        buf.rewind()
        return buf
    }

    /**
     * Blob NCHW [1,1,96,128] float32 0..1 (OpenCV DNN) từ Mat 96×128 gray.
     */
    fun grayToBlob(small: Mat): org.opencv.core.Mat {
        val f = org.opencv.core.Mat(IH, IW, org.opencv.core.CvType.CV_32F)
        val data = ByteArray(IH * IW)
        small.get(0, 0, data)
        val floats = FloatArray(IH * IW) { (data[it].toInt() and 0xFF) / 255f }
        f.put(0, 0, floats)
        val blob = org.opencv.dnn.Dnn.blobFromImage(f, 1.0, Size(IW.toDouble(), IH.toDouble()))
        f.release()
        return blob
    }

    /**
     * Decode 1 frame heatmap (24×32×4, NHWC flattened row-major) → quad
     * normalized, hoặc null nếu peak nào quá thấp / 4 điểm suy biến.
     * @param minPeak ngưỡng tin cậy (peak heatmap; model trả giá trị ~0..1).
     */
    fun decode(
        heat: FloatArray,
        offset: Int = 0,
        layoutNchw: Boolean = false,
        minPeak: Float = 0.30f,
    ): FloatArray? {
        val peaks = FloatArray(NCH)
        val px = FloatArray(NCH)
        val py = FloatArray(NCH)
        for (c in 0 until NCH) {
            var best = -Float.MAX_VALUE
            var bi = 0
            var bj = 0
            for (yy in 0 until OH) {
                for (xx in 0 until OW) {
                    val v = if (layoutNchw) {
                        heat[offset + c * OH * OW + yy * OW + xx]
                    } else {
                        heat[offset + (yy * OW + xx) * NCH + c]
                    }
                    if (v > best) { best = v; bi = xx; bj = yy }
                }
            }
            peaks[c] = best
            if (best < minPeak) return null
            // centroid 3x3 quanh argmax (sub-pixel)
            var sx = 0f; var sy = 0f; var den = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val y2 = bj + dy; val x2 = bi + dx
                if (y2 !in 0 until OH || x2 !in 0 until OW) continue
                val v = (if (layoutNchw) {
                    heat[offset + c * OH * OW + y2 * OW + x2]
                } else {
                    heat[offset + (y2 * OW + x2) * NCH + c]
                }).coerceAtLeast(0f)
                sx += x2 * v; sy += y2 * v; den += v
            }
            px[c] = if (den > 0f) sx / den / OW else bi.toFloat() / OW
            py[c] = if (den > 0f) sy / den / OH else bj.toFloat() / OH
        }
        // 4 điểm phải phân biệt + đúng thứ tự góc (TL,TR,BR,BL)
        val d01 = kotlin.math.hypot((px[1] - px[0]).toDouble(), (py[1] - py[0]).toDouble())
        val d23 = kotlin.math.hypot((px[3] - px[2]).toDouble(), (py[3] - py[2]).toDouble())
        if (d01 < 0.25 || d23 < 0.25) return null
        if (!(px[0] < px[1] && px[3] < px[2] && py[0] < py[3] && py[1] < py[2])) return null
        return floatArrayOf(px[0], py[0], px[1], py[1], px[2], py[2], px[3], py[3])
    }
}
