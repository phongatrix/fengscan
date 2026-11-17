# OMR Scanner — camera chụp phiếu theo mẫu

Ứng dụng Android **nhận diện 4 chấm đen định vị ở 4 góc phiếu**, báo khung xanh
khi khớp, đếm ngược 3-2-1 và **tự động chụp** ảnh phiếu chất lượng cao (< 1MB).

> **Phạm vi**: app **chỉ chụp ảnh** phiếu theo mẫu. KHÔNG đọc/phân tích nội dung
> phiếu (mã đề, SBD, Phần I/II/III, bubble đã tô), KHÔNG chấm điểm, KHÔNG vẽ
> thông tin lên ảnh. Ảnh chụp được **nắn thẳng theo mẫu** (nếu camera hơi nghiêng)
> và xuất đúng khuôn tỷ lệ như ảnh mẫu `kq_12.png` / `kq_11_10.png`.

## Tính năng

1. **Chọn mẫu trước khi vào camera** (`TemplateSelectActivity`)
   - Mẫu 10-11 / Mẫu 12 (khác nhau ở tỷ lệ khối 4 chấm → tỷ lệ khung ngắm).
   - Nhập **tiền tố tên ảnh** (trống → `OMR`).
2. **Camera tự dò + tự phục hồi** (`CameraManagerHelper`)
   - Dò camera sau chính theo tiêu cự/OIS/cỡ sensor, né ultra-wide và telephoto.
   - **Fallback chain**: camera đã dò → `DEFAULT_BACK_CAMERA`; tạo lại use-case mỗi
     lần thử, gỡ bind dở dang trước khi thử tiếp (khắc phục "không bật được camera").
   - Chọn JPEG 4:3 trong dải 3–5.2MP; Preview + ImageAnalysis (640×480) + ImageCapture
     tách luồng; lấy nét/đo sáng **đa điểm** toàn mặt phiếu.
3. **HUD nhận diện 4 chấm realtime** (`FiducialDetectorAnalyzer` + `CountdownTimerOverlayView`)
   - **Khung đỏ CHÍNH LÀ khung ảnh kết quả**: tỷ lệ khung = tỷ lệ *toàn trang* của
     ảnh mẫu (Mẫu 12 → 1061:1495 = 0.7097; Mẫu 10-11 → 1049:1657 = 0.6331).
     Phần giấy nằm ngoài khung bị làm tối → vùng sáng = đúng phần ảnh sẽ lưu.
   - **4 ô vuông nhỏ** vẽ tại đúng vị trí 4 chấm của mẫu (`fiducialTarget`), mỗi ô
     có 4 vạch ngắm ở tâm.
   - Panel trên hiển thị **ảnh mẫu giấy đang chọn** (thumbnail render từ
     `Giay12.pdf` / `Giay10_11.pdf`, đã nắn về đúng khuôn ảnh kết quả) + nút chọn
     mẫu. Panel **không đè lên khung đỏ**: overlay đo vị trí thật của panel trên
     và thanh điều khiển dưới (`syncOverlayInsets`) rồi vẽ khung vào khoảng trống.
   - Màu HUD: **ĐỎ** (chưa đủ chấm) → **VÀNG** (thấy một phần) → **XANH LÁ glow**.
   - **Tiêu chí hợp lệ**: mỗi chấm nằm trong khung đỏ **và** cách tâm ô mục tiêu
     của nó ≤ 7.5% chiều rộng khung — không bắt buộc chạm góc, không cần đúng góc nghiêng.
   - Đếm ngược **3 → 2 → 1** lớn giữa khung, bíp + rung mỗi giây; lệch khung →
     hủy ngay (safety reset). Về 0 → khóa nét và chụp.
   - Toggle **"Tự chụp sau 3s"**; nút CHỤP thủ công luôn sẵn.
4. **Xử lý ảnh: nắn về đúng khuôn mẫu + nén < 1MB** (`ImageProcessor`)
   - **`warpToTemplate()`**: biến đổi phối cảnh từ 4 tâm chấm đã dò tới **4 vị trí
     chấm chuẩn của mẫu** (`TemplateConfig.fiducialTarget`) → nắn thẳng ảnh nếu
     camera hơi nghiêng, xuất ra **đúng kích thước** và **đúng vị trí 4 chấm** như
     ảnh mẫu:

     | Mẫu | Kích thước ảnh ra | Ảnh mẫu tham chiếu |
     |---|---|---|
     | Mẫu 12 | **1061 × 1495 px** | `kq_12.png` |
     | Mẫu 10-11 | **1049 × 1657 px** | `kq_11_10.png` |

     Toạ độ 4 chấm lấy **thô** (không đối xứng hoá) bằng SIFT-homography khớp bản
     render PDF gốc 300dpi với chính 2 ảnh mẫu; kiểm chứng ECC: scale **1.0000**,
     lệch tâm **≤ 2.3 px** trên ảnh >1000px.
   - Cân bằng trắng gray-world → triệt bóng đổ (flat-field) → CLAHE kênh L → unsharp mask.
   - `compressImageUnder1MB()`: giảm dần JPEG quality 88 → 30 (bước 4) tới khi
     < 1024KB. **Không bao giờ thu nhỏ ảnh** — kích thước và toạ độ 4 chấm phải
     giữ nguyên tuyệt đối.
5. **Đặt tên & lưu** (`StorageHelper`)
   - `[Tiền tố]_[yyyyMMdd]_[HHmmss].jpg` — VD `Toan12A1_20261112_083015.jpg`.
   - Android 10+: MediaStore → `Pictures/OMR_Results/` (không cần quyền).
     Android 9-: `Android/data/<pkg>/files/Pictures/OMR_Results/`.
   - Kèm bản xem nhanh trong bộ nhớ trong của app.

## Cấu trúc mã nguồn

```
app/src/main/java/com/example/omrscanner/
├── OmrApp.kt                       # init OpenCV
├── TemplateSelectActivity.kt       # chọn mẫu + tiền tố tên ảnh (launcher)
├── MainActivity.kt                 # camera + HUD + đếm ngược 3s + pipeline chụp
├── ResultActivity.kt               # xem ảnh + tên file
├── camera/
│   ├── CameraManagerHelper.kt      # tự dò camera + fallback, resolution, focus đa điểm
│   └── FiducialDetectorAnalyzer.kt # dò 4 chấm góc realtime, góc nghiêng
├── imaging/ImageProcessor.kt       # enhance + warpToTemplate + nén <1MB
├── ui/CountdownTimerOverlayView.kt # HUD khung đỏ = khung ảnh ra + 4 ô mục tiêu + đếm ngược
├── storage/StorageHelper.kt        # tên file + MediaStore
├── omr/TemplateModel.kt            # SheetType + TemplateConfig (kích thước + vị trí 4 chấm)
└── util/ImageProxyExt.kt           # YUV_420_888 → Gray Mat, decode JPEG + xoay
```

`res/drawable-nodpi/template_mau12.jpg` / `template_mau1011.jpg` là thumbnail mẫu
giấy hiển thị ở panel trên (render 80dpi từ PDF gốc rồi warp về đúng khuôn ảnh
kết quả — 4 chấm sai số ≤ 0.25px).

## Build

1. Mở thư mục này bằng Android Studio (JDK 17).
2. Sync Gradle — OpenCV tự tải: `implementation("org.opencv:opencv:4.10.0")`.
3. `gradle assembleDebug` (splits ABI bật sẵn).

**APK đã build sẵn** tại `../apk/` — 4 biến thể thuật toán dò chấm (cài song song
được vì applicationId khác nhau):

| File (universal, mọi kiến trúc) | Tên app | Thuật toán |
|---|---|---|
| `OMR-Scanner-classic-…apk` | OMR Scanner Classic | Pipeline OpenCV cổ điển (fit-to-block) — **khuyến nghị, chính xác nhất** |
| `OMR-Scanner-tflite-…apk` | OMR Scanner TFLite | CNN keypoint tự huấn luyện chạy TensorFlow Lite (backup) |
| `OMR-Scanner-dnn-…apk` | OMR Scanner DNN | CNN keypoint + nút CHỤP mở Google Document Scanner |
| `OMR-Scanner-mlkit-…apk` | OMR Scanner MLKit | Nút CHỤP mở Google ML Kit Document Scanner |

**Luồng chụp khác nhau giữa các biến thể (v1.3):**
- **Classic / TFLite**: giữ luồng cũ — khung ngắm HUD → chụp CameraX → warp theo
  4 chấm → enhance → nén <1MB → màn kết quả.
- **DNN / MLKit** (v1.4 — bỏ hẳn màn HUD camera của app): từ màn chọn mẫu vào
  **thẳng Google ML Kit Document Scanner**. Google tự dò mép trang, nắn phẳng,
  cho Enhance/Filters/Crop; bấm **Next** → lưu nguyên JPEG vào
  `Pictures/OMR_Results/` theo tên `[Tiền tố]_ngày_giờ.jpg` → **tự mở lại
  scanner** cho phiếu kế tiếp (đếm số phiếu đã lưu trên màn hình). Bấm Back
  trong scanner → dừng vòng lặp, hiện màn "QUÉT PHIẾU" để tiếp tục.
  **Bỏ qua**: HUD camera, warp, enhance, nén, màn kết quả.

Mỗi biến thể còn có bản `arm64` nhẹ hơn cho điện thoại hiện đại. Pipeline cổ điển
luôn là chính; AI chỉ chèn khi cổ điển thất bại (chấm mờ/bóng/chói) và quad của
AI vẫn phải qua gate hình học + ngưỡng tin cậy heatmap (minPeak) trước khi nhận.
Model CNN (input 128×96, heatmap 24×32, ~213KB) huấn luyện trên 3200 ảnh tổng hợp
sinh từ 2 PDF gốc — sai số trung bình 8.3px @480×640, phụ trợ chứ không thay thế.

Bản debug (ký sẵn, cài trực tiếp). Cài đè: `adb install -r <file>.apk`.

## Sử dụng

1. Chọn mẫu phiếu + tiền tố tên ảnh → **Bắt đầu quét phiếu**.
2. Cấp quyền Camera. Panel trên hiển thị **ảnh mẫu giấy** đang chọn.
3. Đưa phiếu vào sao cho **4 chấm đen lọt vào 4 ô nhỏ** trong khung đỏ.
   HUD đỏ → vàng → **xanh lá** + đếm ngược 3-2-1 → tự chụp (nếu bật).
   Muốn chụp tay: tắt toggle và bấm **CHỤP**.
4. Ảnh ra đúng phần giấy trong khung đỏ, đúng kích thước mẫu
   (Mẫu 12: 1061×1495; Mẫu 10-11: 1049×1657); ảnh nằm ở `Pictures/OMR_Results/`.

## Ghi chú kỹ thuật

- Ảnh kết quả có **kích thước cố định theo mẫu** (Mẫu 12: 1061×1495; Mẫu 10-11:
  1049×1657), tiện đối chiếu hàng loạt. Toạ độ 4 chấm mục tiêu nằm trong
  `omr/TemplateModel.kt` (`fiducialTarget`) nếu cần tinh chỉnh.
- Ngưỡng nhận diện chấm đã nới cho ảnh chụp thật: solidity ≥ 0.88, fill ≥ 0.68,
  aspect 0.62–1.60; log chẩn đoán: `adb logcat -s FiducialDetector`.
- Trạng thái trên màn hình cho biết lý do chưa xanh: `(N/4 chấm)`, `nghiêng X°`,
  hoặc `Chưa thấy chấm định vị`.
