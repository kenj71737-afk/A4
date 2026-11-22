package com.alad.app.core.network

import android.os.SystemClock
import android.util.Log
import com.alad.app.core.audio.DubSyncTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FIX22: Groq dubbing provider for ALAD — the same pipeline as the Groq
 * Tampermonkey script (v1.3.0), running inside the app instead of the
 * browser:
 *
 *   captured PCM -> [DubSyncTracker] utterance -> Groq Whisper (STT)
 *   -> Groq chat / DeepSeek (translate, with context memory)
 *   -> [onLine] -> service -> TvbVoiceDirector (Android TTS) + subtitles
 *
 * Why a second provider: Gemini Live is one WebSocket that hears,
 * translates and speaks in a single call, but its free tier is small and
 * its Chinese translation has no conversation memory. Groq splits the
 * work into three cheap calls: Whisper hears the line, a chat model
 * translates it remembering the previous 6 lines (names and address
 * terms stay consistent), and the phone's own Vietnamese TTS reads it —
 * the same TTS the Gemini path already uses, so the voice is identical.
 *
 * Timing: the utterance's own start/end come from [DubSyncTracker] (one
 * VAD for the whole app). The service turns each emitted line into a
 * timed TvbVoiceDirector segment (speakAt = utterance start + media
 * delay) exactly like a Gemini line, so the existing sync clock, media
 * delay and catch-up behaviour all keep working unchanged.
 *
 * The manager owns one worker coroutine: utterances are processed
 * strictly in order, one at a time. If lines arrive faster than the
 * network answers, the oldest pending utterance is dropped (translating
 * a stale line wastes API calls and its picture has already passed).
 */
class GroqDubbingManager(
    private val client: OkHttpClient,
    private val tracker: DubSyncTracker,
    private val groqKey: String,
    private val deepseekKey: String,
    private val translateModelKey: String
) {

    /** One translated line, timed against its own source utterance. */
    data class GroqLine(
        val sourceText: String,
        val translatedText: String,
        val utteranceStartMs: Long,
        val utteranceEndMs: Long
    ) {
        val durationMs: Long get() = (utteranceEndMs - utteranceStartMs).coerceAtLeast(0L)
    }

    data class TranslateModel(
        val key: String,
        val id: String,
        val label: String,
        val provider: String // "groq" or "deepseek"
    )

    companion object {
        private const val TAG = "GroqDubbingManager"

        const val GROQ_API = "https://api.groq.com/openai/v1"
        const val DEEPSEEK_API = "https://api.deepseek.com/v1"
        const val WHISPER_MODEL = "whisper-large-v3-turbo"

        /** Same model set as the Groq userscript. Llama is gone from
         * Groq's free tier (retired 2026-08-16) — do not add it back. */
        val TRANSLATE_MODELS = listOf(
            TranslateModel("fast", "openai/gpt-oss-20b", "GPT-OSS 20B — nhanh", "groq"),
            TranslateModel("best", "openai/gpt-oss-120b", "GPT-OSS 120B — dịch hay hơn", "groq"),
            TranslateModel("qwen", "qwen/qwen3.8-27b", "Qwen 3.8 27B — chuyên tiếng Trung", "groq"),
            TranslateModel("deepseek", "deepseek-chat", "DeepSeek — hay nhất tiếng Trung", "deepseek")
        )

        fun modelForKey(key: String): TranslateModel =
            TRANSLATE_MODELS.find { it.key == key } ?: TRANSLATE_MODELS[0]

        /** Groq chat models only, in fallback order (used when the chosen
         * model 404s/rate-limits, and as the DeepSeek fallback). */
        private val GROQ_FALLBACK_ORDER = listOf("fast", "best", "qwen")

        private const val SYSTEM_PROMPT =
            "You are a simultaneous interpreter for a video, translating dialogue into spoken Vietnamese. " +
                "Translate into concise, natural spoken Vietnamese in the style of a classic Vietnamese TVB drama dub: warm, expressive and clear, with natural Vietnamese address terms (anh/em/chi/co/chu...) matching the characters' relationship, never stiff textbook Vietnamese. " +
                "Use the previous lines (if any) for context: keep character names and address terms consistent with them. " +
                "Output ONLY the Vietnamese translation, no explanations, no quotes, no brackets. " +
                "Translate only meaningful speech. If the input is only non-verbal sounds (cries of pain, groans, moans, gasps, sighs, laughter, coughing, filler sounds), output an empty string."

        /** History kept for translation context (same 6 pairs as the script). */
        private const val HISTORY_MAX = 6

        /** Utterances are processed one at a time; at most this many wait. */
        private const val MAX_PENDING_UTTERANCES = 2

        /** Rolling PCM buffer: utterances are sliced out of it by time. */
        private const val BUFFER_KEEP_MS = 60_000L

        /** Padding around an utterance so the VAD edges never clip a phoneme. */
        private const val UTTERANCE_PAD_MS = 100L

        /** Never send more than this much audio to Whisper in one call. */
        private const val MAX_UTTERANCE_MS = 20_000L

        /** After a 429 from every Groq model, pause translating this long. */
        private const val GROQ_COOLDOWN_MS = 60_000L

        private const val POLL_INTERVAL_MS = 150L

        // ---- Meaningless-speech filter (FIX21, ported from the Groq script) ----
        // A line made only of cries/groans/laughter/filler is skipped: no
        // translate call, no TTS, no subtitle. A line with at least one real
        // word ("á, đau quá!") still passes.

        private val INTERJECTIONS = setOf(
            "á", "à", "ả", "ã", "ạ", "â", "a", "e", "è", "é", "ê", "o", "ô", "ồ", "ố",
            "ơ", "ớ", "u", "ư", "ừm", "hự", "hừ", "hừm", "ui", "ối", "ôi", "ây", "au", "ái",
            "ah", "aah", "oh", "ooh", "uh", "um", "umm", "hmm", "hm", "eh", "er", "ow",
            "oww", "ouch", "ugh", "argh", "aw", "ew", "아야"
        )
        private val INTERJECTION_CHARS = setOf(
            '啊', '哎', '唉', '哦', '呃', '哼', '呜', '呀', '哟', '哈', '呵',
            'あ', 'う', 'え', 'お', 'っ', '아', '어', '으', '앗', '흠'
        )
        private val INTERJECTION_PHRASES = setOf(
            "ui da", "ây da", "ối giời ơi", "trời ơi", "ối giời"
        )
        private val LAUGHTER_RE = Regex("^(ha|he|hi|ho|hu){2,}$")
        private val NON_LETTER_RE = Regex("[^\\p{L}\\s]")
        private val WHITESPACE_RE = Regex("\\s+")

        private fun collapseRepeats(token: String): String {
            val out = StringBuilder(token.length)
            var prev = '\u0000'
            for (ch in token) {
                if (ch != prev) out.append(ch)
                prev = ch
            }
            return out.toString()
        }

        /** True when the text is only cries/groans/laughter/filler sounds. */
        fun isMeaningless(text: String?): Boolean {
            if (text.isNullOrBlank()) return false
            val normalized = text.lowercase()
                .replace(NON_LETTER_RE, " ")
                .replace(WHITESPACE_RE, " ")
                .trim()
            if (normalized.isEmpty()) return true
            if (INTERJECTION_PHRASES.contains(normalized)) return true
            val tokens = normalized.split(" ")
            for (token in tokens) {
                val t = collapseRepeats(token)
                if (INTERJECTIONS.contains(t)) continue
                if (LAUGHTER_RE.matches(t)) continue
                var allNoise = t.isNotEmpty()
                for (ch in t) {
                    if (!INTERJECTION_CHARS.contains(ch)) {
                        allNoise = false
                        break
                    }
                }
                if (allNoise) continue
                return false // có ít nhất một từ thật -> câu có nghĩa
            }
            return true
        }

        /** 16 kHz mono 16-bit PCM -> WAV bytes (44-byte RIFF header). */
        fun pcmToWav(pcm: ByteArray, sampleRate: Int = 16000): ByteArray {
            val out = ByteArrayOutputStream(44 + pcm.size)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + pcm.size)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16) // PCM chunk size
            header.putShort(1) // PCM format
            header.putShort(1) // mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2) // byte rate
            header.putShort(2) // block align
            header.putShort(16) // bits per sample
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(pcm.size)
            out.write(header.array())
            out.write(pcm)
            return out.toByteArray()
        }
    }

    /** A translated line, ready to be spoken on its picture. */
    var onLine: ((GroqLine) -> Unit)? = null

    /** Source transcript (Chinese) for the dual-subtitle line. */
    var onSourceText: ((String) -> Unit)? = null

    /** Status line for the main screen. */
    var onStatus: ((String) -> Unit)? = null

    private data class PcmBlock(val startMs: Long, val pcm: ByteArray) {
        // 16 kHz mono 16-bit = 32 bytes per ms.
        val durationMs: Long get() = (pcm.size / 32).toLong()
    }

    private val bufferLock = Any()
    private val pcmBuffer = ArrayDeque<PcmBlock>()

    private val queueLock = Any()
    private val pendingUtterances = ArrayDeque<DubSyncTracker.Utterance>()

    private val history = ArrayDeque<Pair<String, String>>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var workerJob: Job? = null
    private var pollJob: Job? = null
    private val running = AtomicBoolean(false)
    private var cooldownUntilMs = 0L
    private var lastStatusMessage: String? = null

    private fun setStatus(message: String) {
        // StateFlow only emits on change, but avoid spamming the log too.
        if (message == lastStatusMessage) return
        lastStatusMessage = message
        Log.i(TAG, message)
        onStatus?.invoke(message)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (groqKey.isBlank()) {
            setStatus("Chưa nhập key Groq — vào Cài đặt để nhập key (lấy miễn phí tại console.groq.com).")
            running.set(false)
            return
        }
        tracker.onUtteranceComplete = { utterance -> onUtteranceComplete(utterance) }
        workerJob = scope.launch { workerLoop() }
        // The WebAudio tap only forwards non-silent blocks, so during a
        // pause no PCM arrives and the tracker cannot see the silence that
        // ends an utterance. Poll for the lazy close (same 450 ms rule).
        pollJob = scope.launch {
            while (isActive) {
                try {
                    tracker.closeIfSilent(SystemClock.elapsedRealtime())
                } catch (e: Exception) {
                    Log.w(TAG, "closeIfSilent poll failed", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        setStatus("Groq: đang nghe… (dịch bằng ${modelForKey(translateModelKey).label})")
        Log.i(TAG, "Groq dubbing started, model=${modelForKey(translateModelKey).id}")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        tracker.onUtteranceComplete = null
        workerJob?.cancel()
        pollJob?.cancel()
        workerJob = null
        pollJob = null
        synchronized(bufferLock) { pcmBuffer.clear() }
        synchronized(queueLock) { pendingUtterances.clear() }
        history.clear()
        Log.i(TAG, "Groq dubbing stopped")
    }

    /** Seek / resync: drop everything that belongs to the old scene. */
    fun reset() {
        synchronized(bufferLock) { pcmBuffer.clear() }
        synchronized(queueLock) { pendingUtterances.clear() }
        history.clear()
        Log.i(TAG, "Groq dubbing reset after seek")
    }

    /** One captured PCM block (16 kHz mono) with its capture time. */
    fun feedPcm(pcmData: ByteArray, atMs: Long) {
        if (!running.get() || pcmData.isEmpty()) return
        synchronized(bufferLock) {
            pcmBuffer.addLast(PcmBlock(atMs, pcmData))
            val cutoff = atMs - BUFFER_KEEP_MS
            while (pcmBuffer.isNotEmpty()) {
                val first = pcmBuffer.first()
                if (first.startMs + first.durationMs < cutoff) pcmBuffer.removeFirst() else break
            }
        }
    }

    private fun onUtteranceComplete(utterance: DubSyncTracker.Utterance) {
        if (!running.get()) return
        synchronized(queueLock) {
            // Lines arriving faster than the network answers: drop the
            // oldest pending line instead of translating stale speech.
            while (pendingUtterances.size >= MAX_PENDING_UTTERANCES) {
                val dropped = pendingUtterances.removeFirst()
                Log.w(TAG, "Dropping pending utterance (${dropped.durationMs}ms): worker is behind")
            }
            pendingUtterances.addLast(utterance)
        }
    }

    private suspend fun workerLoop() {
        while (scope.isActive) {
            val utterance = synchronized(queueLock) {
                if (pendingUtterances.isEmpty()) null else pendingUtterances.removeFirst()
            }
            if (utterance == null) {
                delay(50)
                continue
            }
            try {
                processUtterance(utterance)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Groq line failed", e)
                setStatus("Groq lỗi: ${e.message}")
            }
        }
    }

    private suspend fun processUtterance(utterance: DubSyncTracker.Utterance) {
        val now = SystemClock.elapsedRealtime()
        if (now < cooldownUntilMs) {
            Log.d(TAG, "In Groq cooldown, skipping utterance (${utterance.durationMs}ms)")
            return
        }
        val wavPcm = sliceUtterancePcm(utterance)
        if (wavPcm == null || wavPcm.size < 32 * 250) {
            Log.d(TAG, "Utterance too short or no audio buffered, skipping")
            return
        }
        val sourceText = withContext(Dispatchers.IO) {
            whisperTranscribe(pcmToWav(wavPcm))
        }.trim()
        if (sourceText.isEmpty()) return
        onSourceText?.invoke(sourceText)
        if (isMeaningless(sourceText)) {
            Log.d(TAG, "Skipping meaningless line: \"$sourceText\"")
            return
        }
        val translated = translateWithFallback(sourceText)
        if (translated == null) return // error already reported in translate
        val vi = translated.trim()
        if (vi.isEmpty() || isMeaningless(vi)) {
            Log.d(TAG, "Skipping empty/meaningless translation for: \"$sourceText\"")
            return
        }
        synchronized(history) {
            history.addLast(sourceText to vi)
            while (history.size > HISTORY_MAX) history.removeFirst()
        }
        onLine?.invoke(
            GroqLine(
                sourceText = sourceText,
                translatedText = vi,
                utteranceStartMs = utterance.startMs,
                utteranceEndMs = utterance.endMs
            )
        )
    }

    /**
     * Slice an utterance's PCM out of the rolling buffer. The WebAudio tap
     * only forwards non-silent blocks, so gaps are filled with silence to
     * keep the timeline continuous for Whisper.
     */
    private fun sliceUtterancePcm(utterance: DubSyncTracker.Utterance): ByteArray? {
        val startMs = (utterance.startMs - UTTERANCE_PAD_MS).coerceAtLeast(0L)
        val endMs = minOf(
            utterance.endMs + UTTERANCE_PAD_MS,
            startMs + MAX_UTTERANCE_MS
        )
        if (endMs <= startMs) return null
        val totalBytes = ((endMs - startMs) * 32).toInt()
        val out = ByteArray(totalBytes) // gaps stay silent (zeros)
        synchronized(bufferLock) {
            for (block in pcmBuffer) {
                val blockStart = block.startMs
                val blockEnd = blockStart + block.durationMs
                val overlapStart = maxOf(blockStart, startMs)
                val overlapEnd = minOf(blockEnd, endMs)
                if (overlapEnd <= overlapStart) continue
                val srcOffset = ((overlapStart - blockStart) * 32).toInt()
                val dstOffset = ((overlapStart - startMs) * 32).toInt()
                val length = ((overlapEnd - overlapStart) * 32).toInt()
                if (srcOffset < 0 || dstOffset < 0) continue
                if (srcOffset + length > block.pcm.size || dstOffset + length > out.size) continue
                System.arraycopy(block.pcm, srcOffset, out, dstOffset, length)
            }
        }
        return out
    }

    /** Groq Whisper speech-to-text. Returns the transcript (any language). */
    private fun whisperTranscribe(wavBytes: ByteArray): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", "utterance.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", WHISPER_MODEL)
            .addFormDataPart("response_format", "json")
            .build()
        val request = Request.Builder()
            .url("$GROQ_API/audio/transcriptions")
            .header("Authorization", "Bearer $groqKey")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GroqApiException(response.code, "Whisper HTTP ${response.code}: ${text.take(200)}")
            }
            return JSONObject(text).optString("text", "")
        }
    }

    /**
     * Translate with fallback. DeepSeek (when selected) falls back to Groq
     * on missing key / wrong key / out of credit / rate limit; Groq models
     * fall back across each other on 404/429 (a model retired from the
     * free tier must not stop the dub). All Groq models rate-limited ->
     * 60 s cooldown. Returns null when every model failed.
     */
    private suspend fun translateWithFallback(srcText: String): String? {
        val selected = modelForKey(translateModelKey)
        if (selected.provider == "deepseek") {
            try {
                return translateWith(selected, srcText)
            } catch (e: DeepSeekUnavailableException) {
                setStatus("DeepSeek ${e.reason} — dịch tạm bằng Groq.")
                Log.w(TAG, "DeepSeek unavailable (${e.reason}), falling back to Groq")
            } catch (e: GroqApiException) {
                if (e.code == 429) {
                    setStatus("DeepSeek bị giới hạn lượt (429) — dịch tạm bằng Groq.")
                } else {
                    setStatus("DeepSeek lỗi ${e.code} — dịch tạm bằng Groq.")
                }
                Log.w(TAG, "DeepSeek error ${e.code}, falling back to Groq")
            }
        }
        // Groq models, selected first, then the rest in fallback order.
        val order = (listOf(selected.key) + GROQ_FALLBACK_ORDER).distinct()
            .mapNotNull { key -> TRANSLATE_MODELS.find { it.key == key && it.provider == "groq" } }
        var lastError: Exception? = null
        var sawRateLimit = false
        for (model in order) {
            try {
                val result = translateWith(model, srcText)
                if (model.key != selected.key) {
                    setStatus("Đang dịch bằng ${model.label} (model đã chọn không dùng được).")
                }
                return result
            } catch (e: GroqApiException) {
                lastError = e
                if (e.code == 404 || e.code == 429) {
                    if (e.code == 429) sawRateLimit = true
                    Log.w(TAG, "Translate model ${model.id} HTTP ${e.code}, trying next")
                    continue
                }
                setStatus("Groq dịch lỗi ${e.code}.")
                return null
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Translate model ${model.id} failed: ${e.message}")
            }
        }
        if (sawRateLimit) {
            cooldownUntilMs = SystemClock.elapsedRealtime() + GROQ_COOLDOWN_MS
            setStatus("Groq báo hết lượt dịch (429) — nghỉ 60 giây rồi thử lại.")
        } else {
            setStatus("Groq dịch lỗi: ${lastError?.message}")
        }
        return null
    }

    /** One translate call against one model. Throws on any failure. */
    private suspend fun translateWith(model: TranslateModel, srcText: String): String {
        val isDeepSeek = model.provider == "deepseek"
        val apiKey = if (isDeepSeek) deepseekKey else groqKey
        if (apiKey.isBlank()) {
            if (isDeepSeek) throw DeepSeekUnavailableException("chưa nhập key")
            throw GroqApiException(0, "Chưa nhập key Groq")
        }
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        synchronized(history) {
            for ((src, vi) in history) {
                messages.put(JSONObject().put("role", "user").put("content", src))
                messages.put(JSONObject().put("role", "assistant").put("content", vi))
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", srcText))
        val bodyJson = JSONObject()
            .put("model", model.id)
            .put("temperature", 0.3)
            .put("max_tokens", 300)
            .put("messages", messages)
        // GPT-OSS là model suy luận: để reasoning thấp cho dịch nhanh
        // (Qwen/DeepSeek không cần).
        if (model.id.contains("gpt-oss")) bodyJson.put("reasoning_effort", "low")
        val baseUrl = if (isDeepSeek) DEEPSEEK_API else GROQ_API
        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (isDeepSeek && (response.code == 401 || response.code == 402)) {
                        val reason = if (response.code == 401) "key không đúng" else "hết tiền"
                        throw DeepSeekUnavailableException(reason)
                    }
                    throw GroqApiException(response.code, "HTTP ${response.code}: ${text.take(200)}")
                }
                val json = JSONObject(text)
                val choices = json.optJSONArray("choices")
                val first = choices?.optJSONObject(0)
                val message = first?.optJSONObject("message")
                message?.optString("content", "").orEmpty()
            }
        }
    }

    /** HTTP failure from a Groq/DeepSeek call (code 0 = no key / no network). */
    class GroqApiException(val code: Int, message: String) : Exception(message)

    /** DeepSeek cannot serve right now (no key / wrong key / out of credit). */
    class DeepSeekUnavailableException(val reason: String) : Exception(reason)
}
