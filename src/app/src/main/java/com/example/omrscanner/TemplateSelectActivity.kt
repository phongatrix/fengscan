package com.example.omrscanner

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.omrscanner.databinding.ActivityTemplateSelectBinding
import com.example.omrscanner.omr.SheetType
import com.example.omrscanner.storage.StorageHelper

/**
 * Màn hình chọn cấu hình trước khi vào camera:
 *  - Chọn mẫu phiếu (Mẫu 10-11 / Mẫu 12) — HUD và tỷ lệ khung dùng đúng mẫu.
 *  - Nhập tiền tố tên ảnh (trống -> "OMR").
 */
class TemplateSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTemplateSelectBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTemplateSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Khôi phục lựa chọn lần trước
        val last = StorageHelper.loadSheetType(this)
        binding.radioSheetType.check(
            if (last == SheetType.MAU_10_11.name) R.id.radioMau1011 else R.id.radioMau12,
        )
        binding.edtPrefix.setText(StorageHelper.loadPrefix(this))

        binding.btnStart.setOnClickListener {
            val type = if (binding.radioSheetType.checkedRadioButtonId == R.id.radioMau1011) {
                SheetType.MAU_10_11
            } else {
                SheetType.MAU_12
            }
            StorageHelper.saveSheetType(this, type.name)
            StorageHelper.savePrefix(this, binding.edtPrefix.text.toString())
            // dnn/mlkit: vào THẲNG Google Scanner (bỏ màn HUD camera của app).
            // classic/tflite: scanEntry() = null -> dùng camera HUD như cũ.
            val entry = FlavorDetectors.scanEntry(this)
                ?: Intent(this, MainActivity::class.java)
            startActivity(entry)
            finish()
        }
    }
}
