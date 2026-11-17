package com.example.omrscanner.omr

/**
 * Loại phiếu — quyết định khuôn khung ngắm HUD và khuôn ảnh kết quả.
 */
enum class SheetType(val label: String) {
    /** Mẫu 10-11 (Giay10_11.pdf). */
    MAU_10_11("Mẫu 10-11"),

    /** Mẫu 12 (Giay12.pdf). */
    MAU_12("Mẫu 12"),
}

/**
 * Khuôn ảnh kết quả của một mẫu phiếu.
 *
 *  - [outputWidth] / [outputHeight]: **đúng kích thước ảnh mẫu** `kq_*.png`.
 *  - [fiducialTarget]: tâm 4 chấm định vị trong ảnh kết quả, chuẩn hoá 0..1,
 *    dạng [TL.x, TL.y, TR.x, TR.y, BR.x, BR.y, BL.x, BL.y].
 *
 * Cách xác định: render PDF gốc 300 dpi, khớp SIFT + homography với chính ảnh
 * mẫu rồi chiếu toạ độ 4 chấm của PDF vào ảnh mẫu. Kiểm chứng vòng tròn
 * (warp PDF theo các toạ độ này rồi so lại ảnh mẫu): scale 0.9999, xoay
 * < 0.02°, lệch tâm < 1 px — nên ảnh chụp ra có **đúng** kích thước và **đúng**
 * vị trí 4 chấm như 2 ảnh mẫu.
 */
data class TemplateConfig(
    val type: SheetType,
    val outputWidth: Int,
    val outputHeight: Int,
    val fiducialTarget: FloatArray,
) {
    val outputAspect: Float get() = outputHeight.toFloat() / outputWidth

    /**
     * Tâm 4 chấm ở **hệ pixel của trang** `[TLx,TLy, TRx,TRy, BRx,BRy, BLx,BLy]`
     * — không gian ĐẲNG HƯỚNG (px), dùng cho khớp hình học (fitToBlock):
     * chuẩn hóa theo trục riêng (x/W, y/H) sẽ làm méo hình học vì W ≠ H.
     */
    fun fiducialTargetPagePx(): FloatArray = FloatArray(8) { i ->
        if (i % 2 == 0) fiducialTarget[i] * outputWidth
        else fiducialTarget[i] * outputHeight
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = type.hashCode()
}

/** Cấu hình định sẵn cho từng mẫu phiếu. */
object Templates {

    /**
     * Mẫu 10-11 → **1049×1657 px** (ảnh mẫu `kq_11_10.png`).
     *
     * 4 chấm đo được trên ảnh mẫu (px → chuẩn hoá):
     *  TL(71.3, 263.6) TR(983.8, 257.1) BR(1002.2, 1568.1) BL(79.3, 1584.6)
     */
    val MAU_10_11 = TemplateConfig(
        type = SheetType.MAU_10_11,
        outputWidth = 1049,
        outputHeight = 1657,
        fiducialTarget = floatArrayOf(
            0.06797f, 0.15908f,   // TL
            0.93784f, 0.15516f,   // TR
            0.95539f, 0.94635f,   // BR
            0.07560f, 0.95631f,   // BL
        ),
    )

    /**
     * Mẫu 12 → **1061×1495 px** (ảnh mẫu `kq_12.png`).
     *
     * 4 chấm đo được trên ảnh mẫu (px → chuẩn hoá):
     *  TL(43.7, 58.1) TR(1006.6, 53.8) BR(995.1, 1409.9) BL(67.4, 1423.3)
     */
    val MAU_12 = TemplateConfig(
        type = SheetType.MAU_12,
        outputWidth = 1061,
        outputHeight = 1495,
        fiducialTarget = floatArrayOf(
            0.04119f, 0.03886f,   // TL
            0.94873f, 0.03599f,   // TR
            0.93789f, 0.94308f,   // BR
            0.06353f, 0.95204f,   // BL
        ),
    )

    fun forType(type: SheetType): TemplateConfig = when (type) {
        SheetType.MAU_10_11 -> MAU_10_11
        SheetType.MAU_12 -> MAU_12
    }
}
