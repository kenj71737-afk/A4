# FIX24 — Giọng Google TTS cho chế độ Groq (v1.3.2)

Ngày: 2026-11-22. Hoàn thiện lỗi giọng đọc mà FIX23 chưa sửa dứt điểm.

## Bối cảnh

- Bản FIX21 (chỉ Gemini) không lỗi giọng vì giọng đọc là audio của
  Gemini, không qua TTS của máy.
- FIX22 thêm chế độ Groq: giọng đọc duy nhất là Android TTS
  (TvbVoiceDirector) — đường giọng này trên máy user vốn đã lỗi từ lâu
  (không có giọng Việt thật), nên chế độ Groq đọc sai tiếng.
- FIX23 sửa cách chọn giọng + báo thiếu giọng, nhưng trên máy hoàn toàn
  không có giọng Việt thì giọng vẫn im — chưa phải là "sửa xong".

## Cách sửa (FIX24)

Chế độ Groq giờ có giọng thứ hai không phụ thuộc máy: **giọng Google TTS
qua mạng** — đúng giọng bản script Groq dùng (translate.google.com,
miễn phí, không cần key).

- MỚI `core/audio/GoogleTtsSpeaker.kt`: mỗi câu dịch được tải thành file
  MP3 (câu dài cắt <=180 ký tự như script, nối MP3 lại), phát bằng một
  MediaPlayer dùng chung, hẹn giờ đúng như director: đợi tới lúc hình
  trễ tới câu đó mới phát, bỏ câu đã quá hạn, tăng tốc tối đa 1.30x để
  câu dài vừa miệng nhân vật (tính theo độ dài audio thật, không ước
  lượng theo số chữ). Âm lượng + tốc độ theo đúng thanh trượt trong app.
  AudioAttributes USAGE_ASSISTANT như đường audio Gemini nên không bị
  hạ âm lượng chung với tiếng gốc. Tua video thì bỏ hàng đợi + dừng câu
  đang đọc; tắt phiên thì dọn file tạm.
- Service: câu Groq đi vào Google TTS khi `TvbVoiceDirector.voiceMissing`
  (máy thiếu giọng Việt); máy có giọng Việt thật thì vẫn dùng giọng máy
  như cũ. Chế độ Groq không còn hiện cảnh báo "thiếu giọng" của FIX23
  (giọng Google đã lo). Google TTS lỗi mạng 3 lần liên tiếp thì báo trên
  dòng đồng bộ: kiểm tra mạng rồi tắt/bật lại lồng tiếng.
- Chế độ Gemini không đổi (audio Gemini + director cho câu chữ).

## File thay đổi

- MỚI `core/audio/GoogleTtsSpeaker.kt`.
- `core/service/AudioDubbingForegroundService.kt` — tạo speaker ở chế độ
  Groq, nối callback phụ đề/thống kê/cảnh báo, chuyển giọng theo
  voiceMissing, cập nhật âm lượng + tốc độ theo cài đặt, reset khi tua,
  stop khi tắt phiên.
- `app/build.gradle.kts` — versionCode 7, versionName 1.3.2.

## Kiểm chứng (22/11, trên VM — không build được APK ở đây)

- `GoogleTtsSpeaker.kt` thật biên dịch sạch bằng kotlinc 1.9.24 với stub
  MediaPlayer/Context/Build; **35/35 kiểm tra đúng**: cắt câu (<=180 ký
  tự, nối lại đúng), fit rate (kẹp 0.85–1.30, nhân tốc độ user), phát
  đúng thứ tự, đợi tới speakAt, bỏ câu cũ + đếm, lỗi tải (3 lần thì báo
  không tải được), lố giờ tính từ độ dài audio thật (~746ms), âm lượng +
  tốc độ áp dụng, reset giữa chừng dừng câu đang đọc và khởi động lại
  worker.
- CHƯA kiểm chứng: build APK (Gradle bị chặn trên VM — build qua GitHub
  Actions như mọi FIX trước), chạy thật trên máy user (cần mạng tới
  translate.google.com; endpoint này không cần key nhưng Google có thể
  giới hạn khi gọi dồn dập).
