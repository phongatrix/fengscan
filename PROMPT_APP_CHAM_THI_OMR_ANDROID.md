# 📱 PROMPT THIẾT KẾ & PHÁT TRIỂN ỨNG DỤNG ANDROID: OMR SCANNER & GRADER CAMERA

> **Mục tiêu:** Xây dựng ứng dụng Android Native hoàn chỉnh dùng Camera quét, tự động định vị theo các chấm đen góc của phiếu trắc nghiệm, hỗ trợ chọn mẫu phiếu, đếm ngược tự động 3 giây khi khớp khung, tối ưu ảnh, cho phép đặt tên ảnh tùy biến theo thời gian hiện hành và đảm bảo dung lượng ảnh lưu trữ/xuất ra luôn dưới 1MB.

---

```markdown
Bạn là Kỹ sư Android Native Cấp cao (Senior Android Developer - Kotlin, CameraX) kết hợp Chuyên gia Thị giác Máy tính (OpenCV Android SDK).
Hãy thiết kế và viết toàn bộ mã nguồn cho ứng dụng Android: "OMR Sheet Camera Scanner & Grader" - Ứng dụng chụp và chấm bài thi trắc nghiệm tự động theo thời gian thực.

---

### 1. QUẢN LÝ MẪU PHIẾU THI (TEMPLATE SELECTION)
Trước khi vào màn hình Camera, ứng dụng cung cấp giao diện cho phép người dùng chọn mẫu phiếu:
1. **Mẫu 1 (Phiếu kiểm tra định kỳ mới):** 
   - Cấu trúc: Phần I (30 câu trắc nghiệm), Phần II (10 câu Đúng/Sai), Phần III (10 câu trả lời ngắn/điền số). Khung Điểm/Số phách nằm góc trái.
   - Tập hợp các chấm đen định vị (Fiducial Markers) ở 4 góc chính và các chấm mốc phân vùng.
2. **Mẫu 2 (Phiếu trả lời trắc nghiệm chuẩn Bộ GD&ĐT 2025):**
   - Cấu trúc: Số báo danh & Mã đề ở góc trên phải; Phần I (40 câu trắc nghiệm 4 lựa chọn), Phần II (8 câu Đúng/Sai), Phần III (6 câu trả lời ngắn điền số). Vạch mã vạch đồng bộ ở mép phải.
3. **Cơ chế nạp cấu hình mẫu (Template Configuration):**
   - Mỗi mẫu được định nghĩa bởi một file cấu hình JSON/Data Class gồm:
     + Tỷ lệ khung hình chuẩn (Aspect ratio A4 ~ 1:1.414).
     + Tọa độ tương đối (% x, % y) của 4 chấm đen định vị chính ở 4 góc.
     + Bản đồ tọa độ lưới bong bóng OMR (Coordinates Map cho SBD, Mã đề, Phần I, II, III).
   - Khi chọn mẫu nào, Camera HUD và bộ xử lý OMR sẽ tải chính xác cấu hình của mẫu đó.

---

### 2. TÍNH NĂNG CHỤP & CƠ CHẾ ĐẾM NGƯỢC TỰ ĐỘNG 3S (3S AUTO-TIMER)
1. **Chế độ chụp:**
   - Cung cấp nút chuyển đổi (Toggle Switch) trên màn hình Camera: **"Chụp tự động sau 3s"** (Bật/Tắt).
   - Vẫn giữ nút bấm chụp thủ công (Manual Shutter Button) khi người dùng muốn chủ động chụp.
2. **Luồng đếm ngược tự động 3 giây (3-Second Auto-Countdown):**
   - Khi 4 chấm đen của phiếu thi khớp hoàn toàn vào 4 ô mục tiêu trên màn hình (độ lệch < ngưỡng cho phép và góc nghiêng camera < 5°):
     + Khung định vị chuyển sang màu **Xanh Lá Cây** (Green Glow).
     + Nếu tính năng "Chụp tự động sau 3s" đang BẬT: Bắt đầu kích hoạt đồng hồ đếm ngược lớn giữa màn hình: `3... 2... 1...`.
     + Kèm hiệu ứng rung nhẹ (Haptic Feedback) hoặc âm thanh "bíp" ngắn mỗi giây.
     + **Cơ chế an toàn (Safety Reset):** Nếu trong 3 giây này người dùng rung tay làm lệch 4 chấm đen ra khỏi khung định vị -> Lập tức hủy đếm ngược và chuyển khung viền về màu Đỏ/Vàng để căn chỉnh lại.
     + Khi đếm về 0 và vẫn giữ vững khung: Tự động khóa nét (AF Lock) và kích hoạt lệnh chụp ảnh (`takePicture`).

---

### 3. KIẾN TRÚC & PHẦN CỨNG CAMERA (ANDROID CAMERAX)
1. **Tự động nhận diện Camera (Auto-Detect Camera):**
   - Quét phần cứng thiết bị, tự động chọn Camera sau chính (Main Rear Wide Camera).
   - Loại trừ camera góc siêu rộng (Ultra-wide) để tránh biến dạng méo hình cầu (Fisheye Distortion) làm sai lệch vị trí bong bóng OMR.
2. **Tự động xác định độ phân giải khả dụng (Resolution Selector):**
   - Quét danh sách kích thước hỗ trợ từ `StreamConfigurationMap`.
   - Tự động chọn độ phân giải chụp có tỷ lệ 4:3 (chuẩn giấy A4) ở mức tối ưu từ 3MP đến 5MP (1920x1440 đến 2560x1920).
   - Tách biệt luồng Preview 60fps/30fps và luồng ImageCapture độ nét cao.
3. **Tự động lấy nét thông minh theo vùng định vị (Dynamic Auto-Focus & Metering):**
   - Kích hoạt chế độ lấy nét liên tục `CONTROL_AF_MODE_CONTINUOUS_PICTURE`.
   - Khi 4 chấm đen đi vào vùng ngắm, tự động gửi `FocusMeteringAction` để lấy nét và cân bằng sáng đa điểm (3A: AF, AE, AWB) trực tiếp vào toàn bộ mặt phẳng phiếu thi, triệt tiêu vùng mờ nhòe ở các góc biên.

---

### 4. NHẬN DIỆN 4 CHẤM ĐEN ĐỊNH VỊ THỜI GIAN THỰC (REAL-TIME OPENCV)
1. **Phân tích khung hình (`ImageAnalysis`):**
   - Chuyển đổi khung hình Preview (YUV_420_888) sang OpenCV `Mat` dạng Grayscale.
   - Áp dụng Adaptive Thresholding để tìm các khối màu đen đặc.
   - Tìm Contours và lọc theo tiêu chuẩn chấm mốc định vị (Fiducial Marker):
     + Tỷ lệ cạnh (Aspect ratio) vuông xấp xỉ 1:1.
     + Độ đặc (Solidity/Extent) xấp xỉ 1.0 (khối vuông đen đặc, không rỗng ruột).
     + Diện tích nằm trong ngưỡng kích thước chấm mốc của mẫu phiếu đã chọn.
2. **Xác định 4 góc & Đo sai số phối cảnh:**
   - Định danh 4 chấm mốc: Top-Left, Top-Right, Bottom-Left, Bottom-Right.
   - So sánh khoảng cách và độ lệch góc giữa 4 chấm thực tế với 4 ô vuông mục tiêu trên HUD.

---

### 5. TỐI ƯU HÓA HÌNH ẢNH & ĐẢM BẢO FILE < 1MB (< 1024 KB)
1. **Nắn phẳng phối cảnh (Perspective Correction):**
   - Dùng tọa độ tâm 4 chấm đen góc tìm được để gọi `Imgproc.getPerspectiveTransform` và `Imgproc.warpPerspective`.
   - Kết quả xuất ra bức ảnh phẳng 100% đúng tỷ lệ chuẩn của hình mẫu (loại bỏ hoàn toàn góc chụp nghiêng).
2. **Tối ưu hóa hình ảnh (Image Enhancement):**
   - Tự động cân bằng trắng (White Balance), làm sáng nền giấy trắng.
   - Áp dụng bộ lọc tăng độ tương phản nhẹ để làm rõ nét mực bút chì tô và triệt tiêu bóng đổ của bàn tay/điện thoại.
3. **Thuật toán nén ảnh đảm bảo file < 1MB (Adaptive Quality Compression):**
   - Đưa kích thước ảnh sau khi nắn phẳng về chiều rộng tối ưu: 1400px - 1600px (đảm bảo cực rõ nét từng bong bóng OMR).
   - Sử dụng vòng lặp nén thông minh:
     + Bắt đầu nén JPEG ở mức Quality = 85%.
     + Kiểm tra dung lượng byte xuất ra. Nếu `fileSize >= 1024 * 1024 bytes (1MB)`, tự động giảm Quality theo bước nhảy 5% (80%, 75%,...) cho đến khi dung lượng nhỏ hơn 1MB thì dừng lại và lưu file.

---

### 6. XUẤT ẢNH KẾT QUẢ CHẤM BÀI (OMR RESULT RENDERING)
Dựa trên ảnh phẳng chuẩn đã cắt cúp:
1. Xác định tọa độ bong bóng OMR theo Mẫu phiếu đã chọn.
2. Đo mật độ điểm ảnh (Pixel Density) để nhận diện đáp án đã tô.
3. Vẽ đồ họa trực tiếp lên ảnh kết quả:
   - Khoanh tròn màu xanh lá cây (`Color.GREEN`, viền tròn rõ nét) bao quanh các ô đáp án đã chọn (như hình kết quả 3, 4, 5, 6).
   - In khối văn bản thông tin kết quả màu đỏ (`Color.RED`, font monospace/pixelated in đậm) ở góc trên bên trái:
     ```
     MÃ ĐỀ: [Mã đề]
     SBD:   [Số báo danh]
     P1:    [Số câu đúng/Tổng câu] = [Điểm P1]
     P2:    [Điểm P2]
     P3:    [Điểm P3]
     TỔNG:  [Tổng điểm]
     ```

---

### 7. QUY TẮC ĐẶT TÊN ẢNH TÙY BIẾN VÀ LƯU TRỮ (CUSTOM FILE NAMING & STORAGE)
1. **Giao diện nhập tiền tố tên ảnh (Prefix Input):**
   - Trên màn hình chọn mẫu hoặc thanh công cụ Camera, cung cấp trường nhập văn bản (TextField/Dialog): **"Tiền tố tên ảnh"** (Prefix).
   - Cho phép người dùng nhập tiền tố nhận dạng (ví dụ: `A`, `Toan11`, `12A1`,...).
   - Nếu để trống, hệ thống mặc định dùng tiền tố: `OMR`.
2. **Quy tắc sinh tên file tự động (Auto Timestamp Naming):**
   - Khi chụp thành công, tên file ảnh được sinh tự động theo định dạng:
     `[Tiền_tố]_[yyyyMMdd]_[HHmmss].jpg` (hoặc `[Tiền_tố][yyyyMMdd][HHmmss].jpg`).
   - Ví dụ:
     + Đặt tên là `A`, chụp lúc 08 giờ 30 phút 15 giây ngày 12/11/2026 -> Tên ảnh là: `A_20261112_083015.jpg` (hoặc `A20261112083015.jpg`).
     + Đặt tên là `Toan12A1` -> `Toan12A1_20261112_083015.jpg`.
3. **Cơ chế lưu trữ (Scoped Storage):**
   - Lưu trữ an toàn tuân thủ Android 11+ (Scoped Storage / MediaStore API) vào thư mục `Pictures/OMR_Results/` hoặc thư mục riêng của ứng dụng.
   - Hỗ trợ lưu 2 phiên bản (tùy chọn trong cài đặt):
     + `[Tên_file]_raw.jpg`: Ảnh gốc đã nắn phẳng.
     + `[Tên_file]_graded.jpg`: Ảnh đã vẽ khoanh tròn đáp án và in điểm số màu đỏ.

---

### 8. YÊU CẦU MÃ NGUỒN CUNG CẤP (KOTLIN)
Hãy tổ chức mã nguồn rõ ràng theo các file sau:
1. `TemplateModel.kt`: Khai báo Data Class cấu hình mẫu phiếu 1 & 2 (tọa độ các chấm đen góc và bản đồ ô OMR).
2. `CameraManagerHelper.kt`: Cấu hình CameraX, tự động chọn camera chính, tự quét độ phân giải 4:3, cấu hình CAF và cơ chế lấy nét vùng `focusOnArea()`.
3. `FiducialDetectorAnalyzer.kt`: ImageAnalysis xử lý OpenCV nhận diện 4 chấm đen góc, kiểm tra độ khớp và điều khiển cờ trạng thái đếm ngược.
4. `CountdownTimerOverlayView.kt`: Custom View vẽ lưới định vị 4 góc đổi màu (Đỏ/Vàng -> Xanh lá) và hiển thị chữ số đếm ngược 3s to rõ ràng kèm hiệu ứng rung.
5. `ImageProcessor.kt`: Hàm nắn phẳng (Warp Perspective), hàm tăng tương phản và hàm nén thích ứng `compressImageUnder1MB(bitmap: Bitmap): ByteArray`.
6. `StorageHelper.kt`: Quản lý sinh tên file theo tiền tố `[Prefix]_[yyyyMMdd]_[HHmmss].jpg` và lưu trữ ảnh an toàn với dung lượng < 1MB.
```
