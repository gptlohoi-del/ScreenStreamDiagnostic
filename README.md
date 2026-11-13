# RTSP Diagnostic — Android 9 (ZTE 901ZT)

Mã nguồn **ứng dụng RTSP Server mới**, tự triển khai bằng Android SDK Java, **không phải bản fork của ScreenStream gốc**. Tạo ra để hỗ trợ chẩn đoán mất kết nối RTSP trên **ZTE 901ZT Android 9**, với 1–5 client VLC trong cùng mạng Wi-Fi.

## Chức năng v0.1.0

- Chia sẻ màn hình qua Android MediaProjection, mã hóa **H.264** với MediaCodec surface input.
- RTSP Server `rtsp://<IP_điện_thoại>:8554/live`.
- Hỗ trợ RTSP/RTP **TCP interleaved** hoặc **UDP unicast**, 1–5 phiên cùng lúc. Chỉ video, không âm thanh.
- 3 mức bitrate (1/2/3 Mbps), 15/20/25 fps, thay đổi kích thước.
- Foreground Service có thông báo, wakelock CPU và Wi-Fi khi đang truyền.
- Log xoay vòng (tối đa ~4 MiB), ghi sự kiện RTSP, SETUP/PLAY/TEARDOWN, IP client, lỗi kết nối, encoder, số frame, queue, byte gửi, mức tín hiệu Wi-Fi, nhiệt độ pin, RAM.
- Kiểm tra pin, tối ưu hóa pin/Doze, Wi-Fi, codec H.264, bộ nhớ, khóa màn hình; cảnh báo trên giao diện.
- Nút **Đánh dấu sự cố** và **Xuất ZIP nhật ký** bằng Android document picker. Không lưu ảnh màn hình, không tự gửi dữ liệu lên mạng.
- GitHub Actions tự động build APK sử dụng `android-actions/setup-android@v4`.

## Tạo repository trên GitHub và build APK

1. Tạo **repository trống** (ví dụ `rtsp-diagnostic`), chọn nhánh `main` và không tạo README từ giao diện GitHub.
2. Giải nén `ScreenStream-Diagnostic.zip` trên máy tính, mở terminal trong thư mục chứa `README.md`.
3. Chạy:

   ```bash
   git init
   git add .
   git commit -m "Initial RTSP diagnostic Android app"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/rtsp-diagnostic.git
   git push -u origin main
   ```

4. Trên GitHub mở **Actions → Android 9 RTSP Diagnostic APK → Run workflow** (hoặc đợi workflow tự chạy khi push).
5. Trong bản chạy thành công, chọn **Artifacts → RTSP-Diagnostic-Android9-Debug-APK**, tải ZIP artifact, giải nén `app-debug.apk`, chuyển sang ZTE 901ZT và cài đặt.
6. Có thể tạo Git tag `v0.1.0` rồi push để tạo GitHub Release cùng APK. Workflow yêu cầu `contents: write` để tạo GitHub Release; nếu chính sách của tổ chức chặn quyền ghi thì cần cho phép workflow phát hành Releases.

**Chú ý về ký số:** APK này được **ký tự động bằng khóa debug** của runner GitHub. Khóa debug có thể khác nhau qua các lần chạy, vì vậy khi cài bản mới có thể phải gỡ bản cũ (nhớ xuất log trước). Không dùng APK debug này để phát hành lâu dài. Để cập nhật mà không mất dữ liệu cần cấu hình keystore cố định và release signing ở bước tiếp theo.

## Sử dụng

1. Điện thoại phát và các client cùng Wi-Fi. Tắt tiết kiệm pin, mở quyền chạy nền (đặc biệt trên ROM ZTE/SoftBank) nếu cần.
2. Mở RTSP Diagnostic → **Kiểm tra lại các cài đặt Android**. Mục nào có `[CẢNH BÁO]` thì xem xét điều chỉnh.
3. Chọn 720p, 20 FPS, 2 Mbps để thử; bấm **Bắt đầu phát** và đồng ý cửa sổ chụp màn hình Android.
4. Trên điện thoại client, dùng VLC mở `rtsp://<IP>:8554/live` (IP hiển thị tại app).
5. Nếu một client thoát: **ngay tại điện thoại phát**, nhấn **Đánh dấu sự cố**, đợi khoảng 10 giây rồi **Xuất ZIP nhật ký**.
6. Gửi ZIP để phân tích `CLIENT DISCONNECT`, `SEND_ERROR`, `SLOW_CLIENT`, `ENCODER_STALL`, `NETWORK LOST` và các `HEALTH` snapshot trước/sau sự cố.

### Tính năng cảnh báo và hạn chế

- Android 9 **không cung cấp API công khai** để đọc mọi tùy chọn tự khởi động/tiết kiệm pin riêng của ZTE/SoftBank. App có nút mở cài đặt ứng dụng để người dùng kiểm tra thủ công.
- **Client VLC đóng** không nhất thiết có nghĩa server lỗi: kiểm tra log và các máy khác. TCP socket send thành công không chứng minh VLC đã giải mã được; UDP không có ACK nên không biết chính xác client có nhận từng gói hay không.
- Nếu địa chỉ IP Wi-Fi thay đổi khi đang phát, người dùng nên dừng/bắt đầu lại server. Không dùng mạng công cộng: RTSP **không có mật khẩu/TLS** trong bản thử nghiệm.
- MediaProjection trên Android cần người dùng chấp thuận ở đầu phiên. Nếu Android hủy phiên, không được tự khởi động lại mà không xin phép.
- Android 9 có thể có vấn đề codec đặc thù thiết bị; đây là bản MVP cần **kiểm thử thực tế trên ZTE 901ZT và VLC**, chưa thể cam kết hoạt động ổn định hoặc chữa hết các lỗi của ScreenStream.
- Hiện server chưa hỗ trợ âm thanh, RTSP authentication, multicast, RTP RTCP Sender Reports, tùy chỉnh kênh RTP multicast hoặc đổi hướng màn hình động. Khóa xoay màn hình trước khi phát để tránh hình sai kích thước.
- Mỗi build từ GitHub cần internet và tải Android SDK/Gradle. Mã nguồn không cần thiết bị USB để biên dịch.

## Công nghệ

Android Java SDK, minSdk 26, targetSdk 32 (Android 9 là API 28), compileSdk 35, AGP 8.7.3, Gradle 8.9, JDK 17. Không có phụ thuộc runtime ngoài Android SDK.

## Kiểm thử khuyến nghị

- 1 client TCP trong 30 phút; 5 client TCP trong 30 phút; thử từng client đóng riêng.
- UDP và TCP; Wi-Fi 2.4 GHz và 5 GHz; bật/tắt pin tiết kiệm, khóa màn hình; ghi nhật ký cho từng sự cố.
- Kiểm tra hình đứng yên và video chuyển động mạnh; kiểm tra chất lượng trên ZTE 901ZT.

## Giấy phép

MIT License cho mã nguồn viết mới trong gói này. Không sao chép mã nguồn hoặc logo ScreenStream chính thức.
