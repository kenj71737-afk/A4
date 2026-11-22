# FIX22 — Groq dubbing provider in ALAD (v1.3.0)

Ngày: 2026-11-22. Đưa pipeline Groq của script Tampermonkey (bản Groq v1.3.0)
vào app ALAD, làm nguồn lồng tiếng thứ hai bên cạnh Gemini Live.

## Vì sao thêm Groq

- Gemini Live nghe + dịch + nói trong một lần gọi, nhưng hạn mức miễn phí
  nhỏ và bản dịch không nhớ ngữ cảnh câu trước.
- Groq tách thành 3 bước rẻ: Whisper nghe câu → model chat dịch (nhớ 6 câu
  gần nhất nên tên nhân vật và xưng hô nhất quán) → giọng TTS của máy đọc
  (đúng giọng TTS mà đường Gemini đã dùng, nên giọng nghe giống hệt).
- DeepSeek (tùy chọn) dịch hay nhất riêng tiếng Trung giản thể.

## Cách dùng

1. Cài đặt → **Nguồn lồng tiếng** → chọn **Groq**.
2. Nhập **Key Groq** (lấy miễn phí tại console.groq.com, không cần thẻ).
   Key này bắt buộc: Whisper vẫn dùng key Groq.
3. Chọn **Dịch bằng**: GPT-OSS 20B (nhanh, mặc định), GPT-OSS 120B (dịch
   hay hơn), Qwen 3.8 27B (chuyên tiếng Trung/Nhật/Hàn), hoặc DeepSeek
   (cần nhập thêm key DeepSeek ở dưới; DeepSeek lỗi thì tự dịch tạm bằng
   Groq, lựa chọn DeepSeek giữ nguyên để câu sau thử lại).
4. Chọn **Giọng đọc** nam/nữ. Groq không nhận biết nhân vật nên dùng một
   giọng cho mọi câu (Gemini thì nhận biết qua marker [[TVB:...]]).
5. Bấm Lưu, dừng phiên đang chạy rồi bắt đầu lại để áp dụng. Đổi nguồn
   lồng tiếng giữa phiên không có tác dụng cho tới khi bắt đầu lại.

Trong trình duyệt ALAD mọi thứ khác giữ nguyên: trễ hình, tự động chỉnh
trễ, tua ±10s, toàn màn hình của ALAD, phụ đề song ngữ (dòng tiếng Trung
gốc hiện từ bản nghe của Whisper).

## Pipeline

```
PCM 16 kHz (WebAudio tap / system capture)
  -> DubSyncTracker (VAD: 1 nguồn duy nhất cho cả 2 provider)
  -> utterance hoàn thành (450 ms im lặng)
  -> GroqDubbingManager cắt WAV từ bộ đệm PCM (lấp chỗ im lặng bằng 0)
  -> Groq Whisper (whisper-large-v3-turbo, tự nhận diện ngôn ngữ)
  -> lọc tiếng kêu/rên/cười không có nghĩa (port từ FIX21)
  -> dịch (Groq chat / DeepSeek, nhớ 6 câu gần nhất)
  -> TvbVoiceDirector (Android TTS) theo đúng đường hẹn giờ cũ
     (speakAt = lúc câu gốc bắt đầu + trễ hình, deadline bỏ câu cũ)
```

- Độ trễ đo từ lúc câu gốc BẮT ĐẦU (không phải lúc kết thúc) — cùng bản
  sửa v1.0.2 của script Groq: trễ hình phải bao cả thời gian nói câu đó
  cộng thời gian xử lý, nếu không giọng bắt đầu trước hình.
- Model dịch 404/429 thì tự thử model Groq khác; hết lượt (429) ở mọi
  model thì nghỉ 60 giây rồi thử lại.
- Câu tới nhanh hơn tốc độ dịch thì bỏ câu cũ nhất đang chờ (tối đa 2 câu
  chờ) thay vì dịch câu đã qua hình.
- Tua video (ACTION_RESYNC) xóa bộ đệm PCM, câu đang chờ và lịch sử ngữ
  cảnh của Groq.

## File thay đổi

- MỚI `core/network/GroqDubbingManager.kt` — toàn bộ pipeline Groq
  (Whisper, dịch + fallback, lọc tiếng vô nghĩa, cắt WAV, bộ đệm PCM).
- `core/audio/DubSyncTracker.kt` — thêm `onUtteranceComplete` (báo khi một
  câu hoàn thành) và `closeIfSilent()` (đóng câu khi đã im lặng đủ 450 ms;
  tap WebAudio chỉ gửi khối không im lặng nên phải thăm dò định kỳ).
- `data/repository/UserPreferencesRepository.kt` — key mới: dubbing_provider
  (mặc định gemini), groq_api_key, deepseek_api_key, groq_translate_model
  (mặc định fast), groq_voice_gender (mặc định F).
- `presentation/settings/SettingsViewModel.kt` + `SettingsScreen.kt` —
  thẻ chọn Nguồn lồng tiếng + thẻ Groq (2 key, 4 model, giọng nam/nữ).
- `core/service/AudioDubbingForegroundService.kt` — chế độ Groq: tạo
  GroqDubbingManager thay vì kết nối WebSocket, PCM đi vào Groq thay vì
  Gemini, `speakGroqLine()` hẹn giờ theo câu gốc của chính nó, resync/
  dừng dọn cả Groq. Chế độ Gemini không đổi hành vi.
- `BrowserActivity.kt` — JS trễ hình: chưa có khung hình trễ thì hiện
  video gốc thay vì khung đen (cùng bản sửa v1.4/v1.3.0 của script).
- `app/build.gradle.kts` — versionCode 5, versionName 1.3.0.

## Giới hạn thật

- Groq không có nhận diện người nói: một giọng đọc cho mọi nhân vật.
- Groq chậm hơn Gemini khoảng 2–4 giây/câu (3 lần gọi mạng nối tiếp),
  nên trễ hình tự động thường ở mức cao hơn (6–9 giây).
- Chế độ dịch theo phụ đề kiểu EarDub của script Groq (YouTube có phụ đề)
  CHƯA có trong app — app chỉ có chế độ nghe tiếng.
- Giọng đọc dùng Android TTS của máy: máy chưa cài giọng tiếng Việt thì
  giọng sẽ im (phụ đề vẫn hiện). Cài giọng Việt trong Cài đặt Android →
  Ngôn ngữ & nhập liệu → Chuyển văn bản thành giọng nói.

## Kiểm chứng (22/11, trên VM — không build được APK ở đây)

- `GroqDubbingManager.kt` + `DubSyncTracker.kt` + `AudioUtils.kt` biên
  dịch sạch bằng kotlinc 1.9.24 (JDK 17) với stub android.util.Log /
  android.os.SystemClock; 54 kiểm tra chạy đúng 54/54: bộ lọc tiếng vô
  nghĩa (24 trường hợp, khớp script), header WAV (13), tra model (4),
  callback utterance + closeIfSilent + EMA trễ (13).
- JS trễ hình trong BrowserActivity qua `node --check`.
- 3 endpoint Groq Whisper / Groq chat / DeepSeek chat trả 401 với key giả
  (endpoint đúng, request tới được server).
- CHƯA kiểm chứng: build APK (Gradle bị chặn trên VM — build qua GitHub
  Actions như mọi FIX trước), chạy thật trên máy (cần key Groq thật +
  giọng TTS tiếng Việt trên máy).
