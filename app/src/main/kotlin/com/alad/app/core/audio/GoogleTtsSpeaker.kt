package com.alad.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * FIX24: Vietnamese dub voice that does not depend on the phone's TTS
 * voices at all. Each translated line is fetched as audio from the Google
 * Translate TTS endpoint (the same voice the Groq userscript uses — free,
 * no key) and played back with MediaPlayer, timed against the delayed
 * picture exactly like the TVB director times Android TTS: wait until
 * [speakAtMs], drop the line when its [deadlineMs] has passed, and speed
 * the audio up (never past 1.30x) so a long line fits its picture.
 *
 * The service uses this speaker for Groq lines when the phone has no
 * Vietnamese TTS voice (TvbVoiceDirector.voiceMissing). That is the case
 * on phones whose TTS engine never had a vi voice installed: before this
 * fix the dub voice there was either gibberish (pre-FIX23) or silent
 * (FIX23). With this speaker the dub simply works — the only requirement
 * is internet, which the Groq pipeline already needs.
 *
 * One MediaPlayer instance is reused for every line. Lines are fetched
 * eagerly on arrival (in parallel with the line currently playing) so
 * playback never waits on the network unless the line arrived late.
 */
class GoogleTtsSpeaker(
    context: Context,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    initialVolume: Float = 1.0f,
    /** Test override: returns MP3 bytes for one text part. */
    private val fetcher: (suspend (String) -> ByteArray)? = null
) {
    companion object {
        private const val TAG = "GoogleTtsSpeaker"
        private const val TTS_URL = "https://translate.google.com/translate_tts"
        /** Google TTS rejects texts longer than ~200 characters. */
        private const val TTS_MAX_CHARS = 180
        /** Queue bound; the oldest queued line is dropped when full. */
        private const val QUEUE_CAPACITY = 8

        /** Fit-rate clamps, mirroring TvbVoiceDirector: never slowed below
         * this to stretch a short line, never pushed above this to squeeze
         * a long one. */
        private const val MIN_FIT_RATE = 0.85f
        private const val MAX_FIT_RATE = 1.30f

        /**
         * Splits a long line into <=180-character parts, cutting at
         * punctuation so words are not split mid-word. Same rule as the
         * Groq userscript's splitTextForTts.
         */
        fun splitTextForTts(text: String): List<String> {
            val out = mutableListOf<String>()
            var rest = text.trim()
            val seps = arrayOf(". ", "。", "！", "？", ", ", "、", "; ", " ")
            while (rest.length > TTS_MAX_CHARS) {
                var cut = -1
                for (sep in seps) {
                    val idx = rest.lastIndexOf(sep, TTS_MAX_CHARS)
                    if (idx > 20) {
                        cut = idx + sep.length
                        break
                    }
                }
                if (cut < 0) cut = TTS_MAX_CHARS
                out.add(rest.substring(0, cut).trim())
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) out.add(rest)
            return out
        }

        /**
         * Playback rate that fits [audioDurationMs] of speech into
         * [sourceDurationMs] of picture, combined with the user's speed
         * setting. Returns the user speed when no source duration is known.
         */
        fun fitRate(audioDurationMs: Long, sourceDurationMs: Long, userSpeed: Float): Float {
            if (sourceDurationMs <= 0L || audioDurationMs <= 0L) {
                return userSpeed.coerceIn(0.5f, 2.0f)
            }
            val fit = (audioDurationMs.toFloat() / sourceDurationMs)
                .coerceIn(MIN_FIT_RATE, MAX_FIT_RATE)
            return (fit * userSpeed).coerceIn(0.5f, 2.0f)
        }
    }

    /** Fired when a line actually starts playing (subtitle sync). */
    var onSegmentStarted: ((String) -> Unit)? = null

    /** Fired when a queued line cannot be spoken (fetch failed or stale). */
    var onLineFailed: ((String) -> Unit)? = null

    /** Fired whenever dropped/overrun stats change. */
    var onStatsChanged: (() -> Unit)? = null

    /** Fired after 3 consecutive audio-fetch failures (network/Google down). */
    var onTtsUnavailable: (() -> Unit)? = null

    /** Sync diagnostics, read by the service/UI (same meaning as the
     * director's). */
    @Volatile var droppedStaleCount: Int = 0
        private set
    @Volatile var lastOverrunMs: Long = 0L
        private set
    @Volatile var lastAudioDurationMs: Long = 0L
        private set

    private val cacheDir: File =
        File(context.applicationContext.cacheDir, "gtts").apply { mkdirs() }
    private val createdFiles = ConcurrentHashMap.newKeySet<File>()

    private data class QueuedLine(
        val text: String,
        val speakAtMs: Long,
        val deadlineMs: Long,
        val sourceDurationMs: Long,
        val audioFile: Deferred<File?>
    )

    private val queue = Channel<QueuedLine>(
        capacity = QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val fetchScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
    private var workerJob: Job? = null
    private var player: MediaPlayer? = null
    /** Serializes MediaPlayer use across a worker restart (seek). */
    private val playMutex = kotlinx.coroutines.sync.Mutex()

    @Volatile private var volume: Float = initialVolume.coerceIn(0f, 1f)
    @Volatile private var userSpeed: Float = 1.0f
    @Volatile private var stopped = false
    @Volatile private var consecutiveFetchFailures = 0
    @Volatile private var ttsUnavailableReported = false

    fun start() {
        if (workerJob != null) return
        stopped = false
        workerJob = scope.launch { workerLoop() }
    }

    /**
     * Queues a translated line for timed playback. The audio is fetched
     * eagerly in the background; the worker plays it when the delayed
     * video reaches [speakAtMs]. Returns false when the speaker is stopped
     * or the text is blank.
     */
    fun speak(
        text: String,
        speakAtMs: Long,
        deadlineMs: Long,
        sourceDurationMs: Long
    ): Boolean {
        if (stopped || text.isBlank()) return false
        val deferred = fetchScope.async(Dispatchers.IO) { fetchLineAudio(text) }
        val item = QueuedLine(text, speakAtMs, deadlineMs, sourceDurationMs, deferred)
        return queue.trySend(item).isSuccess
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        try {
            player?.setVolume(volume, volume)
        } catch (e: Exception) {
            Log.w(TAG, "Could not set MediaPlayer volume", e)
        }
    }

    fun setUserSpeed(speed: Float) {
        userSpeed = speed.coerceIn(0.5f, 2.0f)
    }

    private suspend fun workerLoop() {
        for (item in queue) {
            try {
                val file = item.audioFile.await()
                if (file == null) {
                    onLineFailed?.invoke(item.text)
                    continue
                }
                // Wait until the delayed video reaches this line.
                val waitMs = item.speakAtMs - SystemClock.elapsedRealtime()
                if (waitMs > 0) delay(waitMs)
                // Drop lines whose picture has already passed, so one late
                // line cannot push every following line late.
                if (item.deadlineMs > 0L && SystemClock.elapsedRealtime() > item.deadlineMs) {
                    droppedStaleCount++
                    onStatsChanged?.invoke()
                    onLineFailed?.invoke(item.text)
                    deleteFile(file)
                    continue
                }
                playMutex.lock()
                try {
                    playFile(item, file)
                } finally {
                    playMutex.unlock()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Google TTS line failed: ${e.message}")
                onLineFailed?.invoke(item.text)
            }
        }
    }

    private suspend fun playFile(item: QueuedLine, file: File) {
        val mp = getPlayer()
        withContext(Dispatchers.IO) {
            mp.reset()
            mp.setDataSource(file.absolutePath)
            mp.prepare()
        }
        val durationMs = try {
            mp.duration.coerceAtLeast(0)
        } catch (e: Exception) {
            0
        }
        lastAudioDurationMs = durationMs.toLong()
        val rate = fitRate(durationMs.toLong(), item.sourceDurationMs, userSpeed)
        lastOverrunMs = if (item.sourceDurationMs > 0L && durationMs > 0) {
            ((durationMs / rate).toLong() - item.sourceDurationMs).coerceAtLeast(0L)
        } else {
            0L
        }
        onStatsChanged?.invoke()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val params: PlaybackParams = mp.playbackParams.setSpeed(rate)
                mp.playbackParams = params
            } catch (e: Exception) {
                Log.w(TAG, "Playback speed unavailable on this device", e)
            }
        }
        try {
            mp.setVolume(volume, volume)
        } catch (e: Exception) {
            Log.w(TAG, "Could not set MediaPlayer volume", e)
        }
        mp.start()
        try {
            onSegmentStarted?.invoke(item.text)
        } catch (e: Exception) {
            Log.w(TAG, "onSegmentStarted failed", e)
        }
        // Poll for completion: cancellable, and a seek-stop (reset) ends
        // the wait at once instead of leaving the worker suspended.
        try {
            while (isPlaying(mp)) delay(100)
        } finally {
            deleteFile(file)
        }
    }

    private fun isPlaying(mp: MediaPlayer): Boolean {
        return try {
            mp.isPlaying
        } catch (e: Exception) {
            false
        }
    }

    private fun getPlayer(): MediaPlayer {
        player?.let { return it }
        val mp = MediaPlayer()
        // Assistant speech, like the Gemini audio track: the dub voice is
        // NOT on STREAM_MUSIC, so the app's ducking of the original media
        // volume does not quiet the dub.
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        player = mp
        return mp
    }

    /**
     * Fetches every part of the line and concatenates the MP3 bytes into
     * one cache file (MP3 frames are independent, so concatenation plays
     * through). Returns null when any part fails.
     */
    private suspend fun fetchLineAudio(text: String): File? {
        val out = File(cacheDir, "gtts-${UUID.randomUUID()}.mp3")
        createdFiles.add(out)
        try {
            FileOutputStream(out).use { fos ->
                for (part in splitTextForTts(text)) {
                    val bytes = fetchPart(part) ?: run {
                        out.delete()
                        createdFiles.remove(out)
                        return null
                    }
                    fos.write(bytes)
                }
            }
            consecutiveFetchFailures = 0
            ttsUnavailableReported = false
            return out
        } catch (e: CancellationException) {
            out.delete()
            createdFiles.remove(out)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Google TTS fetch failed: ${e.message}")
            out.delete()
            createdFiles.remove(out)
            return null
        }
    }

    private suspend fun fetchPart(text: String): ByteArray? = withContext(Dispatchers.IO) {
        fetcher?.let { fetch ->
            try {
                return@withContext fetch(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Google TTS fetch failed: ${e.message}")
                noteFetchFailure()
                return@withContext null
            }
        }
        val url = TTS_URL + "?ie=UTF-8&tl=vi&client=tw-ob&q=" +
            URLEncoder.encode(text, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Google TTS HTTP ${response.code}")
                    noteFetchFailure()
                    return@withContext null
                }
                response.body?.bytes()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Google TTS request failed: ${e.message}")
            noteFetchFailure()
            null
        }
    }

    private fun noteFetchFailure() {
        consecutiveFetchFailures++
        if (consecutiveFetchFailures >= 3 && !ttsUnavailableReported) {
            ttsUnavailableReported = true
            try {
                onTtsUnavailable?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "onTtsUnavailable failed", e)
            }
        }
    }

    private fun deleteFile(file: File) {
        try {
            file.delete()
        } catch (e: Exception) {
            // Best effort; the cache dir is cleaned on stop/reset.
        }
        createdFiles.remove(file)
    }

    private fun deleteAllFiles() {
        val files = createdFiles.toList()
        createdFiles.clear()
        files.forEach { file ->
            try {
                file.delete()
            } catch (e: Exception) {
                // Best effort.
            }
        }
    }

    /** Seek: drop the queue and the line currently playing, then restart
     * the worker so following lines play against the new picture. */
    fun reset() {
        workerJob?.cancel()
        fetchScope.coroutineContext.cancelChildren()
        var item = queue.tryReceive().getOrNull()
        while (item != null) {
            item.audioFile.cancel()
            item = queue.tryReceive().getOrNull()
        }
        try {
            player?.stop()
        } catch (e: Exception) {
            // Already idle/stopped.
        }
        try {
            player?.reset()
        } catch (e: Exception) {
            // Already idle.
        }
        deleteAllFiles()
        if (!stopped) {
            workerJob = scope.launch { workerLoop() }
        }
    }

    fun stop() {
        stopped = true
        workerJob?.cancel()
        workerJob = null
        queue.close()
        fetchScope.coroutineContext.cancelChildren()
        try {
            player?.stop()
        } catch (e: Exception) {
            // Already idle/stopped.
        }
        try {
            player?.release()
        } catch (e: Exception) {
            // Already released.
        }
        player = null
        deleteAllFiles()
        onSegmentStarted = null
        onLineFailed = null
        onStatsChanged = null
        onTtsUnavailable = null
    }
}
