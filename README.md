# RTSP Diagnostic v0.2.2 — Android 9+ / ZTE 901ZT / RC069L

**Mã nguồn đầy đủ** của một ứng dụng Android Java phát màn hình H.264 qua RTSP cho 1–5 VLC client trong LAN, tùy chọn âm thanh AAC, tự kiểm tra các cài đặt Android ảnh hưởng tới phát, ghi log chẩn đoán và xuất ZIP.

- **Thiết bị ưu tiên:** ZTE 901ZT, Android 9 (API 28).
- **Thiết bị phụ:** RC069L, Android 12 (API 31).
- **Khả năng mở rộng:** minSdk 26, compile/target SDK 35; **không cam kết** chạy đúng trên mọi điện thoại/ROM Android chưa kiểm thử.
- **Độc lập với ScreenStream chính thức:** đây là dự án thử nghiệm được xây dựng theo yêu cầu, không phải fork của ScreenStream.
- **Trạng thái kiểm thử:** đã biên dịch và chạy **JVM RTSP smoke test** qua TCP/UDP với RTCP Receiver Reports. **Chưa xác nhận** build Android APK thực tế hoặc thử trên thiết bị ZTE/RC thật. GitHub Actions sẽ xác minh build Android.

## Điểm mới v0.2.2 — Tự động lưu TXT cho mỗi phiên stream

- **Tự ghi TXT mặc định bật**: mỗi khi được cấp quyền chia sẻ màn hình và service bắt đầu, ứng dụng tạo **một file `.txt` riêng** ngay tại thư mục **Downloads/**, ví dụ `RTSPDiagnostic_v0.2.2_20261113_204512_123_ZTE901ZT_a1b2c3d4.txt`. Tên gồm phiên bản, giờ địa phương đến mili-giây, model đã làm sạch và chuỗi ngẫu nhiên để tránh trùng.
- Ghi **liên tục và flush sau mỗi sự kiện**, nên những gì đã ghi vẫn có thể đọc được nếu Android đột ngột kết thúc ứng dụng. Khi dừng phát bình thường sẽ có dòng `SESSION_ENDED`. Nếu file không có dòng này, cần nghi ngờ tiến trình bị dừng hoặc thiết bị mất nguồn (không phải kết luận chắc chắn).
- **Bật/tắt:** mở phần **Ghi TXT tự động / xuất nhật ký** trên màn hình chính, dùng công tắc `Tự ghi TXT trong Downloads mỗi lần phát`. Mặc định **bật**. Tắt khi đang phát sẽ đóng file đang ghi; bật lại sẽ áp dụng từ phiên mới.
- **Android 9:** lần đầu ứng dụng xin `WRITE_EXTERNAL_STORAGE` để ghi trực tiếp vào Downloads công khai. Nếu từ chối, có thể chọn không ghi TXT và vẫn phát. **Android 10+:** lưu qua `MediaStore.Downloads`, không cần quyền truy cập toàn bộ bộ nhớ. Trạng thái/tên file hoặc lỗi ghi sẽ hiện ngay dưới link RTSP.
- **Thông tin tự ghi:** cấu hình phát, Android/build/CPU, encoder/MediaProjection, IP/giao diện mạng, default network, gateway/router DHCP, DNS, Wi-Fi RSSI/frequency/link rate, tiết kiệm pin, RAM/nhiệt, client IP + cổng + `User-Agent` nếu RTSP client gửi, RTSP methods/transport, RTP counters, RTCP receiver report nếu được gửi, network changes và health snapshot mỗi ~60 giây.
- **SSID/BSSID:** dùng nút *Cấp quyền đọc tên Wi-Fi / BSSID* ở phần Nhật ký. Android yêu cầu quyền vị trí và thường phải bật dịch vụ Vị trí; ứng dụng **không đọc tọa độ GPS**. Nếu không cấp, các thông tin mạng ít nhạy cảm hơn vẫn được ghi.
- **Không thể đọc:** cài đặt nội bộ router (QoS, Smart Connect, DFS, band steering, client isolation), tên/model/phiên bản Android của client (trừ khi client tự khai qua `User-Agent`), chất lượng giải mã VLC. Những mục này cần quyền quản trị router hoặc chẩn đoán phía client.
- **Giao diện:** link RTSP có nút **Copy link**; hai nút **Phát màu xanh** / **Dừng màu đỏ** luân phiên sáng-mờ theo trạng thái. Các nhóm chi tiết vẫn thu gọn.
- **Riêng tư:** TXT chứa IP và có thể chứa SSID/BSSID; hãy xem lại trước khi chia sẻ. Không lưu nội dung ảnh màn hình hoặc âm thanh.

**Cách tìm file:** Mở **Files / Quản lý tệp → Downloads** trên điện thoại, tìm `RTSPDiagnostic_v0.2.2_*.txt`. Đọc trực tiếp bằng trình xem văn bản hoặc gửi file cho người phân tích. Chức năng xuất ZIP nhật ký cũ vẫn giữ nguyên và độc lập với TXT theo phiên.

## Điểm mới v0.2.2

1. **RTCP Receiver Reports (RR)**: nhận và giải mã RR qua **UDP RTCP** hoặc **TCP interleaved**, phân tích tỷ lệ mất gói (fraction lost), tổng số gói mất và jitter. Có log `[RTCP_RR]` theo client, track âm thanh/hình. **Không có RR nghĩa là chưa đủ bằng chứng, không đồng nghĩa kết nối tốt hoặc xấu.** Một số VLC client không gửi RR.
2. **Cảnh báo `PLAY_UNCONFIRMED`**: khi RTSP vẫn ở `PLAY` nhưng không có dữ liệu phản hồi xác nhận, ứng dụng ghi rõ không thể xác nhận video thực sự được VLC giải mã. Không tự đóng UDP chỉ vì thiếu RR (tránh làm gián đoạn VLC đang xem bình thường).
3. **Giám sát socket TCP**: watchdog ghi `TCP_WRITE_STALL` và đóng socket nếu một lần ghi TCP kẹt quá 12 giây; dọn phiên RTSP chưa PLAY và không có request sau 90 giây. Phân biệt sự kiện `RTSP_KEEPALIVE`.
4. **Encoder log chi tiết**: `[ENCODER] VIDEO_KEYFRAME` (PTS, kích thước, khoảng cách giữa keyframe), `maxPtsGapMs`, `keyframes` trong bản tin sức khỏe `[HEALTH]`.
5. **Kiểm soát FPS trên Android 10+**: thử cấu hình `MediaFormat.KEY_MAX_FPS_TO_ENCODER`. Android 9 không có khóa này, nên chỉ là hint theo `KEY_FRAME_RATE` và có thể vượt FPS tùy codec. Nếu codec từ chối khóa tùy chọn, ứng dụng thử lại cấu hình cơ bản. Không tùy tiện loại bỏ P-frame H.264 (dễ gây hình hỏng).
6. **Thông báo chiếm cổng 8554**: bắt `BindException`, báo `PORT_IN_USE` và gợi ý đóng ứng dụng khác đang chiếm cổng.
7. **Mốc sự cố có lựa chọn client**: nhấn *Đánh dấu client bị mất hình / out*, chọn IP hoặc *Tất cả / không rõ*; app ghi snapshot cho đúng thời điểm.
8. **Kiểm thử giao thức trong GitHub Actions**: `tools/rtsp-smoke.sh` chạy bằng Java 17 trước unit test và APK build.
9. **Giữ các chức năng v0.2.0**: giao diện một màn hình (bốn khối thu gọn), icon riêng, lựa chọn âm thanh, kiểm tra Android, xuất ZIP, tên APK có phiên bản.

> **Không tuyên bố đã sửa hoàn toàn lỗi VLC tự out.** v0.2.2 giúp xác định rõ hơn lỗi do mạng/RTP, phiên RTSP hay H.264. Các thông số RR cũng chỉ tồn tại nếu client gửi báo cáo. Đặc biệt, UDP send thành công vẫn không chứng minh gói đã đến hoặc VLC đang giải mã.

## Tương thích và âm thanh

| Thiết bị / Android | Video H.264 | Không âm thanh | Micro AAC | Ghi âm phát lại ứng dụng AAC |
|---|---|---|---|---|
| ZTE 901ZT, Android 9 | Có | Có (mặc định) | Có (cần RECORD_AUDIO) | **Không**, Android 9 chưa hỗ trợ |
| RC069L, Android 12 | Có | Có | Có | Có **nếu ứng dụng nguồn cho phép** |
| Android 10+ khác | Dự kiến, cần thử máy | Có | Có | Phụ thuộc ROM, quyền và nội dung nguồn |

- Chỉ chọn **một** nguồn âm thanh: Tắt / Micro / Âm thanh ứng dụng. Chỉ cấp quyền micro khi cần thu âm.
- Âm thanh: AAC-LC mono 44.1 kHz, 64 kbps. Server đưa track âm thanh vào SDP khi khởi tạo audio thành công.
- Luồng video: H.264; video track `trackID=0`, audio track `trackID=1`; RTP TCP interleaved hoặc UDP unicast theo yêu cầu SETUP của client.
- **Không ghi lại nội dung hình ảnh hoặc âm thanh** trong log. Log chứa IP nội bộ và thông tin thiết bị, nên xem trước khi chia sẻ.

## Giao diện một màn hình

Trên đầu là trạng thái, URL `rtsp://<IPv4-WiFi>:8554/live` và nút **Copy link**, nút **Phát màu xanh** / **Dừng màu đỏ** (nút vừa nhấn sẽ mờ, nút còn lại nổi bật), **Đánh dấu sự cố**, và dòng trạng thái file TXT. Các nhóm chỉ hiển thị nội dung khi bấm mở:

- **Cấu hình hình ảnh & âm thanh:** 15/20/25 FPS; 1/2/3 Mbps; scale 100/75/50%; nguồn âm thanh (mặc định Tắt).
- **Client / RTSP:** IP và session ID, track TCP/UDP, trạng thái PLAY (không phải xác nhận VLC đang xem), byte RTP, gói RTP, RTCP RR, jitter và loss nếu có.
- **Android / cảnh báo:** Wi-Fi/IP/RSSI, H.264 encoder, tiết kiệm pin/Doze, pin/nhiệt, RAM, khóa màn hình, quyền micro, một số cài đặt khác. Các mục tùy biến ZTE hoặc router không thể tự đọc phải xem thủ công.
- **Nhật ký:** công tắc tự ghi TXT vào Downloads từng phiên, cấp quyền đọc SSID/BSSID (tùy chọn), hiển thị log gần nhất, xuất ZIP để phân tích.

## Tạo APK tự động bằng GitHub Actions

1. Tạo repository trên GitHub, ví dụ `ScreenStreamDiagnostic`.
2. Giải nén ZIP **rồi upload toàn bộ file/thư mục bên trong**, kể cả `.github/workflows/android-apk.yml` và `tools/` (không chỉ upload file ZIP). Dùng Git trên máy tính để không mất file ẩn:

   ```bash
   git init
   git add .
   git commit -m "RTSP Diagnostic v0.2.2"
   git branch -M main
   git remote add origin https://github.com/<TEN-TAI-KHOAN>/<TEN-REPO>.git
   git push -u origin main
   ```

3. Trong GitHub, mở **Actions → Build RTSP Diagnostic APK → Run workflow** hoặc đẩy commit lên `main`.
4. Workflow dùng **`android-actions/setup-android@v4`**, JDK 17, Gradle 8.9, Android SDK API 35. Chạy JVM smoke test, `:app:testDebugUnitTest`, `:app:assembleDebug`.
5. Tải artifact **`RTSPDiagnostic-v0.2.2-debug`**, giải nén để nhận **`RTSPDiagnostic-v0.2.2-debug.apk`**.
6. Tạo tag `v0.2.2` để đưa APK lên **GitHub Releases**. Tag phải khớp `versionName`.

### Ký APK release ổn định để cập nhật không mất log

APK debug được ký bằng khóa debug do runner tạo, có thể khác giữa các lần build. Nếu cần cài đè lâu dài mà không xóa dữ liệu, tạo **keystore release ổn định** trên máy bạn và thiết lập 4 repository secrets:

| GitHub Actions secret | Giá trị |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Keystore JKS mã hóa base64 một dòng |
| `ANDROID_KEYSTORE_PASSWORD` | Mật khẩu keystore |
| `ANDROID_KEY_ALIAS` | Alias trong JKS |
| `ANDROID_KEY_PASSWORD` | Mật khẩu alias |

Nếu đủ secrets, workflow build thêm **`RTSPDiagnostic-v0.2.2-release.apk`**. Giữ bí mật và sao lưu keystore; không commit file keystore vào GitHub. Để cài đè bản cũ cần **cùng applicationId và cùng khóa ký**, với versionCode mới tăng dần. Không thể cài đè APK debug bằng APK release có chữ ký khác — hãy xuất log trước khi gỡ app cũ.

## Kiểm tra khi VLC tự out

1. Trên ZTE 901ZT thử ban đầu: **Tắt âm thanh**, 15 FPS, **1 Mbps**, 1 client, tăng dần lên 5 client. Chỉ thay đổi **một thông số mỗi lần**.
2. Đối chiếu **TCP vs UDP** trên các client có hỗ trợ chọn transport. Ghi IP từng client.
3. Khi client vừa mất hình hay VLC thoát, bấm **Đánh dấu sự cố → chọn IP** (hoặc *Tất cả / không rõ*). Nếu server vẫn ghi `PLAY`, đó **không phải bằng chứng VLC đang chạy**.
4. Mở **Nhật ký → Xuất ZIP**, gửi file khi cần phân tích. Ghi thêm giờ client thực sự thoát, giao thức, cấu hình âm thanh, có client nào còn xem được không.
5. Xem [Hướng dẫn đọc log](docs/TROUBLESHOOTING.md).

**Diễn giải log:**

- `RTCP_RR`: nhận được báo cáo chất lượng từ phía client; loss, jitter chỉ đáng tin theo **khoảng báo cáo và track tương ứng**, không phải đo trực tiếp chất lượng hình hiển thị.
- `PLAY_UNCONFIRMED`: trạng thái RTSP còn PLAY nhưng **không có xác nhận nhận/giải mã**; không kết luận client bị out.
- `TCP_WRITE_STALL`: một lần ghi socket TCP bị kẹt trên 12 giây, server đóng socket để tránh chiếm tài nguyên.
- `ENCODER_STALL`, `VIDEO_KEYFRAME`, `maxPtsGapMs`: phân tích cung cấp khung hình từ encoder.
- `PORT_IN_USE`: cổng 8554 đang được tiến trình khác sử dụng, không phải lỗi mất kết nối khi đang phát.
- `CLIENT DISCONNECT`: xem `reason=RTSP socket EOF`, `client TEARDOWN` hay `RTP send failure`; đóng từ socket không tự xác định được nguyên nhân gốc ở VLC hay Wi-Fi.

### Giới hạn đã biết

- RTP/RTSP mặc định **không có TLS và không có đăng nhập**: chỉ dùng LAN tin cậy, **không chuyển tiếp cổng 8554 ra Internet**.
- RTCP RR không phải mọi client đều gửi; RTCP RR **không xác nhận VLC giải mã được video**, chỉ phản ánh bộ đếm và thống kê mà bên nhận báo cáo.
- Android 9 chưa có API `KEY_MAX_FPS_TO_ENCODER` nên FPS thực tế có thể lớn hơn cấu hình. `KEY_REPEAT_PREVIOUS_FRAME_AFTER` chỉ yêu cầu lặp **một lần** khi nguồn đứng yên và tùy vendor.
- Không có cách ép âm thanh nội bộ trên Android 9 hay ép ứng dụng nguồn chống ghi lại phải xuất âm thanh.
- Chưa hỗ trợ xác thực RTSP, RTSP over TLS, RTP retransmission, RTCP NACK/PLI, tự reconnect từ VLC, hard cap FPS Android 9 hoặc chẩn đoán lỗi decoder phía VLC.
- Trên Android hiện đại, MediaProjection thường cần người dùng chấp thuận lại khi khởi tạo phiên mới. Các ROM tương lai có thể thêm hạn chế nền/quyền mới.

## Cấu trúc repo

```text
.github/workflows/android-apk.yml  # github build/test/release - setup-android@v4
app/build.gradle                   # versionCode=4, versionName=0.2.2
app/src/main/AndroidManifest.xml
app/src/main/java/com/example/screenstreamdiag/
  MainActivity.java                # giao diện một màn hình, 4 mục thu gọn, đánh dấu IP
  CaptureService.java              # MediaProjection, MediaCodec H.264, foreground service
  AudioEngine.java                 # AAC micro / playback (Android 10+)
  RtspServer.java                  # RTSP, RTP TCP/UDP, RTCP SR/RR, watchdog
  Rtcp.java                        # parser báo cáo RTCP RR thuần Java
  AacRtp.java                      # AAC AU RTP
  H264.java                        # NAL parser
  DeviceInspector.java             # kiểm tra Android và cảnh báo
  DiagnosticLog.java               # log xoay vòng, xuất ZIP, chuyển sự kiện vào TXT
  SessionReport.java               # TXT mỗi phiên, Downloads Android 9 / MediaStore 10+
  NetworkSnapshot.java             # IP, Wi-Fi, gateway/DNS, network capability
app/src/test/...                   # unit tests H.264 / AAC / RTCP
 tools/rtsp-smoke.sh               # biên dịch và kiểm thử RTSP qua Java 17
 tools/RtspSmoke.java
 docs/TROUBLESHOOTING.md
```

MIT License. Không liên kết đội ngũ ScreenStream gốc.
