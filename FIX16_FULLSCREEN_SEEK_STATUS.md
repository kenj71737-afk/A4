# FIX16 – Fullscreen thật sự lấp đầy cửa sổ ALAD + tua video (seek)

## User requirement (18/11/2026)
1. Xem video FULL MÀN HÌNH trong cửa sổ ALAD.
2. Đồng thời có thể TUA TỚI (seek) video.

## Files changed
- `app/src/main/kotlin/com/alad/app/BrowserActivity.kt` — fullscreen JS cải
  tiến, điều khiển tua (cả thanh controls thường và HUD fullscreen), re-sync
  sau khi tua.
- `app/src/main/kotlin/com/alad/app/core/service/AudioDubbingForegroundService.kt`
  — thêm action `ACTION_RESYNC` (reset sync + xóa hàng đợi giọng sau khi tua).
- `app/src/main/kotlin/com/alad/app/core/audio/AudioPlayerManager.kt` — thêm
  `clearPendingAudio()` (xóa các chunk giọng dịch đang chờ phát).

## 1. Fullscreen — video lấp đầy cửa sổ ALAD
Vẫn giữ cơ chế video-only của FIX14 (KHÔNG dùng fullscreen native của
website, vì fullscreen native tách video khỏi DOM/WebView làm mất đồng bộ độ
trễ lồng tiếng; `onShowCustomView` vẫn chặn và nhắc dùng nút của ALAD).
Cải tiến trong `__aladEnterVideoFullscreen()`:
- Ép các wrapper nằm giữa video và player container về `width/height: 100%`
  (lưu style cũ để khôi phục khi thoát): trước đây video có thể vẫn nhỏ
  trong một khung con có kích thước cố định của trang.
- Khóa cuộn trang khi fullscreen (`overflow: hidden` trên cả `html` và
  `body`, khôi phục khi thoát).
- Video giữ `object-fit: contain` + `object-position: center`: đúng tỉ lệ
  khung hình, không méo.
- Canvas hình trễ (thứ người xem thực sự nhìn thấy, vì video gốc bị ẩn
  `opacity: 0.01`) trước đây bị kéo giãn theo khung của video element, méo
  hình khi tỉ lệ màn hình khác tỉ lệ video. Nay canvas được đặt theo đúng
  tỉ lệ thật của video (`videoWidth/videoHeight`, letterbox căn giữa) cả
  trong và ngoài fullscreen.
- Nút `THOÁT` trong HUD fullscreen và phím Back vẫn thoát như cũ; mọi style
  đã sửa đều được khôi phục, trang không reload.
- Fullscreen nay cũng hoạt động khi video đang tạm dừng (trước đây bắt buộc
  video đang phát).

## 2. Tua video (seek)
Điều khiển tua có ở cả hai chỗ, dùng chung logic:
- Thanh controls thường (dưới thanh độ trễ): hàng `−10s | thanh trượt | +10s |
  thời gian (phút:giây / tổng)`.
- HUD fullscreen: hàng thứ hai dưới hàng độ trễ, cùng các nút đó.
- Thanh trượt hiển thị tiến trình thật của video, cập nhật mỗi 0,5 giây
  (`__aladVideoProgress()` trả về `currentTime|duration|paused`); kéo thả
  để nhảy tới vị trí. Khi đang kéo, việc cập nhật tự động tạm dừng để không
  giật thanh trượt.
- Nút `−10s` / `+10s` tua lùi/tới 10 giây. Tua thực hiện bằng cách đặt
  `video.currentTime` trên đúng video element đang phát (dùng chung hàm tìm
  video `__aladFindVideo()` với fullscreen: ưu tiên video đang phát, nếu
  không có thì lấy video đã tải lớn nhất).
- Nếu không tìm thấy video nào: các nút tua bị mờ/disable, thanh trượt hiện
  `— / —`, bấm tua hiện thông báo ngắn.
- Video livestream (duration vô cực) không tua được: nút tua tự disable.

## 3. Re-sync lồng tiếng sau khi tua
Tua làm mọi mốc thời gian của pipeline lồng tiếng (thuộc cảnh cũ) trở nên
sai. Sau mỗi lần tua thành công, app re-sync 2 tầng:
- Tầng JS (trong trang): `__aladSeekTo/__aladSeekBy` ghi dấu
  `__aladSeekAt = performance.now()`. Vòng `tick()` của canvas: (a) xóa
  toàn bộ frame đệm của cảnh cũ, (b) độ trễ hiệu dụng (`effDelay`) xây lại
  từ 0 theo thời gian kể từ lúc tua, tới đủ độ trễ đã chọn — âm thanh
  (DelayNode) và hình ảnh cùng ramp nên vẫn đồng bộ với nhau, thay vì
  phát lại cảnh cũ trong suốt 5 giây độ trễ.
- Tầng native: `BrowserActivity` gửi `ACTION_RESYNC` tới
  `AudioDubbingForegroundService` (chỉ khi đang lồng tiếng), service sẽ:
  `DubSyncTracker.reset()` (xóa utterance + trung bình độ trễ dịch),
  `TvbVoiceDirector.stop()` (dừng câu đang đọc + xóa hàng đợi),
  `TvbVoiceDirector.resetTextStream()` (xóa đoạn dịch Gemini đang đọc dở),
  `AudioPlayerManager.clearPendingAudio()` (xóa chunk giọng dịch đang chờ),
  xóa phụ đề. Câu lồng tiếng của cảnh mới sẽ được căn giờ lại từ đầu.
- Hiện toast ngắn "Đã tua — đang đồng bộ lại lồng tiếng…".

## Không thay đổi
- Độ trễ lồng tiếng (3–10s, AUTO/THỦ CÔNG), giọng TVB, cơ chế FIX15 sync,
  chặn fullscreen native của website — giữ nguyên.

## Cách test (trên máy thật, sau khi build)
1. `./gradlew assembleDebug`, cài `app/build/outputs/apk/debug/app-debug.apk`.
2. Bắt đầu lồng tiếng tại màn hình ALAD, chia sẻ link video YouTube sang ALAD.
3. Bấm `⛶ XEM TOÀN MÀN HÌNH`: video phải lấp đầy màn hình (chỉ còn viền
   đen letterbox đúng tỉ lệ nếu tỉ lệ video khác tỉ lệ máy), không còn
   khung nhỏ hay nội dung trang xung quanh. Bấm `THOÁT` (hoặc Back): trang
   trở lại bình thường, video vẫn phát, độ trễ vẫn giữ.
4. Trong fullscreen, bấm `+10s` / `−10s`: video nhảy đúng 10 giây, hiện
   toast đồng bộ lại, giọng lồng tiếng sau đó khớp cảnh mới (không đọc câu
   của cảnh cũ). Kéo thanh trượt: video nhảy tới vị trí kéo, thời gian
   hiển thị đúng.
5. Tua khi chưa phát video: nút tua mờ, bấm hiện thông báo (không crash).
6. Thử trên 2 website khác nhau. Video DRM / iframe nhúng / player lạ có
   thể không tua/fullscreen được — ghi nhận riêng, không phải lỗi chung.

## Limits / verification
- Chưa build APK và chưa test trên máy thật (Gradle không chạy được trên
  VM này — xem HUONG-DAN-BUILD.txt). Code đã rà soát bằng đọc lại: cân
  bằng ngoặc, tên hàm JS/Kotlin khớp nhau, không dùng API không tồn tại.
- Video trong iframe (player nhúng cross-origin) không tìm thấy được bằng
  `document.querySelectorAll('video')` — seek/fullscreen không áp dụng.
- Sau khi tua, độ trễ hình/tiếng xây lại từ 0 trong vài giây đầu (đây là
  hành vi đúng: chưa có đủ nội dung mới để trễ đủ 5 giây).
