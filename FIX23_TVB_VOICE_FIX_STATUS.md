# FIX23 — Sửa giọng đọc TVB đọc sai tiếng (v1.3.1)

Ngày: 2026-11-22. Lỗi user báo: giọng đọc TVB đọc "tiếng gì đâu không"
thay vì tiếng Việt. Lỗi này có từ lâu (từ khi có đường giọng TVB), không
phải do FIX22 — FIX22 chỉ làm nó lộ ra, vì chế độ Groq chỉ có đường giọng
này (chế độ Gemini còn có giọng audio của Gemini).

## Nguyên nhân thật

`TvbVoiceDirector` chỉ gọi `setLanguage(vi-VN)` rồi tin là đã có giọng
Việt. Nhiều engine TTS của hãng máy (Samsung, Xiaomi, Oppo...) báo
tiếng Việt là "có" trong khi thực ra không cài giọng Việt nào — rồi đọc
chữ Việt bằng giọng nước ngoài. App không hề kiểm tra xem có giọng Việt
thật không, và không ép chọn giọng nào.

## Cách sửa

1. **Chọn giọng Việt thật** (`TvbVoiceDirector.selectVietnameseVoice`):
   sau `setLanguage`, liệt kê `tts.voices`, chọn giọng có locale tiếng
   Việt — ưu tiên giọng đã tải về máy (không cần mạng), rồi vi-VN, rồi
   chất lượng cao nhất — và ép bằng `setVoice()`. Chỉ khi chọn được giọng
   thật mới tính là sẵn sàng.
2. **Thử engine Google TTS** (`com.google.android.tts`): engine mặc định
   không có giọng Việt thì tắt nó đi, tạo lại engine Google (nếu máy có
   cài) rồi chọn giọng lại. Chỉ thử một lần.
3. **Không có giọng Việt ở đâu cả**: giọng đọc tắt hẳn, câu dịch chỉ hiện
   phụ đề — không đọc bậy bằng giọng nước ngoài nữa. Đồng thời báo rõ
   trên màn hình trình duyệt ALAD (dòng đồng bộ): máy chưa có giọng tiếng
   Việt, hướng dẫn cài trong Cài đặt → Chuyển văn bản thành giọng nói,
   rồi tắt/bật lại lồng tiếng.
4. `setVoice` bị từ chối (hiếm): vẫn đọc bằng chế độ chỉ đặt ngôn ngữ như
   cũ, không tắt giọng.

## File thay đổi

- `core/audio/TvbVoiceDirector.kt` — chọn giọng Việt thật, fallback
  engine Google, callback `onVoiceMissing`, chẩn đoán `selectedVoiceName`
  / `voiceMissing`.
- `data/repository/DubbingSessionRepository.kt` — flow `ttsVoiceWarning`
  (+ xóa khi dừng/reset phiên).
- `core/service/AudioDubbingForegroundService.kt` — nối `onVoiceMissing`
  vào cảnh báo + trạng thái kết nối; xóa cảnh báo cũ khi bắt đầu phiên mới.
- `BrowserActivity.kt` — dòng đồng bộ hiện cảnh báo thiếu giọng (ưu tiên
  cao nhất), làm mới ngay khi cảnh báo xuất hiện/biến mất.
- `app/build.gradle.kts` — versionCode 6, versionName 1.3.1.

## Kiểm chứng (22/11, trên VM — không build được APK ở đây)

- `TvbVoiceDirector.kt` thật biên dịch sạch bằng kotlinc 1.9.24 với stub
  TextToSpeech/Voice/Handler/Context; **33/33 kiểm tra đúng**: chọn giọng
  (ưu tiên giọng local > mạng, vi-VN > nước khác, chất lượng cao),
  chuyển engine Google khi engine mặc định thiếu giọng, báo thiếu giọng
  và không đọc bậy khi không có giọng ở đâu, xử lý LANG_MISSING_DATA,
  chỉ thử Google đúng 1 lần, vẫn đọc được khi setVoice bị từ chối,
  thứ tự hàng đợi giữ nguyên.
- CHƯA kiểm chứng: build APK (Gradle bị chặn trên VM — build qua GitHub
  Actions như mọi FIX trước), chạy thật trên máy user (cần xem máy user
  có giọng Việt trong engine nào).
