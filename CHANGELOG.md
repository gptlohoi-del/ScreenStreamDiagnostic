# Changelog

## 0.2.2 (2026-11-13)

- Tự động tạo TXT riêng trong Downloads khi bắt đầu mỗi phiên phát, tự flush mỗi dòng.
- Bật/tắt ghi TXT trong nhóm Nhật ký; Android 9 xin WRITE_EXTERNAL_STORAGE, Android 10+ dùng MediaStore.
- Host/network/router snapshot: Android/build, mạng, IP, gateway, DHCP, DNS, RSSI, frequency, link speed; lặp lại trong phiên.
- Log nhận dạng User-Agent (nếu có) từ RTSP client, kèm session/IP/transport/RTP/RTCP.
- Nút copy địa chỉ RTSP, nút phát xanh / dừng đỏ, ẩn/mờ nút không khả dụng.
- Tùy chọn cấp quyền vị trí để đọc SSID/BSSID; không đọc tọa độ GPS.


## 0.2.1 (2026-11-13)

- RTCP Receiver Report parser (RFC 3550), hỗ trợ RTP/RTSP interleaved TCP và UDP RTCP.
- Theo dõi báo cáo mất gói, jitter, số báo cáo và timestamp gần nhất riêng từng track và client.
- Ghi cảnh báo `PLAY_UNCONFIRMED` thay vì khẳng định VLC vẫn đang xem khi chỉ thấy RTP send thành công.
- TCP writer watchdog, thông báo cổng 8554 bị chiếm, dọn phiên RTSP không hoạt động.
- Encoder bổ sung keyframe/PTS gap logging; giới hạn FPS đầu vào khi Android 10+ hỗ trợ.
- Cho phép đánh dấu lỗi có IP client, UI một màn hình với các nhóm thu gọn.
- Tích hợp JVM RTSP smoke test vào GitHub Actions.

## 0.2.0

- Tùy chọn AAC (micro / playback capture nếu Android 10+), hai RTSP tracks.
- Giao diện rút gọn, icon riêng, cảnh báo Android, log ZIP và tên APK theo version.
