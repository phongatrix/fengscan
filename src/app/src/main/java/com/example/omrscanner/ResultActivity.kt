package com.example.omrscanner

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.omrscanner.databinding.ActivityResultBinding
import com.example.omrscanner.imaging.ImageProcessor

/**
 * Màn hình xem ảnh vừa chụp: ảnh đã nắn phẳng + tối ưu, nén < 1MB,
 * kèm tên file và nơi lưu. Không có thông tin phân tích nào trên ảnh.
 */
class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            Toast.makeText(this, R.string.result_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val bitmap: Bitmap? = runCatching { ImageProcessor.decodeForDisplay(path, 1200) }.getOrNull()
        if (bitmap != null) {
            binding.imageResult.setImageBitmap(bitmap)
        } else {
            Toast.makeText(this, R.string.result_load_fail, Toast.LENGTH_SHORT).show()
        }

        binding.tvFileName.text = buildString {
            append(intent.getStringExtra(EXTRA_FILE_NAME) ?: "")
            append(" • ")
            append(
                if (intent.getBooleanExtra(EXTRA_GALLERY_SAVED, false)) {
                    getString(R.string.result_saved_gallery)
                } else {
                    getString(R.string.result_saved_internal)
                },
            )
        }

        binding.btnNext.setOnClickListener { finish() }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_FILE_NAME = "fileName"
        const val EXTRA_GALLERY_SAVED = "gallerySaved"
        const val EXTRA_SHEET_TYPE = "sheetType"
    }
}
