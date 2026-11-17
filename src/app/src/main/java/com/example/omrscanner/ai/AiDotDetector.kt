package com.example.omrscanner.ai

import android.content.Context
import android.graphics.Bitmap
import org.opencv.core.Mat

/**
 * Bộ dò 4 chấm bằng AI (CNN keypoint) — chỉ có bản thực ở flavor `tflite`
 * và `dnn`; flavor `classic`/`mlkit` trả về null (dùng pipeline cổ điển).
 *
 * Hợp đồng:
 *  - Input: ảnh xám hiển thị dọc ( bất kỳ kích thước, model tự resize 96×128).
 *  - Output: quad normalized [TL,TR,BR,BL] theo ảnh input, hoặc null nếu
 *    model không tự tin (peak heatmap quá thấp / 4 điểm suy biến).
 */
interface AiDotDetector {
    /** Dò trên khung xám realtime (analysis stream). */
    fun detectQuad(gray: Mat): FloatArray?

    /** Dò trên ảnh JPEG đã chụp (pipeline warp dùng quad này). */
    fun detectQuad(bitmap: Bitmap): FloatArray?
}

/** Nguồn tạo detector theo flavor — mỗi flavor có implementation riêng. */
interface AiDetectorProvider {
    fun create(context: Context): AiDotDetector?
}
