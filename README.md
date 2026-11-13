# RTSP Diagnostic 0.2.0 — Android 9+ / ZTE 901ZT / RC069L

Ứng dụng Android Java **phát màn hình trực tiếp qua RTSP** (H.264) cho 1–5 thiết bị VLC trong cùng LAN; có **lựa chọn truyền âm thanh AAC**, kiểm tra Android/Wi-Fi, nhật ký theo phiên client và nút đánh dấu sự cố. Mã nguồn dựa trên dự án thử nghiệm RTSP Diagnostic trước đó trong cuộc trò chuyện; **không** phải fork hay bản chính thức của ScreenStream.

> **Trạng thái:** mã nguồn thử nghiệm. Đã kiểm tra cấu trúc repo và kiểm tra Java/RTP độc lập nơi có thể, nhưng **chưa chạy được một bản build Gradle Android hoặc kiểm thử trên ZTE 901ZT/RC069L thật trong môi trường tạo repo**. GitHub Actions là bước xác nhận build chính thức. Có thể phát sinh vấn đề tùy ROM, codec, VLC, mạng hoặc phiên bản Android.

## Nền tảng và âm thanh

| Hệ điều hành | Video H.264 | Không âm thanh | Micro AAC | Âm thanh phát từ ứng dụng AAC |
|---|---|---|---|---|
| Android 9 / API 28 (ZTE 901ZT) | Có | Có (mặc định) | Có (cấp quyền `RECORD_AUDIO`) | **Không** (API chưa hỗ trợ) |
| Android 12 / API 31 (RC069L) | Có | Có | Có | Có, **chỉ khi ứng dụng nguồn cho phép bắt âm thanh** |
| Android 10+ khác | Dự kiến | Có | Có | Tùy chính sách Android/ứng dụng nguồn/ROM |

- Chỉ chọn **một** nguồn âm thanh tại một thời điểm: Tắt / Micro / Âm thanh ứng dụng. Không trộn hai nguồn.
- Âm thanh ứng dụng cần Android 10+ và sự đồng ý MediaProjection + `RECORD_AUDIO`. Một số ứng dụng/nhóm nội dung không cho phép ghi âm lại nên phía client có thể im lặng dù RTSP vẫn phát video.
- Video H.264/AAC-LC mono 44.1 kHz, AAC 64 kbps; RTSP video trackID=0, audio trackID=1, RTP over TCP interleaved hoặc UDP unicast (do VLC yêu cầu).
- **LAN tin cậy**: RTSP mặc định không có xác thực hay TLS. Không mở port 8554 ra Internet.

## Giao diện một màn hình

Màn hình chính có địa chỉ `rtsp://<IPv4-WiFi-của-máy-phát>:8554/live`, tình trạng phát, nút **Phát / Dừng / Đánh dấu sự cố**, và bốn khối mở/đóng bằng cách nhấn:

1. **Cấu hình hình ảnh & âm thanh**: 15–25 FPS, 1–3 Mbps, chất lượng 100/75/50%, nguồn âm thanh.
2. **Client / trạng thái RTSP**: số kết nối, RTSP session ID, track TCP/UDP, byte gửi, dropped, thời gian gửi video cuối.
3. **Kiểm tra Android / cảnh báo**: tiết kiệm pin, Doze, khóa/tắt màn hình, Wi-Fi/RSSI, pin/nhiệt, bộ nhớ, tự xoay, timeout màn hình, quyền micro và dữ liệu nền. Không thể tự đọc tối ưu pin riêng của ZTE, client isolation router hay tín hiệu tại client.
4. **Nhật ký và xuất ZIP**: log hai file xoay vòng, mốc sự cố, báo cáo thiết bị.

## Sửa đổi liên quan lỗi mất kết nối

- Cố gắng bật `MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER = 500000` µs cho encoder Surface để giảm khoảng trống video khi màn hình tĩnh. Nếu ROM/codec từ chối cấu hình tùy chọn này, thử lại không có nó và ghi `REPEAT_PREVIOUS_FRAME_UNSUPPORTED`.
- Khi client bắt đầu `PLAY` hoặc hàng đợi tràn, yêu cầu encoder phát keyframe (`PARAMETER_KEY_REQUEST_SYNC_FRAME`) với giới hạn tần suất.
- Ghi `WAITING_KEYFRAME`, `FIRST_VIDEO_KEYFRAME`, `SLOW_CLIENT`, `AUDIO_FAILED`, `ENCODER_STALL` cùng số liệu theo session. Không suy đoán nguyên nhân VLC nếu không có dữ liệu.
- Android phiên bản mới cần người dùng cho phép MediaProjection mỗi phiên; không cố tự vượt qua hệ thống.

## GitHub: đưa toàn bộ repo lên và tạo APK

1. Tạo repository trống, ví dụ `ScreenStreamDiagnostic`, trên GitHub.
2. Upload **nội dung bên trong thư mục repo này** lên repository, **bao gồm thư mục ẩn `.github/workflows`**; không upload file ZIP làm tệp duy nhất. Có thể dùng giao diện GitHub **Add file → Upload files** hoặc `git add .; git commit; git push`.
3. Trong GitHub, mở **Actions → Build RTSP Diagnostic APK → Run workflow** (hoặc push nhánh `main`).
4. Workflow sử dụng chính xác `android-actions/setup-android@v4`, JDK 17, Gradle 8.9, Android API 35, chạy `:app:testDebugUnitTest` rồi `:app:assembleDebug`.
5. Tải artifact `RTSPDiagnostic-v0.2.0-debug`, giải nén và cài `RTSPDiagnostic-v0.2.0-debug.apk`.
6. Tag `v0.2.0` trên GitHub để workflow tạo GitHub Release và gắn APK tương ứng.

**Lưu ý về chữ ký APK:** APK debug do runner GitHub tự ký bằng debug keystore; các lần build có thể dùng **khóa khác nhau**, dẫn tới lỗi cập nhật ứng dụng. Nếu không cài đè được, xuất log trước rồi gỡ phiên bản cũ (gỡ app sẽ xóa nhật ký nội bộ), sau đó cài bản mới. Để cập nhật dài hạn không mất dữ liệu, sử dụng APK release với **một khóa ký cố định**.

### Tùy chọn ký release bằng GitHub Secrets

Tạo keystore JKS của bạn và cất an toàn (đừng commit keystore hoặc mật khẩu vào repo). Thêm 4 **Actions secrets** trong repository:

| Secret | Nội dung |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Nội dung JKS mã hóa base64 một dòng |
| `ANDROID_KEYSTORE_PASSWORD` | Mật khẩu kho khóa |
| `ANDROID_KEY_ALIAS` | Tên alias |
| `ANDROID_KEY_PASSWORD` | Mật khẩu của alias |

Ví dụ tạo khóa và lấy base64 trên máy cục bộ (JDK có `keytool`):

```bash
keytool -genkeypair -keystore rtsp-release.jks -alias rtsp -keyalg RSA -keysize 3072 -validity 10000
base64 -w0 rtsp-release.jks
```

Khi có đủ bốn secrets, workflow **tự tạo thêm** `RTSPDiagnostic-v0.2.0-release.apk` đã ký. Chỉ bản release dùng **cùng keystore + alias + applicationId** qua các phiên bản mới cài đè được ổn định. Với GitHub Actions có thể dùng `Settings → Secrets and variables → Actions` để thêm secrets.

## Cách sử dụng và phân tích sự cố

- Kết nối máy phát và các client vào cùng Wi-Fi/LAN, tắt cô lập client trên AP nếu có.
- Mở RTSP Diagnostic, thử trước **Tắt âm thanh**, 720p/20fps/2Mbps và 1–2 client, sau đó tăng lên 5.
- Bấm Phát, chấp thuận chia sẻ màn hình. VLC mở URL hiển thị (VD `rtsp://192.168.1.5:8554/live`). Trong VLC ưu tiên thử RTP over RTSP (TCP).
- Khi mất hình/đóng VLC, bấm **Đánh dấu client vừa bị thoát** rồi mở **Nhật ký và xuất ZIP**, lưu lại file. Chụp/ghi nhận giờ chính xác và IP client.
- Nếu muốn truyền âm thanh, cấp quyền micro và chọn **Micro** hoặc **Âm thanh ứng dụng** (chỉ Android 10+), sau đó khởi động lại phiên. Kiểm tra xem VLC có chọn cả hai track không.

## Giới hạn hiện tại

- Có RTCP Sender Reports cơ bản mỗi 5 giây, nhưng chưa có phân tích RTCP Receiver Reports, xác thực RTSP, TLS, phục hồi Wi-Fi/IP tự động, tái cấu hình khi xoay màn hình, trộn âm thanh, tự nối lại phía VLC hoặc kiểm thử tương thích mọi Android.
- Thu âm nội bộ có thể bị nguồn phát giới hạn theo chính sách Android; không thể buộc thu âm ứng dụng từ chối.
- Thành công khi `DatagramSocket.send` không đồng nghĩa gói UDP đã tới client, và gửi TCP thành công không chứng minh VLC đã giải mã khung hình.
- Khi MediaProjection bị hủy, ứng dụng cần người dùng cho phép chia sẻ màn hình lại. Dịch vụ và log cố gắng dọn tài nguyên an toàn.
- APK debug dùng để chẩn đoán trên LAN, không nên coi là bản sản phẩm đã kiểm định.

## Bố cục

```
.github/workflows/android-apk.yml      # Test/build/release APK
app/build.gradle                        # Min API 26; target API 35; version + file APK
app/src/main/AndroidManifest.xml
app/src/main/java/com/example/screenstreamdiag/
    MainActivity.java                    # One-view, các phần mở/đóng, xuất ZIP
    CaptureService.java                  # MediaProjection, H.264, foreground service
    AudioEngine.java                     # AudioRecord + AAC encoder
    RtspServer.java                      # RTSP, SDP, RTP video/audio, client logging
    AacRtp.java                          # AAC-hbr RTP payload
    H264.java
    DeviceInspector.java
    DiagnosticLog.java
app/src/main/res/...                    # Icon mới + notification icon
app/src/test/...                        # H.264 và AAC unit tests
```

### Yêu cầu đối với bản phát hành mới

Khi tăng version, cập nhật `versionCode` (tăng dần) và `versionName` trong `app/build.gradle`. Workflow tự đọc `versionName` để đặt tên artifact và kiểm tra Git tag trùng `v<versionName>`. Tên APK luôn có dạng **tên app + phiên bản + biến thể**.

MIT License. Dự án này không liên kết với đội ngũ phát triển ScreenStream gốc.
