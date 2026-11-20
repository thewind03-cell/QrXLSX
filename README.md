# QRcode Kiểm Kê

Ứng dụng Android nhẹ: đọc file `.xlsx`, tạo QR từ cột bạn chọn, bấm nút để sang mã tiếp theo.
Hoạt động hoàn toàn offline: không có quyền Internet, không dùng thư viện QR ngoài.

## Tính năng chính
1. **Chọn file & cột linh hoạt:** Chọn sheet, tick "Dòng đầu là tiêu đề", chọn cột tạo QR và tối đa 3 cột hiển thị thêm (Tên sản phẩm, Số lượng, IMEI). Tự động gợi ý cột theo tiêu đề.
2. **Hiển thị tối ưu cho máy quét:**
   - Phím âm lượng xuống / lên hoặc nút **Tiếp ▶ / ◀ Trước** để chuyển mã.
   - Bấm vào số thứ tự (vd `3 / 120`) để nhảy tới mã bất kỳ; giữ màn hình luôn sáng.
   - Điều chỉnh độ sáng 25% / 50% / 75% / 100% trực tiếp trên màn hình QR (mặc định 50%).
3. **Nhiều tab (tối đa 3 tab):** Mở song song nhiều file/sheet, mỗi tab giữ nguyên vị trí mã đang xem, có nút chuyển nhanh `⇄` giữa tab hiện tại và tab trước đó.
4. **Time Machine (khôi phục phiên):** Tự động lưu metadata tiến độ cục bộ trong `filesDir/timemachine/state.json` (tuyệt đối không lưu nội dung mã/tên/IMEI) kèm tối đa 20 mốc lịch sử vị trí cho mỗi tab; có nút xoá dữ liệu đã lưu và thu hồi quyền URI.

## Build
Cần Android Studio mới nhất (hỗ trợ AGP 8.11 và API 36) và JDK 17 trở lên.
- Bản thử: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Bản phát hành (APK): `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
- Bản phát hành lên Play: xem `PLAY_STORE.md` (`./gradlew bundleRelease` ra file `.aab`).

## Cấu trúc
- `QrEncoder.java`: bộ mã hoá QR (ISO/IEC 18004) tự viết, thuần Java, không phụ thuộc gì. Version 1–40, 4 mức sửa lỗi, tự chọn chế độ numeric / alphanumeric / byte (UTF-8).
- `XlsxReader.kt`: đọc xlsx bằng `ZipFile` + `XmlPullParser`, IMEI lưu dạng số (`3.5E+14`) được khôi phục đầy đủ.
- `Models.kt`, `TabState.kt`, `TimeMachine.kt`, `AppViewModel.kt`: quản lý trạng thái nhiều tab và lưu/khôi phục phiên offline.
- `MainActivity.kt`, `SetupScreen.kt`, `DisplayScreen.kt`, `QrView.kt`: giao diện Jetpack Compose, vẽ QR bằng Canvas với ô là số nguyên pixel.

## Kiểm chứng bộ QR
Bộ mã hoá được thử với hơn 2000 chuỗi (mọi version 1–40, cả 4 mức sửa lỗi, số / chữ hoa / UTF-8) và giải mã lại bằng hai bộ giải mã độc lập (OpenCV, zbar): tất cả đều đúng.
Lưu ý: chuỗi chứa ký tự có dấu được mã hoá UTF-8 không kèm ECI (cách phổ biến); máy quét cần đọc UTF-8. Mã sản phẩm và IMEI (ASCII) không bị ảnh hưởng.

## Bản quyền
Copyright © 2026 **Thanh Phong Do** (`thewind03@gmail.com`). All rights reserved.
Xem chi tiết tại [LICENSE](LICENSE).
