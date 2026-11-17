package com.example.omrscanner

import android.content.Context
import android.graphics.Bitmap
import com.example.omrscanner.ai.AiDotDetector
import com.example.omrscanner.ai.HeatmapDecoder
import org.opencv.core.Mat
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import java.io.File

/**
 * Flavor **dnn** — CNN keypoint cùng model (dot_model.onnx) chạy qua module
 * **OpenCV DNN** có sẵn trong OpenCV 4.10 (không thêm dependency mới).
 */
class OnnxDotDetector(context: Context) : AiDotDetector {

    private val net: Net

    init {
        // OpenCV cần đường dẫn file — copy asset ra cacheDir
        val f = File(context.cacheDir, "dot_model.onnx")
        if (!f.exists() || f.length() == 0L) {
            context.assets.open("dot_model.onnx").use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
        }
        net = Dnn.readNetFromONNX(f.absolutePath)
        net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV)
        net.setPreferableTarget(Dnn.DNN_TARGET_CPU)
    }

    override fun detectQuad(gray: Mat): FloatArray? = runCatching {
        val small = HeatmapDecoder.toModelGray(gray)
        val blob = HeatmapDecoder.grayToBlob(small)
        small.release()
        net.setInput(blob)
        val out = net.forward()
        val res = readAndDecode(out)
        blob.release()
        out.release()
        res
    }.getOrNull()

    override fun detectQuad(bitmap: Bitmap): FloatArray? = runCatching {
        val small = HeatmapDecoder.toModelGray(bitmap)
        val blob = HeatmapDecoder.grayToBlob(small)
        small.release()
        net.setInput(blob)
        val out = net.forward()
        val res = readAndDecode(out)
        blob.release()
        out.release()
        res
    }.getOrNull()

    private fun readAndDecode(out: Mat): FloatArray? {
        // out: [1,48,64,4] (NHWC) hoặc [1,4,48,64] (NCHW) tùy exporter
        val d1 = out.size(1)
        val d2 = out.size(2)
        val d3 = out.size(3)
        val nchw = d1 == HeatmapDecoder.NCH
        val total = out.size(0) * d1 * d2 * d3
        val flat = FloatArray(total)
        out.get(0, 0, flat)
        return HeatmapDecoder.decode(flat, 0, layoutNchw = nchw)
    }

}
