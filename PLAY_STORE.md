# Đưa QR Kiểm Kê lên Google Play

## 1. Việc phải làm trước khi build bản phát hành
1. **Đổi `applicationId`** trong `app/build.gradle.kts` (đang là `com.kiemke.qrxlsx`) thành tên riêng của bạn, ví dụ `vn.tencongty.qrkiemke`. Không đổi được sau khi đã đưa lên Play.
2. **Tạo khoá upload** (làm một lần, giữ file `.jks` và mật khẩu ở nơi an toàn, không đưa lên git):
   ```
   keytool -genkeypair -v -keystore upload-keystore.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
   ```
   Sao chép `keystore.properties.example` thành `keystore.properties` và điền thông tin.
3. **Build file AAB** (Play chỉ nhận AAB cho app mới, không nhận APK):
   ```
   ./gradlew bundleRelease
   ```
   Kết quả: `app/build/outputs/bundle/release/app-release.aab`.
4. Mỗi lần tải bản mới lên, **tăng `versionCode`** trong `app/build.gradle.kts`.
5. Nên chạy thử bản release trên máy thật (`./gradlew assembleRelease` rồi cài APK) vì bản release bật R8 thu nhỏ code.

Yêu cầu hiện hành của Google (kiểm tra ngày 18/11/2026): app mới và bản cập nhật phải target Android 16 (API 36), project đã đặt sẵn. Nguồn: [Play Console Help – target API level](https://support.google.com/googleplay/android-developer/answer/11926878).

## 2. Tài khoản nhà phát triển
- Đăng ký tài khoản Play Console (phí một lần 25 USD).
- Tài khoản cá nhân tạo sau 13/11/2023 phải chạy **closed test với ít nhất 12 tester, opt-in liên tục 14 ngày** rồi mới xin được quyền phát hành chính thức. Tài khoản tổ chức (có mã D-U-N-S) thường không bị yêu cầu này. Nguồn: [Play Console Help – app testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465). Hãy xem lại trang này lúc đăng ký vì Google có thể đổi quy định.

## 3. Điền trong Play Console (mục "App content")
| Mục | Khai báo gợi ý |
|---|---|
| Chính sách quyền riêng tư | Đăng `PRIVACY_POLICY.md` lên một URL công khai (GitHub Pages, Google Sites, website công ty) rồi dán link |
| Data safety | Không thu thập, không chia sẻ dữ liệu nào. App không có quyền Internet, dữ liệu chỉ xử lý trên máy |
| Quảng cáo | Không có quảng cáo |
| Đăng nhập / truy cập app | Không cần tài khoản |
| Đối tượng | Từ 18 tuổi trở lên (công cụ nghiệp vụ), không hướng tới trẻ em |
| Xếp hạng nội dung | Điền bảng câu hỏi, tất cả đều "Không" |
| Danh mục | Công cụ (Tools) hoặc Kinh doanh (Business) |

## 4. Thông tin trang cửa hàng
- Biểu tượng 512×512: `store/icon-512.png`
- Ảnh bìa 1024×500: `store/feature-graphic-1024x500.png`
- Ảnh chụp màn hình điện thoại: tối thiểu 2 ảnh. Hãy chụp từ máy thật: màn chọn cột, màn hiển thị QR. **Dùng dữ liệu mẫu, không chụp dữ liệu nội bộ thật.**
- Mô tả ngắn (≤80 ký tự): `Tạo mã QR từ file Excel để quét kiểm kê, từng mã một, hoạt động offline.`
- Mô tả đầy đủ gợi ý:
  > Dành cho người kiểm kê hàng hoá. Mở file Excel (.xlsx), chọn cột mã sản phẩm hoặc IMEI, app hiển thị mã QR lớn để bạn quét vào hệ thống kiểm kê. Bấm "Tiếp" hoặc phím âm lượng để sang mã kế tiếp. Có thể hiện thêm tên sản phẩm, số lượng, IMEI dưới mã QR. App hoạt động hoàn toàn offline, không gửi dữ liệu đi đâu, không cần tài khoản.

## 5. Tên app và thương hiệu
Tên hiển thị là "QR Kiểm Kê". Hãy tra cứu trên Play xem đã có app trùng tên chưa. "QR Code" là nhãn hiệu của Denso Wave, nên nếu cần hãy ghi "mã QR" trong mô tả cho phù hợp thông lệ.

## 6. Gỡ lỗi trước khi nộp
- Mở **Android Studio → Build → Analyze APK** trên bản release, kiểm tra tab Manifest không có `INTERNET`.
- Dùng **Pre-launch report** của Play (tự chạy khi tải bản test lên) để bắt lỗi crash trên nhiều máy.
