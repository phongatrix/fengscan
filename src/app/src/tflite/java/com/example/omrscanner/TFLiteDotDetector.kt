package com.example.omrscanner

import android.content.Context
import android.graphics.Bitmap
import com.example.omrscanner.ai.AiDotDetector
import com.example.omrscanner.ai.HeatmapDecoder
import org.opencv.core.Mat
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Flavor **tflite** — CNN keypoint tự huấn luyện (dot_model.tflite, ~63KB)
 * chạy TensorFlow Lite. Model dự đoán 4 heatmap 24×32 cho [TL,TR,BR,BL];
 * quad của model vẫn phải qua gate hình học của QuadFinder trước khi nhận.
 * Chụp vẫn bằng CameraX thuần.
 */
class TFLiteDotDetector(context: Context) : AiDotDetector {

    private val interpreter: Interpreter

    init {
        interpreter = Interpreter(loadModel(context, "dot_model.tflite"), Interpreter.Options().apply {
            numThreads = 2
        })
    }

    private fun loadModel(context: Context, name: String): MappedByteBuffer {
        context.assets.openFd(name).use { fd ->
            FileInputStream(fd.fileDescriptor).use { fis ->
                return fis.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength,
                )
            }
        }
    }

    private val output = Array(1) {
        Array(HeatmapDecoder.OH) { Array(HeatmapDecoder.OW) { FloatArray(HeatmapDecoder.NCH) } }
    }

    override fun detectQuad(gray: Mat): FloatArray? = runCatching {
        val small = HeatmapDecoder.toModelGray(gray)
        val input = HeatmapDecoder.grayToNhwc(small)
        small.release()
        interpreter.run(input, output)
        HeatmapDecoder.decode(flatten(output))
    }.getOrNull()

    override fun detectQuad(bitmap: Bitmap): FloatArray? = runCatching {
        val small = HeatmapDecoder.toModelGray(bitmap)
        val input = HeatmapDecoder.grayToNhwc(small)
        small.release()
        interpreter.run(input, output)
        HeatmapDecoder.decode(flatten(output))
    }.getOrNull()

    private fun flatten(
        out: Array<Array<Array<FloatArray>>>,
    ): FloatArray {
        val flat = FloatArray(HeatmapDecoder.OH * HeatmapDecoder.OW * HeatmapDecoder.NCH)
        var k = 0
        for (y in 0 until HeatmapDecoder.OH)
            for (x in 0 until HeatmapDecoder.OW)
                for (c in 0 until HeatmapDecoder.NCH)
                    flat[k++] = out[0][y][x][c]
        return flat
    }
}
