package com.alad.app.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.alad.app.core.audio.AudioCaptureManager
import com.alad.app.core.audio.AudioPlayerManager
import com.alad.app.core.audio.AudioUtils
import com.alad.app.core.audio.BrowserAudioBridge
import com.alad.app.core.audio.DubSyncTracker
import com.alad.app.core.audio.GoogleTtsSpeaker
import com.alad.app.core.audio.TvbVoiceDirector
import com.alad.app.core.network.ALADWebSocketManager
import com.alad.app.core.network.GroqDubbingManager
import com.alad.app.data.repository.DubbingSessionRepository
import com.alad.app.data.repository.UserPreferencesRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

class AudioDubbingForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "alad_dubbing_channel"
        private const val NOTIFICATION_ID = 101

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        // FIX16: the browser video was seeked to a new scene. The service
        // drops every timing/queue that belongs to the old position.
        const val ACTION_RESYNC = "ACTION_RESYNC"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val TAG = "DubbingService"

        // Delegated to DubbingSessionRepository for backward compatibility
        val isRunning: StateFlow<Boolean> get() = DubbingSessionRepository.isDubbingRunning
        val audioAmplitude: StateFlow<Float> get() = DubbingSessionRepository.audioAmplitude
    }

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in serviceScope", throwable)
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + coroutineExceptionHandler)

    private var mediaProjection: MediaProjection? = null
    private var audioCaptureManager: AudioCaptureManager? = null
    private var audioPlayerManager: AudioPlayerManager? = null
    private var webSocketManager: ALADWebSocketManager? = null
    private var groqDubbingManager: GroqDubbingManager? = null
    private var googleTtsSpeaker: GoogleTtsSpeaker? = null
    private var tvbVoiceDirector: TvbVoiceDirector? = null
    private var dubSyncTracker: DubSyncTracker? = null
    private val tvbTextModeActive = AtomicBoolean(false)
    private var settingsObservationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        when (action) {
            ACTION_START -> {
                val notification = createNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    }
                    startForeground(NOTIFICATION_ID, notification, serviceType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                if (resultCode != 0 && data != null) {
                    startDubbing(resultCode, data)
                }
            }
            ACTION_STOP -> {
                stopDubbing()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_RESYNC -> {
                // FIX16: the browser video jumped to a new scene (user seek).
                // Every timing the dub pipeline holds belongs to the old
                // position: reset the sync clock, drop the queued TVB lines
                // and the half-read Gemini text stream, discard translated
                // audio still waiting to play, and clear the subtitle, so
                // nothing from the old scene is spoken over the new picture.
                // The WebAudio/frame delay re-syncs itself in JS.
                dubSyncTracker?.reset()
                groqDubbingManager?.reset() // FIX22: drop Groq's buffered audio + pending lines
                googleTtsSpeaker?.reset() // FIX24: drop queued Google TTS lines + stop playback
                tvbVoiceDirector?.stop()
                tvbVoiceDirector?.resetTextStream()
                audioPlayerManager?.clearPendingAudio()
                DubbingSessionRepository.clearSubtitle()
                Log.i(TAG, "Re-sync after video seek: tracker + voice queue cleared")
            }
        }

        return START_NOT_STICKY
    }

    private fun startDubbing(resultCode: Int, data: Intent) {
        DubbingSessionRepository.setDubbingRunning(true)
        DubbingSessionRepository.setConnectionStatus("Initializing...")
        DubbingSessionRepository.clearSubtitle()

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)
        if (projection == null) {
            Log.e(TAG, "Failed to get MediaProjection! ResultCode: $resultCode")
            DubbingSessionRepository.setConnectionStatus("Capture Denied")
            DubbingSessionRepository.setDubbingRunning(false)
            return
        }

        // CRITICAL FOR ANDROID 14 (API 34): Register MediaProjection.Callback before starting capture
        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped by system")
                serviceScope.launch(Dispatchers.Main) {
                    try {
                        stopDubbing()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping dubbing from projection callback", e)
                    }
                }
            }
        }
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        mediaProjection = projection

        serviceScope.launch {
            try {
                val repository = UserPreferencesRepository(applicationContext)

                val apiKey = repository.apiKeyFlow.first()
                val targetLang = repository.targetLangFlow.first()
                val targetDialect = repository.targetDialectFlow.first()
                val volumeRatio = repository.volumeRatioFlow.first()
                val isDubbingMuted = repository.isDubbingMutedFlow.first()
                val originalVolume = repository.originalVolumeFlow.first()
                val isOriginalMuted = repository.isOriginalMutedFlow.first()
                val voicePersona = repository.voicePersonaFlow.first()
                val dubbingMode = repository.dubbingModeFlow.first()
                val showDualSubtitles = repository.showDualSubtitlesFlow.first()
                // FIX22: dubbing provider (Gemini Live / Groq) + Groq settings.
                val dubbingProvider = repository.dubbingProviderFlow.first()
                val groqKey = repository.groqApiKeyFlow.first()
                val deepseekKey = repository.deepseekApiKeyFlow.first()
                val groqTranslateModel = repository.groqTranslateModelFlow.first()
                val groqVoiceGender = repository.groqVoiceGenderFlow.first()
                val isGroqMode = dubbingProvider == UserPreferencesRepository.PROVIDER_GROQ

                // Sync repository values into the active session
                DubbingSessionRepository.setDubbingVolume(volumeRatio)
                DubbingSessionRepository.setDubbingMuted(isDubbingMuted)
                DubbingSessionRepository.setOriginalVolumeRatio(originalVolume)
                DubbingSessionRepository.setOriginalMuted(isOriginalMuted)
                DubbingSessionRepository.setShowDualSubtitles(showDualSubtitles)

                val okHttpClient = OkHttpClient.Builder()
                    .pingInterval(15, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build()

                webSocketManager = ALADWebSocketManager(okHttpClient)

                // Initialize AudioPlayerManager if not in SUBTITLE_ONLY mode
                if (dubbingMode != UserPreferencesRepository.MODE_SUBTITLE_ONLY) {
                    audioPlayerManager = AudioPlayerManager(applicationContext)
                    audioPlayerManager?.start()
                    audioPlayerManager?.setVoicePersona(voicePersona)
                    audioPlayerManager?.setDubbingAudio(volumeRatio, isDubbingMuted)
                    audioPlayerManager?.setOriginalMediaAudio(originalVolume, isOriginalMuted)
                    audioPlayerManager?.setPlaybackSpeed(DubbingSessionRepository.dubbingPlaybackSpeed.value)
                }

                webSocketManager?.onStatusChanged = { status ->
                    DubbingSessionRepository.setConnectionStatus(status)
                }

                tvbVoiceDirector = TvbVoiceDirector(applicationContext)
                dubSyncTracker = DubSyncTracker()
                tvbTextModeActive.set(false)
                // FIX23: a previous session may have left a voice warning
                // behind; the new director reports it again if it still
                // applies.
                DubbingSessionRepository.setTtsVoiceWarning(null)

                // Subtitles follow the voice, not the network: a line appears
                // when its dub actually starts speaking, i.e. when the delayed
                // video reaches that line. Without this the subtitle showed
                // seconds before its picture.
                tvbVoiceDirector?.onSegmentStarted = { segment ->
                    DubbingSessionRepository.appendSubtitleText(segment.text)
                    val director = tvbVoiceDirector
                    if (director != null) {
                        DubbingSessionRepository.setLastDubOverrunMs(director.lastOverrunMs)
                        DubbingSessionRepository.setDroppedDubSegments(director.droppedStaleCount)
                    }
                }

                // FIX23: no Vietnamese TTS voice on the phone. In Gemini
                // mode the dub voice stays off (lines fall back to
                // subtitles) and the browser screen shows why instead of
                // reading the lines aloud with a foreign voice. In Groq
                // mode there is no warning: FIX24 reads the lines with the
                // Google TTS network voice instead, so the dub just works.
                tvbVoiceDirector?.onVoiceMissing = {
                    if (!isGroqMode) {
                        DubbingSessionRepository.setTtsVoiceWarning(
                            "Máy chưa có giọng tiếng Việt — giọng đọc đang tắt. " +
                                "Cài giọng Tiếng Việt trong Cài đặt → Chuyển văn bản thành giọng nói, " +
                                "rồi tắt/bật lại lồng tiếng."
                        )
                        DubbingSessionRepository.setConnectionStatus("Thiếu giọng tiếng Việt (TTS)")
                    }
                }

                // Attach source timing to each translated line so the director
                // can wait for its picture and fit the line into it.
                fun speakTimedSegments(segments: List<TvbVoiceDirector.Segment>) {
                    if (segments.isEmpty()) return
                    val now = SystemClock.elapsedRealtime()
                    val tracker = dubSyncTracker
                    val timed = tracker?.utteranceForSegment(now)
                    if (timed?.complete == true) {
                        val utterance = timed.utterance
                        val latency = (now - utterance.endMs).coerceIn(0L, 9000L)
                        // Per-line latency: the media delay follows the live
                        // Gemini speed instead of the one-shot measurement at
                        // session start. BrowserActivity polls this value.
                        val newDelay = tracker.recordTranslationLatency(latency)
                        DubbingSessionRepository.setEstimatedDubbingDelayMs(newDelay.toInt())
                        DubbingSessionRepository.setLastSourceUtteranceMs(utterance.durationMs.toInt())
                        DubbingSessionRepository.setLastDubLatencyMs(latency.toInt())
                        Log.d(TAG, "TVB sync: source line ${utterance.durationMs}ms, " +
                            "translation latency ${latency}ms, media delay ${newDelay}ms")
                    }
                    val mediaDelay = DubbingSessionRepository.selectedMediaDelayMs.value.toLong()
                    segments.forEach { segment ->
                        val timedSegment = if (timed != null) {
                            val speakAt = timed.utterance.startMs + mediaDelay
                            if (timed.complete) {
                                segment.copy(
                                    sourceDurationMs = timed.utterance.durationMs,
                                    speakAtMs = speakAt,
                                    deadlineMs = speakAt + timed.utterance.durationMs +
                                        TvbVoiceDirector.DEADLINE_GRACE_MS
                                )
                            } else {
                                // Speaker still talking: the start time is
                                // known, the full length is not. Wait for the
                                // picture, but do not trust the provisional
                                // length for the speaking rate.
                                segment.copy(speakAtMs = speakAt)
                            }
                        } else {
                            segment
                        }
                        if (tvbVoiceDirector?.speakSegment(timedSegment) == true) {
                            tvbTextModeActive.set(true)
                        } else {
                            // TTS not ready: show the subtitle now instead of
                            // losing the line entirely.
                            DubbingSessionRepository.appendSubtitleText(segment.text)
                        }
                    }
                }

                // FIX22: one Groq line, timed against its own source
                // utterance. The latency sample is measured from the
                // utterance START (not its end): the media delay must cover
                // the whole line plus the Whisper + translate time, or the
                // dub starts before its picture (same fix as the Groq
                // userscript v1.0.2). Otherwise the line flows through the
                // same timed TTS path as a Gemini line.
                fun speakGroqLine(line: GroqDubbingManager.GroqLine, gender: TvbVoiceDirector.Gender) {
                    val now = SystemClock.elapsedRealtime()
                    val tracker = dubSyncTracker
                    val latency = (now - line.utteranceStartMs).coerceIn(0L, 9000L)
                    if (tracker != null) {
                        val newDelay = tracker.recordTranslationLatency(latency)
                        DubbingSessionRepository.setEstimatedDubbingDelayMs(newDelay.toInt())
                        DubbingSessionRepository.setLastSourceUtteranceMs(line.durationMs.toInt())
                        DubbingSessionRepository.setLastDubLatencyMs(latency.toInt())
                        Log.d(TAG, "Groq sync: source line ${line.durationMs}ms, " +
                            "translation latency ${latency}ms, media delay ${newDelay}ms")
                    }
                    val mediaDelay = DubbingSessionRepository.selectedMediaDelayMs.value.toLong()
                    val speakAt = line.utteranceStartMs + mediaDelay
                    val deadline = speakAt + line.durationMs + TvbVoiceDirector.DEADLINE_GRACE_MS
                    // FIX24: when the phone has no Vietnamese TTS voice,
                    // Groq lines are read by the Google TTS network voice
                    // instead of the (voiceless) device TTS. Otherwise the
                    // line flows through the same timed TTS path as a
                    // Gemini line.
                    val director = tvbVoiceDirector
                    if (director?.voiceMissing == true) {
                        val speaker = googleTtsSpeaker
                        if (speaker?.speak(line.translatedText, speakAt, deadline, line.durationMs) != true) {
                            DubbingSessionRepository.appendSubtitleText(line.translatedText)
                        }
                        return
                    }
                    val segment = TvbVoiceDirector.Segment(
                        speakerId = "groq",
                        gender = gender,
                        text = line.translatedText,
                        sourceDurationMs = line.durationMs,
                        speakAtMs = speakAt,
                        deadlineMs = deadline
                    )
                    if (tvbVoiceDirector?.speakSegment(segment) != true) {
                        // TTS not ready: show the subtitle now instead of losing the line.
                        DubbingSessionRepository.appendSubtitleText(line.translatedText)
                    }
                }

                webSocketManager?.onTextMessageReceived = { textDelta ->
                    // Gemini may split a speaker marker across WebSocket deltas.
                    // Buffer until a complete speaker-tagged phrase is available.
                    val segments = tvbVoiceDirector?.consumeTextDelta(textDelta).orEmpty()
                    speakTimedSegments(segments)
                }

                webSocketManager?.onSourceTextReceived = { sourceChunk ->
                    DubbingSessionRepository.appendSourceSubtitleText(sourceChunk)
                }

                webSocketManager?.onTurnComplete = {
                    // Close the open source utterance so its final length is
                    // known before the last phrase is timed.
                    dubSyncTracker?.endCurrentUtterance()
                    // Flush the final phrase: it has no subsequent speaker marker.
                    tvbVoiceDirector?.flushTextStream()?.let { speakTimedSegments(it) }
                    DubbingSessionRepository.onTurnComplete()
                }

                var activeDubbingMode = dubbingMode
                val firstAudioSentAtMs = AtomicLong(0L)
                val firstTranslatedAudioMeasured = AtomicBoolean(false)

                webSocketManager?.onBinaryMessageReceived = { audioChunk ->
                    if (!firstTranslatedAudioMeasured.get()) {
                        val sentAt = firstAudioSentAtMs.get()
                        if (sentAt > 0L) {
                            val elapsed = (SystemClock.elapsedRealtime() - sentAt).coerceIn(0L, 8000L)
                            // Add a modest playback safety margin; keep the automatic range bounded.
                            val adaptiveDelay = (elapsed + 2000L).coerceIn(3000L, 10000L).toInt()
                            DubbingSessionRepository.setEstimatedDubbingDelayMs(adaptiveDelay)
                            val selectedDelay = DubbingSessionRepository.selectedMediaDelayMs.value
                            val remainingVoiceDelay = (selectedDelay - elapsed).coerceAtLeast(0L)
                            audioPlayerManager?.setInitialVoiceDelayMs(remainingVoiceDelay)
                            firstTranslatedAudioMeasured.set(true)
                            Log.i(TAG, "First translation latency=${elapsed}ms; source delay=${selectedDelay}ms; translated voice hold=${remainingVoiceDelay}ms")
                        }
                    }
                    if (activeDubbingMode != UserPreferencesRepository.MODE_SUBTITLE_ONLY && !tvbTextModeActive.get()) {
                        audioPlayerManager?.playAudioData(audioChunk)
                    }
                }

                if (isGroqMode) {
                    // FIX22: Groq provider — Whisper hears, a chat model
                    // translates (with context memory), Android TTS reads.
                    // Always the text/TTS path: no Gemini binary audio here.
                    tvbTextModeActive.set(true)
                    val groqGender = if (groqVoiceGender == UserPreferencesRepository.GROQ_GENDER_MALE) {
                        TvbVoiceDirector.Gender.MALE
                    } else {
                        TvbVoiceDirector.Gender.FEMALE
                    }
                    val trackerForGroq = dubSyncTracker
                    if (trackerForGroq != null) {
                        groqDubbingManager = GroqDubbingManager(
                            client = okHttpClient,
                            tracker = trackerForGroq,
                            groqKey = groqKey,
                            deepseekKey = deepseekKey,
                            translateModelKey = groqTranslateModel
                        )
                        groqDubbingManager?.onStatus = { status ->
                            DubbingSessionRepository.setConnectionStatus(status)
                        }
                        groqDubbingManager?.onSourceText = { source ->
                            DubbingSessionRepository.appendSourceSubtitleText(source)
                        }
                        groqDubbingManager?.onLine = { line ->
                            speakGroqLine(line, groqGender)
                        }
                        groqDubbingManager?.start()

                        // FIX24: Google TTS network voice for Groq lines.
                        // Used only when the phone has no Vietnamese TTS
                        // voice (see speakGroqLine); otherwise it stays
                        // idle. Same subtitle/stats wiring as the director.
                        googleTtsSpeaker = GoogleTtsSpeaker(
                            context = applicationContext,
                            client = okHttpClient,
                            scope = serviceScope,
                            initialVolume = volumeRatio
                        )
                        googleTtsSpeaker?.onSegmentStarted = { text ->
                            DubbingSessionRepository.appendSubtitleText(text)
                            val speaker = googleTtsSpeaker
                            if (speaker != null) {
                                DubbingSessionRepository.setLastDubOverrunMs(speaker.lastOverrunMs)
                                DubbingSessionRepository.setDroppedDubSegments(speaker.droppedStaleCount)
                            }
                        }
                        googleTtsSpeaker?.onLineFailed = { text ->
                            DubbingSessionRepository.appendSubtitleText(text)
                        }
                        googleTtsSpeaker?.onStatsChanged = {
                            val speaker = googleTtsSpeaker
                            if (speaker != null) {
                                DubbingSessionRepository.setLastDubOverrunMs(speaker.lastOverrunMs)
                                DubbingSessionRepository.setDroppedDubSegments(speaker.droppedStaleCount)
                            }
                        }
                        googleTtsSpeaker?.onTtsUnavailable = {
                            DubbingSessionRepository.setTtsVoiceWarning(
                                "Không tải được giọng đọc Google — kiểm tra mạng rồi tắt/bật lại lồng tiếng."
                            )
                        }
                        googleTtsSpeaker?.start()
                    }
                } else {
                    webSocketManager?.connect(
                        apiKey = apiKey,
                        sourceLang = "",
                        targetLang = targetLang,
                        voicePersona = voicePersona,
                        dubbingMode = dubbingMode,
                        targetDialect = targetDialect
                    )
                }

                // Dynamic observation for audio controls and settings
                settingsObservationJob?.cancel()
                settingsObservationJob = serviceScope.launch {
                    // 1. Reactive dubbing volume updates
                    launch {
                        kotlinx.coroutines.flow.combine(
                            DubbingSessionRepository.dubbingVolume,
                            DubbingSessionRepository.isDubbingMuted
                        ) { vol, muted -> Pair(vol, muted) }.collect { (vol, muted) ->
                            audioPlayerManager?.setDubbingAudio(vol, muted)
                            // FIX24: the Google TTS voice follows the same slider.
                            googleTtsSpeaker?.setVolume(if (muted) 0f else vol)
                        }
                    }

                    // 2. Reactive original media volume & ducking updates
                    launch {
                        kotlinx.coroutines.flow.combine(
                            DubbingSessionRepository.originalVolumeRatio,
                            DubbingSessionRepository.isOriginalMuted
                        ) { ratio, muted -> Pair(ratio, muted) }.collect { (ratio, muted) ->
                            audioPlayerManager?.setOriginalMediaAudio(ratio, muted)
                        }
                    }

                    // 3. Apply translated voice playback speed immediately when adjusted.
                    launch {
                        DubbingSessionRepository.dubbingPlaybackSpeed.collect { speed ->
                            audioPlayerManager?.setPlaybackSpeed(speed)
                            // FIX24: the Google TTS voice follows the same speed.
                            googleTtsSpeaker?.setUserSpeed(speed)
                        }
                    }

                    // 4. Real-time voice persona timbre shaping without dropping WebSocket
                    launch {
                        repository.voicePersonaFlow.collect { newVoice ->
                            audioPlayerManager?.setVoicePersona(newVoice)
                        }
                    }

                    // 5. Real-time dubbing mode switching without dropping WebSocket
                    launch {
                        repository.dubbingModeFlow.collect { newMode ->
                            activeDubbingMode = newMode
                        }
                    }

                    // 6. Reconnect WebSocket ONLY when target language or dialect genuinely changes
                    // (Gemini mode only: Groq has no WebSocket to reconnect).
                    launch {
                        if (isGroqMode) return@launch
                        var activeLang = targetLang
                        var activeDialect = targetDialect
                        kotlinx.coroutines.flow.combine(
                            repository.targetLangFlow,
                            repository.targetDialectFlow
                        ) { lang, dialect -> Pair(lang, dialect) }.collect { (newLang, newDialect) ->
                            if (newLang != activeLang || newDialect != activeDialect) {
                                activeLang = newLang
                                activeDialect = newDialect
                                val currentKey = repository.apiKeyFlow.first()
                                val currentVoice = repository.voicePersonaFlow.first()
                                val currentMode = repository.dubbingModeFlow.first()
                                webSocketManager?.disconnect()
                                webSocketManager?.connect(
                                    apiKey = currentKey,
                                    sourceLang = "",
                                    targetLang = newLang,
                                    voicePersona = currentVoice,
                                    dubbingMode = currentMode,
                                    targetDialect = newDialect
                                )
                            }
                        }
                    }
                }

                // One shared path for every captured PCM block, whichever tap
                // produced it. Besides forwarding to Gemini it feeds the TVB
                // sync tracker, so the dub knows when each source line starts
                // and ends. Both taps must use this: feeding only one of them
                // would leave the tracker blind whenever the other is active.
                val onSourcePcm: (ByteArray) -> Unit = { pcmData ->
                    val now = SystemClock.elapsedRealtime()
                    firstAudioSentAtMs.compareAndSet(0L, now)
                    dubSyncTracker?.feedPcm(pcmData, now)
                    if (isGroqMode) {
                        // FIX22: Groq buffers the PCM and slices each
                        // finished utterance out for Whisper.
                        groqDubbingManager?.feedPcm(pcmData, now)
                    } else {
                        webSocketManager?.sendAudioData(pcmData)
                    }
                    val currentAmp = DubbingSessionRepository.audioAmplitude.value
                    DubbingSessionRepository.setAudioAmplitude(
                        AudioUtils.processPcmAmplitude(pcmData, currentAmp)
                    )
                }

                // Prefer the in-WebView WebAudio tap. It splits source PCM before
                // DelayNode playback, so Gemini receives live audio while speakers
                // receive the delayed original mix. System capture remains fallback
                // for pages where WebAudio is unavailable or CORS/DRM returns silence.
                BrowserAudioBridge.setListeners(
                    onPcm = onSourcePcm,
                    onStatus = { message -> Log.i(TAG, "Browser audio: $message") },
                    onModeChanged = { webAudioActive ->
                        serviceScope.launch(Dispatchers.IO) {
                            if (webAudioActive) {
                                // Stop system playback capture only after the first
                                // non-silent WebAudio PCM block has reached this service.
                                audioCaptureManager?.stopCapture()
                                audioCaptureManager = null
                                Log.i(TAG, "WebAudio active: system capture stopped to prevent duplicate PCM")
                            } else if (DubbingSessionRepository.isDubbingRunning.value &&
                                audioCaptureManager == null && mediaProjection != null) {
                                val projectionForFallback = mediaProjection
                                audioCaptureManager = AudioCaptureManager()
                                audioCaptureManager?.startCapture(projectionForFallback!!, applicationInfo.uid) { pcmData ->
                                    if (BrowserAudioBridge.isActive) return@startCapture
                                    onSourcePcm(pcmData)
                                }
                                Log.w(TAG, "WebAudio unavailable: system capture fallback resumed")
                            }
                        }
                    }
                )

                audioCaptureManager = AudioCaptureManager()
                val appUid = applicationInfo.uid
                if (!BrowserAudioBridge.isActive) mediaProjection?.let { projection ->
                    audioCaptureManager?.startCapture(projection, appUid) { pcmData ->
                        // Once a non-silent WebAudio block arrives, stop forwarding
                        // system-captured copies to prevent duplicate Gemini input.
                        if (BrowserAudioBridge.isActive) return@startCapture
                        onSourcePcm(pcmData)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during dubbing session startup", e)
                DubbingSessionRepository.setConnectionStatus("Error: ${e.message}")
            }
        }
    }

    private fun stopDubbing() {
        BrowserAudioBridge.reset()
        DubbingSessionRepository.resetSession()

        audioCaptureManager?.stopCapture()
        audioCaptureManager = null

        audioPlayerManager?.stop()
        audioPlayerManager = null
        tvbVoiceDirector?.shutdown()
        tvbVoiceDirector = null
        // FIX22: stop the Groq manager before the tracker goes away — its
        // poll job and utterance callback both touch the tracker.
        groqDubbingManager?.stop()
        groqDubbingManager = null
        // FIX24: stop the Google TTS speaker with the session.
        googleTtsSpeaker?.stop()
        googleTtsSpeaker = null
        dubSyncTracker?.reset()
        dubSyncTracker = null
        tvbTextModeActive.set(false)

        webSocketManager?.disconnect()
        webSocketManager = null

        settingsObservationJob?.cancel()
        settingsObservationJob = null

        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (DubbingSessionRepository.isDubbingRunning.value) {
            stopDubbing()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Live Dubbing Service"
            val descriptionText = "Capturing and streaming audio for dubbing"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, com.alad.app.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, AudioDubbingForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 1, stopIntent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ALAD Live Dubbing")
            .setContentText("Connected and dubbing...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopPendingIntent)
            .setOngoing(true)
            .build()
        }
}
