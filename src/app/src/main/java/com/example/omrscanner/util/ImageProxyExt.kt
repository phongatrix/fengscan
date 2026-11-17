package com.example.omrscanner.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * ImageProxy (YUV_420_888) helpers.
 *
 * Only the Y plane is needed for fiducial / bubble analysis, so we convert
 * straight to a grayscale [Mat] — much cheaper than a full YUV->RGB conversion.
 */
object ImageProxyExt {

    /**
     * Extracts the luma plane into a CV_8UC1 Mat and rotates it to the
     * display orientation reported by [ImageProxy.imageInfo.rotationDegrees],
     * so coordinates match what the user sees on screen.
     */
    fun ImageProxy.toGrayMat(displayOriented: Boolean = true): Mat {
        val plane = planes[0]
        val w = width
        val h = height
        val mat = Mat(h, w, CvType.CV_8UC1)
        val buf = plane.buffer.duplicate()
        buf.rewind()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride == 1 && rowStride == w) {
            val data = ByteArray(w * h)
            buf.get(data)
            mat.put(0, 0, data)
        } else {
            val row = ByteArray(w)
            for (r in 0 until h) {
                if (pixelStride == 1) {
                    buf.position(r * rowStride)
                    buf.get(row, 0, w)
                } else {
                    for (c in 0 until w) {
                        row[c] = buf.get(r * rowStride + c * pixelStride)
                    }
                }
                mat.put(r, 0, row)
            }
        }
        if (!displayOriented) return mat
        val rotated = Mat()
        when (imageInfo.rotationDegrees) {
            90 -> Core.rotate(mat, rotated, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(mat, rotated, Core.ROTATE_180)
            270 -> Core.rotate(mat, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> mat.copyTo(rotated)
        }
        mat.release()
        return rotated
    }

    /** Decodes JPEG bytes and applies the EXIF/rotation to display orientation. */
    fun decodeJpegRotated(bytes: ByteArray, rotationDegrees: Int): Bitmap {
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (rotationDegrees == 0) return raw
        val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (rotated != raw) raw.recycle()
        return rotated
    }
}
