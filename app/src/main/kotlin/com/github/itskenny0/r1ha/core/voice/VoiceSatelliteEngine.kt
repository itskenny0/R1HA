package com.github.itskenny0.r1ha.core.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Voice satellite engine: drives the HA assist pipeline end-to-end from a tap.
 *
 * Lifecycle, conceptually:
 *   start() → opens the pipeline → captures the binary-handler byte from the
 *   `run-start` event → starts AudioRecord at 16 kHz PCM-16 mono → streams
 *   ~20 ms chunks at HA via the WS until stop() is called → signals
 *   end-of-utterance with a single-byte frame → continues to receive events
 *   (stt-end, intent-end, tts-end, run-end) → on tts-end plays the TTS audio
 *   via MediaPlayer with the configured access token as a bearer header.
 *
 * The class is single-shot for a given run: start() can be called again after
 * a previous run completes (or fails), but not while in-flight; the caller
 * inspects [state] to know when it's safe to re-fire.
 *
 * Wake-word is intentionally not implemented; on-device wake detection needs
 * a model artifact (Porcupine, OpenWakeWord) and a separate audio path that
 * runs continuously. This satellite is push-to-talk only. A future cycle can
 * layer a wake-word frontend on top of the same pipeline-run plumbing.
 */
class VoiceSatelliteEngine(
    private val haRepository: HaRepository,
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
) {

    /** State machine the UI binds to. The transitions match the pipeline event
     *  order (run-start → stt-end → tts-end → run-end). */
    sealed class State {
        data object Idle : State()
        data object Connecting : State()
        data class Listening(val partial: String? = null) : State()
        data class Thinking(val sttText: String) : State()
        data class Speaking(val sttText: String, val responseText: String, val ttsUrl: String) : State()
        data class Done(val sttText: String, val responseText: String) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pipeline: HaRepository.PipelineRun? = null
    private var audioRecord: AudioRecord? = null
    private var recorderJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var handlerByte: Byte = 0
    private var sttText: String = ""
    private var responseText: String = ""

    /** Open the pipeline and start recording. Idempotent guard: re-firing while
     *  not Idle / Done / Error is a no-op so the UI's debounce logic can stay
     *  simple. */
    fun start(pipelineId: String?, conversationId: String?, appContext: Context) {
        when (_state.value) {
            is State.Idle, is State.Done, is State.Error -> Unit
            else -> return
        }
        _state.value = State.Connecting
        sttText = ""
        responseText = ""
        // Mic permission must already be granted; the screen handles that.
        // We start the AudioRecord eagerly so the user sees the listening
        // animation the moment HA confirms the run. Frames buffer until we
        // have a handler byte; the recorder loop drops audio until it's set.
        startRecorder(appContext)
        scope.launch {
            val run = haRepository.startAssistPipeline(
                pipelineId = pipelineId,
                conversationId = conversationId,
                onEvent = ::onPipelineEvent,
            ).getOrElse { t ->
                _state.value = State.Error(t.message ?: "Failed to open pipeline")
                R1Log.w("VoiceSat", "pipeline open failed: ${t.message}")
                // If the pipeline never opens, the recorder we just started has
                // nothing to feed; tear it down so we don't hold the mic open
                // while the screen shows the error state.
                recorderJob?.cancel()
                recorderJob = null
                runCatching { audioRecord?.stop() }
                runCatching { audioRecord?.release() }
                audioRecord = null
                return@launch
            }
            pipeline = run
            // run-start arrives asynchronously; we set Listening once it does
            // (via onPipelineEvent). Until then we stay in Connecting so the
            // UI shows a spinner.
        }
    }

    /** Signal end-of-utterance to HA. The pipeline keeps emitting events
     *  (intent-end, tts-end, run-end) after we stop pushing audio. */
    fun stop() {
        recorderJob?.cancel()
        recorderJob = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { pipeline?.finishAudio(handlerByte) }
        // State stays at whatever the latest event set it to (Listening at
        // worst); the Thinking → Speaking → Done transitions arrive through
        // the event stream.
    }

    /** Tear down the engine entirely — call from ViewModel.onCleared. Cancels
     *  any in-flight subscription, stops playback, releases the recorder. */
    fun release() {
        recorderJob?.cancel()
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        scope.launch { pipeline?.cancel() }
    }

    private fun startRecorder(appContext: Context) {
        val sampleRate = 16000
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        // Aim for ~20 ms chunks (320 samples = 640 bytes) — HA's pipeline copes
        // with anything reasonable, but small frames keep latency low. The OS
        // minimum buffer is usually larger than that on most devices, so we
        // bump up to whichever is bigger to satisfy the recorder while still
        // reading chunked-out 640 byte slices.
        val recordBuf = maxOf(minBuf, 4096)
        val frameBytes = 640
        @Suppress("MissingPermission")
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channel,
            encoding,
            recordBuf,
        )
        audioRecord = rec
        recorderJob = scope.launch {
            runCatching { rec.startRecording() }.onFailure { t ->
                _state.value = State.Error("Couldn't start mic: ${t.message}")
                // startRecording failed but the AudioRecord still holds the
                // native mic resource; release it so a failed start doesn't
                // leave the microphone claimed until the next GC.
                runCatching { rec.release() }
                if (audioRecord === rec) audioRecord = null
                return@launch
            }
            val buf = ByteArray(frameBytes)
            while (isActiveJob()) {
                val read = rec.read(buf, 0, buf.size)
                if (read <= 0) {
                    // -1 = ERROR, -2 = BAD_VALUE, -3 = INVALID_OPERATION, -6 = DEAD_OBJECT.
                    // Any of these means the mic is gone; surface and bail.
                    R1Log.w("VoiceSat", "audio read returned $read; ending stream")
                    break
                }
                val p = pipeline
                if (p != null && handlerByte != 0.toByte()) {
                    // The if-check copy-with-trim happens because the last read
                    // can return less than frameBytes; sending the unused tail
                    // would push silence at HA.
                    val chunk = if (read == buf.size) buf else buf.copyOf(read)
                    if (!p.sendAudio(handlerByte, chunk)) {
                        // Socket dropped mid-stream; HA's pipeline will time out
                        // on its end. Surface and stop pushing.
                        _state.value = State.Error("Lost connection to HA mid-stream")
                        break
                    }
                }
            }
        }
    }

    /** Coroutine-friendly poll: the recorder loop wants to know if it should
     *  still be running. Replaces a separate AtomicBoolean. */
    private fun isActiveJob(): Boolean = recorderJob?.isActive == true

    private fun onPipelineEvent(event: JsonObject) {
        val type = (event["type"] as? JsonPrimitive)?.content ?: return
        val data = event["data"] as? JsonObject
        when (type) {
            "run-start" -> {
                // data.runner_data.stt_binary_handler_id is the byte we prefix
                // each audio frame with. HA sends this exactly once.
                val runnerData = data?.get("runner_data") as? JsonObject
                val rawByte = (runnerData?.get("stt_binary_handler_id") as? JsonPrimitive)
                    ?.content?.toIntOrNull()
                if (rawByte != null) {
                    handlerByte = rawByte.toByte()
                    _state.value = State.Listening()
                    R1Log.i("VoiceSat", "pipeline run-start handlerByte=$rawByte")
                } else {
                    R1Log.w("VoiceSat", "run-start without handler id, can't stream audio")
                }
            }
            "stt-end" -> {
                val out = data?.get("stt_output") as? JsonObject
                val text = (out?.get("text") as? JsonPrimitive)?.content.orEmpty()
                sttText = text
                _state.value = State.Thinking(text)
                // HA has the transcript; further mic audio is wasted bandwidth + battery.
                // Tear down the recorder eagerly. The pipeline subscription stays open so
                // the intent + TTS events still reach us.
                recorderJob?.cancel()
                recorderJob = null
                runCatching { audioRecord?.stop() }
                runCatching { audioRecord?.release() }
                audioRecord = null
            }
            "intent-end" -> {
                val intentOutput = data?.get("intent_output") as? JsonObject
                val response = intentOutput?.get("response") as? JsonObject
                val speech = response?.get("speech") as? JsonObject
                val plain = speech?.get("plain") as? JsonObject
                val text = (plain?.get("speech") as? JsonPrimitive)?.content.orEmpty()
                responseText = text
            }
            "tts-end" -> {
                val ttsOutput = data?.get("tts_output") as? JsonObject
                val rawUrl = (ttsOutput?.get("url") as? JsonPrimitive)?.content
                if (rawUrl != null) {
                    scope.launch { playTtsUrl(rawUrl) }
                }
            }
            "run-end" -> {
                _state.value = State.Done(sttText, responseText)
                scope.launch { pipeline?.cancel(); pipeline = null }
            }
            "error" -> {
                val msg = (data?.get("message") as? JsonPrimitive)?.content
                    ?: (data?.get("code") as? JsonPrimitive)?.content
                    ?: "Pipeline error"
                _state.value = State.Error(msg)
                R1Log.w("VoiceSat", "pipeline error: $msg")
                // Same recorder + pipeline teardown as run-end; whatever state
                // we were in (Listening / Thinking / Speaking), HA isn't going
                // to continue, so we shouldn't hold the mic open.
                recorderJob?.cancel()
                recorderJob = null
                runCatching { audioRecord?.stop() }
                runCatching { audioRecord?.release() }
                audioRecord = null
                scope.launch { pipeline?.cancel(); pipeline = null }
            }
        }
    }

    private suspend fun playTtsUrl(rawUrl: String) {
        val s = settings.settings.first()
        val server = s.server?.url
        if (server == null) {
            R1Log.w("VoiceSat", "no server URL; can't play TTS")
            return
        }
        val token = tokens.load()?.accessToken
        if (token == null) {
            R1Log.w("VoiceSat", "no access token; can't play TTS")
            return
        }
        val absolute = if (rawUrl.startsWith("http")) rawUrl else server.trimEnd('/') + rawUrl
        _state.value = State.Speaking(sttText, responseText, absolute)
        // MediaPlayer's HEAD request to the TTS endpoint accepts a bearer
        // token via the headers map. The endpoint redirects to a one-shot
        // signed URL for the actual audio, so we don't need to add the token
        // to the audio fetch — but adding it on the initial request doesn't
        // hurt the redirect path either.
        runCatching {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                val context = appContextRef
                if (context != null) {
                    setDataSource(
                        context,
                        Uri.parse(absolute),
                        mapOf("Authorization" to "Bearer $token"),
                    )
                } else {
                    setDataSource(absolute)
                }
                setOnPreparedListener { it.start() }
                setOnCompletionListener {
                    runCatching { it.release() }
                    if (mediaPlayer === it) mediaPlayer = null
                }
                setOnErrorListener { mp, what, extra ->
                    R1Log.w("VoiceSat", "MediaPlayer error what=$what extra=$extra")
                    runCatching { mp.release() }
                    if (mediaPlayer === mp) mediaPlayer = null
                    true
                }
                prepareAsync()
            }
        }.onFailure { t ->
            R1Log.w("VoiceSat", "TTS playback failed: ${t.message}")
        }
    }

    /** Set by the screen via [withAppContext] so MediaPlayer can attach
     *  request headers for the TTS fetch. We keep it nullable so unit tests
     *  can bypass the context dependency. */
    @Volatile
    private var appContextRef: Context? = null

    fun attachContext(context: Context) {
        appContextRef = context.applicationContext
    }
}
