package com.example.omrscanner

import android.content.Context
import android.content.Intent
import com.example.omrscanner.ai.AiDotDetector
import com.example.omrscanner.scan.CaptureEnhancer

object FlavorDetectors {


    fun aiDetector(context: Context): AiDotDetector? =
        runCatching { OnnxDotDetector(context) }.getOrNull()

    fun captureEnhancer(context: Context): CaptureEnhancer? =
        runCatching { MlKitCaptureEnhancer(context, prefix = "FS") }.getOrNull()

    /**
     * Màn vào sau khi chọn mẫu:
     *  - dnn/mlkit: [ScannerOnlyActivity] — vào THẲNG Google Scanner,
     *    bỏ hẳn màn HUD camera của app.
     *  - classic/tflite: null -> dùng [MainActivity] (camera HUD) như cũ.
     */
    fun scanEntry(context: Context): Intent? = Intent(context, ScannerOnlyActivity::class.java)
}
