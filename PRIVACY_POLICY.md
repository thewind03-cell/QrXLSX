# Chính sách quyền riêng tư – QRcode Kiểm Kê

Cập nhật lần cuối: 20/11/2026

**Tác giả / Đơn vị phát hành:** Thanh Phong Do
**Liên hệ:** thewind03@gmail.com

## Dữ liệu chúng tôi thu thập
QRcode Kiểm Kê **không thu thập, không gửi ra ngoài và không chia sẻ** bất kỳ dữ liệu cá nhân hay dữ liệu nghiệp vụ nào của bạn.

## Cách ứng dụng xử lý và lưu trạng thái phiên trên thiết bị
- Khi bạn chọn một file Excel (.xlsx), ứng dụng đọc file đó **ngay trên thiết bị** để tạo mã QR. Ứng dụng chỉ truy cập file bạn chọn thông qua bộ chọn file của Android (`OpenDocument`), không xin quyền truy cập toàn bộ bộ nhớ.
- Mã QR được tạo hoàn toàn bằng mã nguồn nằm trong ứng dụng, không gọi dịch vụ bên ngoài.
- Bản sao tạm dùng khi giải nén file được xoá ngay sau khi đọc xong.
- Để khôi phục phiên làm việc nếu ứng dụng bị đóng đột ngột (tính năng Time Machine), ứng dụng lưu **cục bộ trong thư mục riêng của app** (`filesDir/timemachine/state.json`) các thông tin: đường dẫn URI đã cấp quyền đọc, tên file, kích thước, thời điểm sửa, chỉ số sheet/cột đã chọn, vị trí mã đang xem (cùng tối đa 20 mốc lịch sử vị trí gần nhất) và mức độ sáng màn hình.
- Ứng dụng **không bao giờ lưu nội dung dữ liệu kiểm kê** (mã QR, tên sản phẩm, số lượng, IMEI) xuống bộ nhớ lưu trữ; nội dung này chỉ nằm tạm trong RAM khi bạn đang mở phiên.
- Dữ liệu khôi phục hoàn toàn nằm trên thiết bị của bạn, không gửi đi đâu. Bạn có thể xoá sạch dữ liệu khôi phục và thu hồi toàn bộ quyền truy cập file đã nhớ bất cứ lúc nào bằng nút **"Xoá dữ liệu đã lưu"** trên màn hình thiết lập.

## Kết nối mạng
Ứng dụng **không khai báo quyền Internet** và không thể gửi dữ liệu ra ngoài thiết bị.

## Bên thứ ba
Ứng dụng không dùng quảng cáo, công cụ phân tích hay SDK của bên thứ ba.

## Trẻ em
Ứng dụng là công cụ nghiệp vụ, không nhắm tới trẻ em.

## Thay đổi chính sách
Nếu có thay đổi, chúng tôi sẽ cập nhật tại trang này và ghi rõ ngày cập nhật.
