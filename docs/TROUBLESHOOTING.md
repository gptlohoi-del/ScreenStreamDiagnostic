# Đọc log v0.2.1 khi client VLC out

## Quy trình thu log

1. Khi bắt đầu chạy, ghi giờ, IP máy phát và cấu hình hình/âm thanh. Ví dụ ZTE Android 9, 15 FPS, 1 Mbps, audio off.
2. Khi mất hình/thoát: nhấn **Đánh dấu sự cố → chọn IP vừa bị lỗi** càng sớm càng tốt. Nếu không rõ, chọn *Tất cả / không rõ*.
3. Xuất ZIP sau sự cố, càng sớm càng tốt. Mỗi file log tối đa ~2 MiB và xoay vòng hai file nên log cũ có thể bị ghi đè.
4. Cung cấp thêm *thời gian trên VLC*, giao thức TCP/UDP, số client vẫn xem được, trạng thái đứng hình hay VLC tự đóng.

## Cách đọc nhanh

| Dấu hiệu | Có thể suy ra | Không thể suy ra |
|---|---|---|
| `PLAY` | RTSP server đã chấp nhận yêu cầu PLAY | VLC đang thực sự giải mã / còn trên màn hình |
| `RTCP_RR` + `lossFraction` | Client đã gửi thống kê mất gói của khoảng báo cáo cho track cụ thể | Tất cả gói trước/sau mốc đó hoặc hình vẫn hiển thị đúng |
| Không thấy `RTCP_RR` | Chưa có báo cáo đo được | Wi-Fi ổn định / VLC đã thoát |
| `lastVideoSendAgoMs` thấp | Server vẫn gửi dữ liệu RTP | UDP packet đã tới client hoặc decoder hoạt động |
| `TCP_WRITE_STALL` | Một lần ghi socket TCP bị kẹt trên 12 giây | Chính xác do router, sóng hay VLC |
| `SLOW_CLIENT` | Queue đầy, server phải bỏ frame để tránh backlog | Client có đủ tốc độ mạng hay không |
| `VIDEO_KEYFRAME` | Encoder xuất khung hình I được ghi nhận | Client đã nhận được keyframe |
| `maxPtsGapMs` lớn | Có khoảng hở lớn giữa các PTS encoder trong kỳ đo | Encoder phần cứng chắc chắn treo (có thể màn hình tĩnh) |
| `INCIDENT USER_MARKED` | Người dùng xác nhận có vấn đề tại mốc đó | RTSP server biết chính xác giờ VLC thoát |
| `PORT_IN_USE` | Cổng RTSP 8554 không thể bind | Có sự cố RTP ở phiên phát thành công khác |

## Những thử nghiệm đối chứng đề xuất

A. 15 FPS / 1 Mbps / tắt âm thanh / **UDP** / một client, 10 phút.

B. Giữ nguyên các thông số; dùng **RTSP TCP** nếu client hỗ trợ, 10 phút.

C. Khi một client ổn định, tăng số client 2, 3, rồi tối đa 5.

D. Để màn hình phát **đứng yên** rồi làm lại khi nội dung **chuyển động liên tục**; so sánh `VIDEO_KEYFRAME`, `maxPtsGapMs`, và mốc VLC thực sự out.

E. Lặp lại trên ZTE 901ZT Android 9 và RC069L Android 12 (hai thiết bị có codec/ROM khác nhau). Chỉ khi ổn định hãy thử micro hoặc playback audio.

**Không thay nhiều thông số cùng lúc.** Vắng RTCP RR trên VLC là hiện tượng có thể xảy ra; RR không phải cơ chế bắt buộc mọi client gửi. Đọc được RTCP không có nghĩa kiểm soát được nguồn phát trên VLC.

## Riêng Android 9 / ZTE 901ZT

`MediaFormat.KEY_MAX_FPS_TO_ENCODER` chỉ có từ Android 10 (API29); trên Android 9, `KEY_FRAME_RATE` là cấu hình encoder chứ không bảo đảm hard cap đầu ra. Vòng gửi RTP **không loại bỏ P-frame một cách tùy tiện**, vì việc đó có thể làm client mất khả năng giải mã cho đến IDR kế tiếp.

Hãy kiểm tra thủ công chính sách tiết kiệm pin/tự quản lý ứng dụng riêng của ZTE, cài đặt chặn thiết bị ngang hàng (AP/client isolation) trên Wi-Fi, tín hiệu **tại từng điện thoại client**, và xem VLC có chế độ RTSP over TCP hay không.
