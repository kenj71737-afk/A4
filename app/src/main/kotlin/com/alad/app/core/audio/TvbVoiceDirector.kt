package com.alad.app.core.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

/**
 * Ordered Vietnamese voice director for Gemini speaker-tagged text.
 *
 * The reading voice follows the classic Vietnamese TVB dubbing delivery
 * the user pointed at (HTV2 interview with the Saigon Film dubbing team —
 * "Sài Gòn phim dịch và lồng tiếng, FaFilm Việt Nam phát hành..."):
 * warm, expressive ("truyền cảm"), clear, measured, with steady drama
 * that never shouts, and one steady voice per character across the drama.
 *
 * This is a generic style approximation through Android TTS pitch/rate
 * only. It does NOT reproduce any real dubbing actor's voice — no
 * recording or voice model of any actor is used, and no actor is named
 * as the voice. If the installed TTS engine has a warmer Vietnamese
 * voice, picking it in the system TTS settings will do more for the
 * sound than any pitch number here.
 *
 * Sync rules (see [DubSyncTracker] for where the timing comes from):
 * - `speakAtMs` is the moment the delayed video reaches this line. The
 *   director waits until then instead of speaking the moment Gemini's text
 *   arrives (which is seconds before the delayed picture gets there).
 * - `sourceDurationMs` is how long the original line lasts. The TTS rate is
 *   raised so the Vietnamese line fits inside it: a line that would take
 *   4 s to say at normal speed but only has a 2.5 s mouth movement is
 *   spoken faster, instead of running over into the next line.
 * - `deadlineMs` is the last moment the line may still start. A line that
 *   is already past its picture is dropped, not queued: speaking it late
 *   would push every following line late too, which is how dubbing drift
 *   used to accumulate over a whole episode.
 * - Subtitles follow the voice, not the network: [onSegmentStarted] fires
 *   when the utterance actually starts speaking, so the subtitle appears
 *   with its picture instead of seconds early.
 */
class TvbVoiceDirector(context: Context) : TextToSpeech.OnInitListener {
    /** Speaker identity; gender selects the classic-dubbing profile below. */
    enum class Gender { MALE, FEMALE }

    /**
     * Classic Vietnamese TVB dubbing delivery (see the class note).
     * Male: a touch deeper and slower than flat TTS — warm and steady.
     * Female: warm and clear ("êm"), near-natural pitch, measured pace.
     * The values stay close to neutral on purpose: the style is in the
     * steadiness and clarity, not in an exaggerated low/high voice.
     */
    private object ClassicDubStyle {
        const val MALE_PITCH = 0.94f
        const val MALE_BASE_RATE = 0.95f
        const val FEMALE_PITCH = 1.04f
        const val FEMALE_BASE_RATE = 0.97f
    }

    /**
     * Timing fields are elapsedRealtime ms. All three default to 0, which
     * means "no timing known": speak immediately at the base rate, never
     * drop. That keeps the old untimed path working unchanged.
     */
    data class Segment(
        val speakerId: String,
        val gender: Gender,
        val text: String,
        val sourceDurationMs: Long = 0L,
        val speakAtMs: Long = 0L,
        val deadlineMs: Long = 0L
    )

    companion object {
        private const val TAG = "TvbVoiceDirector"
        private val MARKER = Regex("\\[\\[TVB:([A-Za-z0-9_-]{1,32}):(M|F)\\]\\]", RegexOption.IGNORE_CASE)

        /** Google's TTS engine package. Tried when the phone's default TTS
         * engine has no Vietnamese voice: many OEM engines (Samsung,
         * Xiaomi, Oppo...) report vi-VN as "available" but have no vi voice
         * installed, and then read Vietnamese text with a foreign voice.
         * Google TTS is the engine most likely to have a real vi-VN voice. */
        const val GOOGLE_TTS_ENGINE = "com.google.android.tts"

        /**
         * Picks the best real Vietnamese voice out of an engine's voice set.
         * Prefers a downloaded (non-network) voice, then vi-VN over another
         * vi country, then the highest quality. Returns null when the engine
         * has no Vietnamese voice at all — that is the case `setLanguage`
         * alone cannot detect, and the reason the dub used to read
         * Vietnamese text with a foreign voice instead of warning.
         */
        fun pickVietnameseVoice(voices: Set<android.speech.tts.Voice>?): android.speech.tts.Voice? {
            return voices.orEmpty()
                .filter { it.locale.language.equals("vi", ignoreCase = true) }
                .maxWithOrNull(
                    compareBy<android.speech.tts.Voice> { !it.isNetworkConnectionRequired }
                        .thenBy { it.locale.country.equals("VN", ignoreCase = true) }
                        .thenBy { it.quality }
                )
        }

        /** Rough Vietnamese TTS pace at rate 1.0: ~85 ms per character
         * (about 12 characters per second). Only a fit estimate; the exact
         * pace depends on the installed TTS engine. */
        const val MS_PER_CHAR_AT_RATE_1 = 85f

        /** TTS rate is never slowed below this to stretch a short line, and
         * never pushed above this to squeeze a long one: beyond 1.30 the
         * voice sounds rushed and the line is reported as overrun instead. */
        const val MIN_SYNC_RATE = 0.85f
        const val MAX_SYNC_RATE = 1.30f

        /** Grace after the source line ends before a queued line is stale. */
        const val DEADLINE_GRACE_MS = 900L

        fun parseTaggedSegment(raw: String): Segment? {
            val match = MARKER.find(raw.trim()) ?: return null
            val body = raw.trim().substring(match.range.last + 1).trim()
            if (body.isBlank()) return null
            val gender = if (match.groupValues[2].equals("M", true)) Gender.MALE else Gender.FEMALE
            return Segment(match.groupValues[1], gender, body)
        }

        /** Estimated speaking time of [text] at the given TTS rate. */
        fun estimateSpeechDurationMs(text: String, rate: Float): Long =
            (text.length * MS_PER_CHAR_AT_RATE_1 / rate.coerceAtLeast(0.5f)).toLong()

        /**
         * TTS rate that fits [text] into [sourceDurationMs]. Returns
         * [baseRate] when no source duration is known. The result is
         * clamped to [MIN_SYNC_RATE]..[MAX_SYNC_RATE]; if the line still
         * does not fit at the maximum rate, the caller sees the overrun in
         * [TvbVoiceDirector.lastOverrunMs] and the next line's deadline
         * protects the following picture.
         */
        fun requiredRate(text: String, sourceDurationMs: Long, baseRate: Float): Float {
            if (sourceDurationMs <= 0L || text.isBlank()) return baseRate
            val rate = text.length * MS_PER_CHAR_AT_RATE_1 / sourceDurationMs
            return rate.coerceIn(MIN_SYNC_RATE, MAX_SYNC_RATE)
        }
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Segment>()
    private val streamLock = Any()
    private val queueLock = Any()
    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var closed = false
    private var speaking = false
    private var startPending = false
    private var activeSegment: Segment? = null
    private val streamBuffer = StringBuilder()
    private var activeSpeakerId: String? = null
    private var activeGender: Gender? = null
    private val streamMarker = MARKER

    /** Fired when an utterance actually starts speaking (for subtitle sync). */
    var onSegmentStarted: ((Segment) -> Unit)? = null

    /**
     * Fired once when no Vietnamese voice exists in any TTS engine on the
     * phone. The director stays not-ready, so lines fall back to subtitles
     * instead of being read aloud with a foreign voice. The service shows
     * the install instructions on screen from this callback.
     */
    var onVoiceMissing: (() -> Unit)? = null

    /** True once the director has established that no vi voice exists. */
    @Volatile var voiceMissing = false
        private set

    /** Name of the Vietnamese voice actually selected, for diagnostics. */
    @Volatile var selectedVoiceName: String? = null
        private set

    private var triedGoogleEngine = false

    /** Sync diagnostics, read by the service/UI. */
    @Volatile var droppedStaleCount: Int = 0
        private set
    @Volatile var lastRequiredRate: Float = 1f
        private set
    @Volatile var lastOverrunMs: Long = 0L
        private set
    /** Actual start minus the planned start; near 0 means on picture. */
    @Volatile var lastSyncOffsetMs: Long = 0L
        private set

    init { engine = TextToSpeech(appContext, this) }

    /** Buffers fragmented Gemini deltas; markers may span WebSocket events. */
    fun consumeTextDelta(delta: String): List<Segment> = synchronized(streamLock) {
        if (delta.isNotEmpty()) streamBuffer.append(delta)
        val emitted = mutableListOf<Segment>()
        while (true) {
            val snapshot = streamBuffer.toString()
            val marker = streamMarker.find(snapshot)
            if (marker == null) {
                // Preserve a possible partial marker at the end of the buffer.
                if (streamBuffer.length > 4096) {
                    val keep = minOf(128, streamBuffer.length)
                    streamBuffer.delete(0, streamBuffer.length - keep)
                }
                break
            }
            val previousSpeaker = activeSpeakerId
            val previousGender = activeGender
            if (previousSpeaker != null && previousGender != null) {
                val body = snapshot.substring(0, marker.range.first).trim()
                if (body.isNotEmpty()) emitted += Segment(previousSpeaker, previousGender, body)
            }
            streamBuffer.delete(0, marker.range.last + 1)
            activeSpeakerId = marker.groupValues[1]
            activeGender = if (marker.groupValues[2].equals("M", true)) Gender.MALE else Gender.FEMALE
        }
        emitted
    }

    /** Emits the final phrase at Gemini turnComplete. */
    fun flushTextStream(): List<Segment> = synchronized(streamLock) {
        val body = streamBuffer.toString().trim()
        val id = activeSpeakerId
        val gender = activeGender
        streamBuffer.setLength(0)
        activeSpeakerId = null
        activeGender = null
        if (body.isNotEmpty() && id != null && gender != null) listOf(Segment(id, gender, body)) else emptyList()
    }

    fun resetTextStream() = synchronized(streamLock) {
        streamBuffer.setLength(0)
        activeSpeakerId = null
        activeGender = null
    }

    override fun onInit(status: Int) {
        val tts = engine ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Android TTS initialization failed: $status")
            return
        }
        // FIX23: `setLanguage(vi-VN)` alone is not proof of a Vietnamese
        // voice. Several OEM engines report the language as available while
        // having no vi voice installed, and then read the Vietnamese lines
        // with a foreign voice — the "reads some other language" bug that
        // has been there since the TVB voice was introduced. So pick a real
        // Vietnamese voice out of the engine's voice set and force it with
        // setVoice(); only that counts as ready.
        if (!selectVietnameseVoice(tts)) {
            // No vi voice in this engine. If the Google TTS engine is
            // installed, retry once with it — it is the engine most likely
            // to carry a real vi-VN voice.
            if (!triedGoogleEngine && hasEngine(tts, GOOGLE_TTS_ENGINE)) {
                triedGoogleEngine = true
                Log.w(TAG, "No Vietnamese voice in the default TTS engine; " +
                    "retrying with the Google TTS engine")
                mainHandler.post { switchEngine(GOOGLE_TTS_ENGINE) }
                return
            }
            voiceMissing = true
            ready = false
            Log.e(TAG, "No Vietnamese TTS voice on this phone: the TVB voice " +
                "stays off and lines fall back to subtitles")
            try {
                onVoiceMissing?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "onVoiceMissing failed", e)
            }
            return
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val segment = activeSegment ?: return
                if (segment.speakAtMs > 0L) {
                    lastSyncOffsetMs = SystemClock.elapsedRealtime() - segment.speakAtMs
                }
                try {
                    onSegmentStarted?.invoke(segment)
                } catch (e: Exception) {
                    Log.w(TAG, "onSegmentStarted failed", e)
                }
            }
            override fun onDone(utteranceId: String?) = mainHandler.post { finishCurrentUtterance() }.let { Unit }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = mainHandler.post { finishCurrentUtterance() }.let { Unit }
            override fun onError(utteranceId: String?, errorCode: Int) = mainHandler.post { finishCurrentUtterance() }.let { Unit }
        })
        Log.i(TAG, "Vietnamese Android TTS ready, voice=$selectedVoiceName")
        ready = true
        mainHandler.post { startNextUtterance() }
    }

    /**
     * Sets the language AND forces a real Vietnamese voice. Returns true
     * only when a vi voice was found and selected; false means this engine
     * cannot read Vietnamese, whatever `setLanguage` reported.
     */
    private fun selectVietnameseVoice(tts: TextToSpeech): Boolean {
        val langResult = tts.setLanguage(Locale("vi", "VN"))
        if (langResult == TextToSpeech.LANG_MISSING_DATA ||
            langResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Log.w(TAG, "TTS engine reports vi-VN unavailable: $langResult")
            return false
        }
        val voice = try {
            pickVietnameseVoice(tts.voices)
        } catch (e: Exception) {
            Log.w(TAG, "Could not list TTS voices", e)
            null
        } ?: run {
            Log.w(TAG, "TTS engine has no Vietnamese voice installed")
            return false
        }
        if (tts.setVoice(voice) != TextToSpeech.SUCCESS) {
            // The voice exists but the engine refused to select it; the
            // language is still set to vi-VN, so speak with the engine's
            // default for the locale rather than giving up entirely.
            Log.w(TAG, "setVoice(${voice.name}) refused; keeping language-only vi-VN")
        }
        selectedVoiceName = voice.name
        return true
    }

    /** True when [enginePackage] is among the installed TTS engines. */
    private fun hasEngine(tts: TextToSpeech, enginePackage: String): Boolean {
        return try {
            tts.engines.orEmpty().any { it.name == enginePackage }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list TTS engines", e)
            false
        }
    }

    /** Shuts the current engine down and re-creates it with [enginePackage].
     * Its onInit runs the same voice selection again. */
    private fun switchEngine(enginePackage: String) {
        if (closed) return
        try {
            engine?.stop()
            engine?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Could not shut down the previous TTS engine", e)
        }
        engine = TextToSpeech(appContext, this, enginePackage)
    }

    /** Accepts a tagged line and queues it in arrival order. */
    fun speakTagged(raw: String): Boolean {
        val segment = parseTaggedSegment(raw) ?: return false
        return speakSegment(segment)
    }

    fun speakSegment(segment: Segment): Boolean {
        if (!ready || closed || segment.text.isBlank()) return false
        synchronized(queueLock) { queue.addLast(segment) }
        mainHandler.post { startNextUtterance() }
        return true
    }

    private fun startNextUtterance() {
        if (!ready || closed || speaking) return
        val now = SystemClock.elapsedRealtime()
        val next = synchronized(queueLock) {
            // Drop lines whose picture has already passed, oldest first.
            // Speaking one late line would push every following line late.
            while (queue.isNotEmpty()) {
                val head = queue.first()
                if (head.deadlineMs > 0L && now > head.deadlineMs) {
                    queue.removeFirst()
                    droppedStaleCount++
                    Log.w(TAG, "Dropping stale TVB line for speaker=${head.speakerId}: " +
                        "deadline passed ${now - head.deadlineMs}ms ago")
                    continue
                }
                break
            }
            if (queue.isEmpty()) null else queue.first()
        } ?: return

        // Wait until the delayed video reaches this line.
        if (next.speakAtMs > now) {
            if (!startPending) {
                startPending = true
                mainHandler.postDelayed(
                    { startPending = false; startNextUtterance() },
                    next.speakAtMs - now
                )
            }
            return
        }

        synchronized(queueLock) {
            if (queue.isEmpty() || queue.first() != next) return
            queue.removeFirst()
        }
        val tts = engine ?: return
        speaking = true
        activeSegment = next
        // The voice settings are applied only when this utterance becomes
        // active. QUEUE_FLUSH prevents a previous utterance's settings from
        // affecting it. Pitch/rate follow the classic-dubbing profile above;
        // the rate then moves further only to fit the line into its picture
        // (see requiredRate).
        val baseRate = if (next.gender == Gender.MALE) {
            ClassicDubStyle.MALE_BASE_RATE
        } else {
            ClassicDubStyle.FEMALE_BASE_RATE
        }
        val pitch = if (next.gender == Gender.MALE) {
            ClassicDubStyle.MALE_PITCH
        } else {
            ClassicDubStyle.FEMALE_PITCH
        }
        val rate = requiredRate(next.text, next.sourceDurationMs, baseRate)
        lastRequiredRate = rate
        lastOverrunMs = if (next.sourceDurationMs > 0L) {
            (estimateSpeechDurationMs(next.text, rate) - next.sourceDurationMs).coerceAtLeast(0L)
        } else {
            0L
        }
        if (lastOverrunMs > 0L) {
            Log.w(TAG, "TVB line for speaker=${next.speakerId} overruns its picture by " +
                "${lastOverrunMs}ms even at max rate ${MAX_SYNC_RATE}x")
        }
        tts.setPitch(pitch)
        tts.setSpeechRate(rate)
        val utteranceId = "alad-${next.speakerId}-${UUID.randomUUID()}"
        val result = tts.speak(next.text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS rejected utterance for speaker=${next.speakerId}")
            speaking = false
            activeSegment = null
            mainHandler.post { startNextUtterance() }
        }
    }

    private fun finishCurrentUtterance() {
        speaking = false
        activeSegment = null
        startNextUtterance()
    }

    fun stop() {
        startPending = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            synchronized(queueLock) { queue.clear() }
            speaking = false
            activeSegment = null
            engine?.stop()
        }
    }

    fun shutdown() {
        closed = true
        ready = false
        startPending = false
        onSegmentStarted = null
        onVoiceMissing = null
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            synchronized(queueLock) { queue.clear() }
            speaking = false
            activeSegment = null
            engine?.stop()
            engine?.shutdown()
            engine = null
        }
        resetTextStream()
    }
}
