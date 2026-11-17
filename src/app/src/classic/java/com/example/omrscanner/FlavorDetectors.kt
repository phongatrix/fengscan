package com.example.omrscanner

import android.content.Context
import android.content.Intent
import com.example.omrscanner.ai.AiDotDetector
import com.example.omrscanner.scan.CaptureEnhancer

/**
 * Flavor **classic** — pipeline OpenCV cổ điển (fit-to-block), không AI,
 * chụp bằng CameraX thuần.
 */
object FlavorDetectors {
    fun aiDetector(context: Context): AiDotDetector? = null

    fun captureEnhancer(context: Context): CaptureEnhancer? = null

    /**
     * Màn vào sau khi chọn mẫu:
     *  - true  (dnn/mlkit): [ScannerOnlyActivity] — vào THẲNG Google Scanner,
     *    bỏ hẳn màn HUD camera của app.
     *  - false (classic/tflite): null -> dùng [MainActivity] (camera HUD) như cũ.
     */
    fun scanEntry(context: Context): Intent? = null
}
