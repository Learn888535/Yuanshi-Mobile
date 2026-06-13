package com.yuanshi.avatar.service

import com.yuanshi.avatar.engine.DigitalHumanEngine
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.AudioManager
import android.util.Base64
import android.view.KeyEvent
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlin.math.PI
import kotlin.math.sin

enum class RealtimeState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    WAKE_WORD
}

/** 录音交互模式 */
enum class ListeningMode {
    /** 按住说话：按住按钮录音，松开发送 */
    PUSH_TO_TALK,
    /** 持续监听：点击开关进入/退出监听模式，说话自动发送并继续监听 */
    TOGGLE,
    /** 唤醒词模式：说设定唤醒词（如 "原宝"）激活，自动录音并交互，完成后继续监听 */
    WAKE_WORD
}

data class RealtimeConfig(
    val backendBaseUrl: String = "",
    val apiKey: String = "",
    val baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    val ttsWsUrl: String = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
    val asrModel: String = "qwen3-asr-flash",
    val asrLanguage: String = "zh",
    val asrEnableItn: Boolean = false,
    val asrEnablePunctuation: Boolean = true,
    val chatModel: String = "qwen-max",
    val chatTemperature: Double = 0.7,
    val ttsModel: String = "qwen3-tts-flash-realtime",
    val ttsVoice: String = "Cherry",
    val ttsSampleRate: Int = 16000,
    val ttsSpeed: Double = 1.0,
    val ttsVolume: Double = 50.0,
    val ttsPitch: Double = 1.0,
    val systemPrompt: String = "你是一个自然、简洁、友好的中文数字人助手，请用口语化中文简短回答。",
    val useWebSocket: Boolean = false,  // false=HTTP模式, true=WebSocket模式
    val deviceId: String = ""  // 持久化设备标识（用于跨轮次对话上下文）
) {
    fun isBackendConfigured(): Boolean = backendBaseUrl.isNotBlank()
    fun isDirectConfigured(): Boolean = apiKey.isNotBlank()
}

interface RealtimeAsrClient {
    fun transcribe(pcm16kMono: ByteArray): String
}

interface RealtimeLlmClient {
    fun reply(userText: String): String
}

interface RealtimeTtsClient {
    fun synthesizeToPcm16kMono(text: String): ByteArray
    fun cancel()
}

class RealtimeSessionManager(
    private val config: RealtimeConfig,
    private val asrClient: RealtimeAsrClient = createAsrClient(config),
    private val llmClient: RealtimeLlmClient = createLlmClient(config),
    private val ttsClient: RealtimeTtsClient = createTtsClient(config),
    private val callback: Callback,
    private val deviceController: DeviceController? = null  // WebSocket 模式需要
) {
    interface Callback {
        fun onStateChanged(state: RealtimeState)
        fun onInfo(message: String)
        fun onError(message: String)
    }

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile
    private var state: RealtimeState = RealtimeState.IDLE

    /** 追踪当前活动的 WebSocket 客户端，用于 interrupt 时断开 */
    @Volatile
    private var currentWsClient: VoiceWebSocketClient? = null

    /** 流式会话 WebSocket 客户端（录音时预连接，用于边录边发） */
    @Volatile
    private var streamingWsClient: VoiceWebSocketClient? = null

    /**
     * 会话完成回调（仅在 TOGGLE 模式下使用）。
     * startSession() 结束后调用，用于自动启动下一轮录音。
     */
    var onSessionComplete: (() -> Unit)? = null

    fun getState(): RealtimeState = state

    fun startSession(pcmData: ByteArray, engine: DigitalHumanEngine?) {
        if (state != RealtimeState.IDLE) {
            callback.onInfo("realtime session is busy")
            return
        }
        ioExecutor.execute {
            if (engine == null || !engine.isReady) {
                callback.onError("digital human engine is not ready")
                return@execute
            }
            try {
                val sessionStartMs = System.currentTimeMillis()
                setState(RealtimeState.THINKING)
                if (config.isBackendConfigured()) {
                    if (config.useWebSocket) {
                        // === WebSocket 模式：双向通信 + 工具调用 ===
                        callback.onInfo("ws mode: connecting...")
                        setState(RealtimeState.SPEAKING)
                        val wsClient = VoiceWebSocketClient(
                            config = config,
                            engine = engine,
                            deviceController = deviceController,
                            callback = callback
                        )
                        // 保存引用以便 interrupt() 能断开连接
                        currentWsClient = wsClient
                        if (!wsClient.connect()) {
                            currentWsClient = null
                            throw IllegalStateException("WebSocket connection failed")
                        }
                        wsClient.sendAudio(pcmData)
                        wsClient.sendAudioEnd()
                        wsClient.waitForSession()
                        currentWsClient = null
                        return@execute
                    } else {
                        // === HTTP 模式：现有流式逻辑 ===
                        callback.onInfo("backend voice interaction stream start")
                        callback.onInfo("upload pcm size: ${pcmData.size} bytes")
                        setState(RealtimeState.SPEAKING)
                        BackendVoiceInteractionClient(config, callback).interactStream(pcmData, engine)
                        return@execute
                    }
                }
                callback.onInfo("asr start")
                val asrStartMs = System.currentTimeMillis()
                val userText = asrClient.transcribe(pcmData)
                callback.onInfo("asr elapsed: ${System.currentTimeMillis() - asrStartMs} ms")
                callback.onInfo("asr result: $userText")

                if (userText.isBlank()) {
                    callback.onError("asr result is empty")
                    return@execute
                }

                callback.onInfo("llm start")
                val llmStartMs = System.currentTimeMillis()
                var replyText = ""
                var llmRetries = 0
                val maxLlmRetries = 1
                while (llmRetries <= maxLlmRetries) {
                    try {
                        replyText = llmClient.reply(userText)
                        break  // 成功则跳出重试循环
                    } catch (e: Exception) {
                        llmRetries++
                        if (llmRetries > maxLlmRetries) {
                            throw e  // 重试耗尽，向上传播
                        }
                        callback.onInfo("llm retry #$llmRetries after: ${e.message?.take(50)}")
                    }
                }
                callback.onInfo("llm elapsed: ${System.currentTimeMillis() - llmStartMs} ms")
                callback.onInfo("llm result: $replyText")
                if (replyText.isBlank()) {
                    callback.onError("llm result is empty")
                    return@execute
                }

                callback.onInfo("tts start")
                val ttsStartMs = System.currentTimeMillis()
                val replyPcm = ttsClient.synthesizeToPcm16kMono(replyText)
                callback.onInfo("tts elapsed: ${System.currentTimeMillis() - ttsStartMs} ms")
                if (replyPcm.isEmpty()) {
                    callback.onError("tts result is empty")
                    return@execute
                }

                callback.onInfo("audio bytes: ${replyPcm.size}, play estimate: ${estimatePlaybackMs(replyPcm.size)} ms")
                setState(RealtimeState.SPEAKING)
                val playbackStartMs = System.currentTimeMillis()
                // 如果是视频数字人引擎，传入回复文字用于后端生成口型同步视频
                if (engine is com.yuanshi.avatar.engine.VideoAvatarEngineImpl) {
                    engine.pendingReplyText = replyText
                }
                engine.startPush()
                var cursor = 0
                val frameSize = 3200
                while (cursor < replyPcm.size && state == RealtimeState.SPEAKING) {
                    val end = minOf(cursor + frameSize, replyPcm.size)
                    engine.pushPcm(replyPcm.copyOfRange(cursor, end))
                    cursor = end
                }
                engine.stopPush()
                callback.onInfo("playback elapsed: ${System.currentTimeMillis() - playbackStartMs} ms")
                callback.onInfo("total elapsed: ${System.currentTimeMillis() - sessionStartMs} ms")
                callback.onInfo("speak finished")
            } catch (e: Exception) {
                callback.onError("realtime error: ${e.message ?: "unknown"}")
            } finally {
                setState(RealtimeState.IDLE)
                // TOGGLE 模式下通知外部启动下一轮录音
                onSessionComplete?.invoke()
            }
        }
    }

    fun interrupt(engine: DigitalHumanEngine?) {
        callback.onInfo("interrupt realtime session")
        // 1. 立即停止本地 TTS（非流式模式）
        ttsClient.cancel()
        // 2. 立即停止引擎音频/视频播放
        //    注意：不调用 engine.stopPush() —— stopPush 会触发 VideoAvatarEngine
        //    向后端发送 speak 请求生成视频。interrupt 的目的是停止当前播放，
        //    不是触发新一轮视频生成。stopPush 只能由拥有 push 生命周期的代码调用
        //   （StreamingPusher.stop()、或直接调用 pushPcm 后的业务代码）。
        engine?.stopAudio()
        // 3. 断开所有 WebSocket 连接
        //    注意：不再用 state 做门禁判断，因为 streaming 录音期间 state 仍为 IDLE，
        //    如果此时用户按停止，条件判断会跳过 engine.stopAudio() 导致停止无效。
        //    参考 V1.3 实现：interrupt 始终执行所有停止操作。
        currentWsClient?.disconnect()
        currentWsClient = null
        streamingWsClient?.disconnect()
        streamingWsClient = null
        setState(RealtimeState.IDLE)
    }

    fun release() {
        ttsClient.cancel()
        ioExecutor.shutdownNow()
        streamingWsClient?.disconnect()
        streamingWsClient = null
        setState(RealtimeState.IDLE)
    }

    // ========================================================================
    // 流式会话管理（边录边发 WebSocket 模式）
    // ========================================================================

    /**
     * 预连接 WebSocket 用于流式录音。
     * 在录音开始前调用，边录边发 PCM 块。
     * 录音结束后调用 [finishStreamingSession] 发送 audio_end 并等待结果。
     *
     * @return true 连接成功，false 连接失败
     */
    fun beginStreamingSession(engine: DigitalHumanEngine?): Boolean {
        if (state != RealtimeState.IDLE) {
            callback.onInfo("streaming: session busy")
            return false
        }
        if (engine == null || !engine.isReady) {
            callback.onError("streaming: digital human engine is not ready")
            return false
        }
        val wsClient = VoiceWebSocketClient(
            config = config,
            engine = engine,
            deviceController = deviceController,
            callback = callback
        )
        if (!wsClient.connect()) {
            callback.onError("streaming: ws connect failed")
            return false
        }
        streamingWsClient = wsClient
        callback.onInfo("streaming: ws connected, ready to send chunks")
        return true
    }

    /**
     * 发送流式 PCM 音频块（录音中实时发送）
     */
    fun sendStreamingAudioChunk(chunk: ByteArray) {
        streamingWsClient?.sendAudioChunk(chunk)
    }

    /**
     * 结束流式会话：发送 audio_end → 等待后端处理 → TTS 流式播放
     *
     * 在录音完成时调用。内部切换到 ioExecutor 执行阻塞的 waitForSession，
     * 不阻塞调用线程（AudioRecorder 的后台线程）。
     */
    fun finishStreamingSession() {
        val wsClient = streamingWsClient ?: return
        // 注意：不要在此处置空 streamingWsClient！
        // 用户按停止按钮时，interrupt() 需要通过 streamingWsClient?.disconnect()
        // 断开正在运行的 WebSocket 会话。如果提前置空，停止按钮将失效。
        ioExecutor.execute {
            try {
                setState(RealtimeState.THINKING)
                wsClient.sendAudioEnd()
                setState(RealtimeState.SPEAKING)
                wsClient.waitForSession()
            } finally {
                streamingWsClient = null
                setState(RealtimeState.IDLE)
                onSessionComplete?.invoke()
            }
        }
    }

    /**
     * 检查当前是否处于流式会话中
     */
    val isStreamingActive: Boolean
        get() = streamingWsClient != null

    private fun setState(newState: RealtimeState) {
        state = newState
        callback.onStateChanged(newState)
    }
}

private fun createAsrClient(config: RealtimeConfig): RealtimeAsrClient {
    return if (config.isDirectConfigured()) DashScopeAsrClient(config) else DefaultAsrClient()
}

private fun createLlmClient(config: RealtimeConfig): RealtimeLlmClient {
    return if (config.isDirectConfigured()) DashScopeLlmClient(config) else DefaultLlmClient()
}

private fun createTtsClient(config: RealtimeConfig): RealtimeTtsClient {
    return if (config.isDirectConfigured()) DashScopeRealtimeTtsClient(config) else DefaultTtsClient()
}

private data class BackendVoiceInteractionResult(
    val recognizedText: String,
    val responseText: String,
    val pcm16kMono: ByteArray,
    val sampleRate: Int,
    val backendElapsedMs: Long
)

private class BackendVoiceInteractionClient(
    private val config: RealtimeConfig,
    private val callback: RealtimeSessionManager.Callback,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    fun interact(pcm16kMono: ByteArray): BackendVoiceInteractionResult {
        val requestStartMs = System.currentTimeMillis()
        val multipartBody = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("camera_id", "camera_1")
            .addFormDataPart("language", config.asrLanguage)
            .addFormDataPart("voice", config.ttsVoice)
            .addFormDataPart(
                "audio",
                "recording.pcm",
                pcm16kMono.toRequestBody("application/octet-stream".toMediaType())
            )
            .build()
        val response = httpClient.newCall(
            Request.Builder()
                .url("${config.backendBaseUrl.trimEnd('/')}/api/v1/voice/interaction")
                .post(multipartBody)
                .build()
        ).execute()
        return response.use {
            if (!it.isSuccessful) {
                throw IllegalStateException("backend request failed: ${it.code}")
            }
            val json = JSONObject(it.body?.string().orEmpty())
            val recognizedText = json.optString("recognized_text", "")
            val responseText = json.optString("response_text", "")
            val audioBase64 = json.optString("audio_base64", "")
            val sampleRate = json.optInt("sample_rate", 24000)
            val pcmBytes = if (audioBase64.isBlank()) {
                ByteArray(0)
            } else {
                Base64.decode(audioBase64, Base64.DEFAULT)
            }

            // --- Audio format diagnostics ---
            var actualSampleRate = sampleRate
            var cleanPcm: ByteArray
            if (pcmBytes.size >= 4 && pcmBytes[0] == 0x52.toByte() && pcmBytes[1] == 0x49.toByte() && pcmBytes[2] == 0x46.toByte() && pcmBytes[3] == 0x46.toByte()) {
                // WAV header detected — strip it
                callback.onInfo("AUDIO_DIAG: detected WAV header (RIFF)")
                val wavSampleRate = parseWavSampleRate(pcmBytes)
                if (wavSampleRate != null) {
                    actualSampleRate = wavSampleRate
                    callback.onInfo("AUDIO_DIAG: WAV header sampleRate=$wavSampleRate, reported=$sampleRate")
                }
                cleanPcm = stripWavHeader(pcmBytes)
                callback.onInfo("AUDIO_DIAG: stripped WAV header, raw PCM size=${cleanPcm.size} (was ${pcmBytes.size})")
            } else {
                cleanPcm = pcmBytes
            }

            // Log first 20 bytes as hex for diagnostics
            val hexDump = cleanPcm.take(20).joinToString(" ") { b ->
                String.format("%02x", b.toInt() and 0xFF)
            }
            callback.onInfo("AUDIO_DIAG: first 20 bytes: [$hexDump]")

            // Check for silence
            if (cleanPcm.isNotEmpty()) {
                val silentThreshold = 40  // max absolute sample value for "silence"
                var maxAbs = 0
                var clippedCount = 0
                for (i in cleanPcm.indices step 2) {
                    if (i + 1 < cleanPcm.size) {
                        val sample = ((cleanPcm[i+1].toInt() shl 8) or (cleanPcm[i].toInt() and 0xFF)).toShort()
                        val absVal = kotlin.math.abs(sample.toInt())
                        if (absVal > maxAbs) maxAbs = absVal
                        if (absVal > 32000) clippedCount++
                    }
                }
                val isSilent = maxAbs < silentThreshold
                callback.onInfo("AUDIO_DIAG: maxSample=$maxAbs, clippedSamples=$clippedCount, silent=$isSilent")
            }

            // Downsample to 16kHz if needed
            val pcm16k = if (actualSampleRate == 16000) {
                cleanPcm
            } else {
                callback.onInfo("AUDIO_DIAG: downsampling ${actualSampleRate}Hz -> 16000Hz, input size=${cleanPcm.size}")
                PcmResampler.downsamplePcm16BitMono(cleanPcm, actualSampleRate, 16000)
            }

            callback.onInfo("AUDIO_DIAG: final pcm16k size=${pcm16k.size}, expected duration=${pcm16k.size / 32000.0} sec")

            return BackendVoiceInteractionResult(
                recognizedText = recognizedText,
                responseText = responseText,
                pcm16kMono = pcm16k,
                sampleRate = actualSampleRate,
                backendElapsedMs = System.currentTimeMillis() - requestStartMs
            )
        }
    }

    /**
     * Streaming interaction: POST to /voice/interaction/stream, read NDJSON
     * response line by line, push audio chunks to DUIX as they arrive.
     */
    fun interactStream(pcm16kMono: ByteArray, engine: DigitalHumanEngine) {
        val requestStartMs = System.currentTimeMillis()
        val multipartBody = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("camera_id", "camera_1")
            .addFormDataPart("language", config.asrLanguage)
            .addFormDataPart("voice", config.ttsVoice)
            .addFormDataPart(
                "audio",
                "recording.pcm",
                pcm16kMono.toRequestBody("application/octet-stream".toMediaType())
            )
            .build()
        val response = httpClient.newCall(
            Request.Builder()
                .url("${config.backendBaseUrl.trimEnd('/')}/api/v1/voice/interaction/stream")
                .post(multipartBody)
                .build()
        ).execute()

        if (!response.isSuccessful) {
            throw IllegalStateException("backend stream request failed: ${response.code}")
        }

        var recognizedText = ""
        var responseText = ""
        var audioChunks = 0
        val pusher = StreamingPusher(engine, callback)
        pusher.start()

        try {
            val body = response.body ?: throw IllegalStateException("response body is null")
            val reader = BufferedReader(InputStreamReader(body.byteStream()))
            reader.use { r ->
                var line: String? = r.readLine()
                while (line != null) {
                    if (line.isBlank()) {
                        line = r.readLine()
                        continue
                    }
                    try {
                        val json = JSONObject(line)
                        when (json.optString("type", "")) {
                            "start" -> {
                                recognizedText = json.optString("recognized_text", "")
                                responseText = json.optString("response_text", "")
                                callback.onInfo("stream asr result: $recognizedText")
                                callback.onInfo("stream llm result: $responseText")
                            }
                            "audio" -> {
                                val audioBase64 = json.optString("data", "")
                                if (audioBase64.isNotBlank()) {
                                    val pcmChunk = Base64.decode(audioBase64, Base64.DEFAULT)
                                    if (pcmChunk.isNotEmpty()) {
                                        pusher.pushAudio(pcmChunk)
                                        audioChunks++
                                    }
                                }
                            }
                            "done" -> {
                                callback.onInfo("stream done: audioChunks=$audioChunks, backendElapsed=${System.currentTimeMillis() - requestStartMs} ms")
                            }
                        }
                    } catch (e: Exception) {
                        callback.onInfo("stream parse error: ${e.message}")
                    }
                    line = r.readLine()
                }
            }
        } finally {
            // 如果是视频数字人引擎，传入回复文字用于后端生成口型同步视频
            if (responseText.isNotBlank() && engine is com.yuanshi.avatar.engine.VideoAvatarEngineImpl) {
                engine.pendingReplyText = responseText
            }
            pusher.stop()
            callback.onInfo("stream total elapsed: ${System.currentTimeMillis() - requestStartMs} ms")
            callback.onInfo("speak finished")
        }
    }

    /**
     * Parse sample rate from a WAV header. Returns null if parsing fails.
     * WAV header layout (44 bytes standard):
     *   0-3: "RIFF", 4-7: file size, 8-11: "WAVE"
     *   12-15: "fmt ", 16-19: fmt chunk size, 20-21: audio format (1=PCM)
     *   22-23: channels, 24-27: sample rate
     */
    private fun parseWavSampleRate(wavBytes: ByteArray): Int? {
        if (wavBytes.size < 28) return null
        try {
            // Bytes 24-27: sample rate (little-endian 32-bit int)
            val sr = (wavBytes[24].toInt() and 0xFF) or
                     ((wavBytes[25].toInt() and 0xFF) shl 8) or
                     ((wavBytes[26].toInt() and 0xFF) shl 16) or
                     ((wavBytes[27].toInt() and 0xFF) shl 24)
            return sr
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Strip the WAV header from a RIFF/WAV file and return raw PCM data.
     * Searches for the "data" chunk to find the start of PCM data.
     */
    private fun stripWavHeader(wavBytes: ByteArray): ByteArray {
        if (wavBytes.size < 12) return wavBytes
        // Skip "RIFF" + size + "WAVE" (12 bytes)
        var offset = 12
        while (offset + 8 <= wavBytes.size) {
            val chunkId = String(wavBytes, offset, 4, java.nio.charset.StandardCharsets.US_ASCII)
            val chunkSize = (wavBytes[offset+4].toInt() and 0xFF) or
                            ((wavBytes[offset+5].toInt() and 0xFF) shl 8) or
                            ((wavBytes[offset+6].toInt() and 0xFF) shl 16) or
                            ((wavBytes[offset+7].toInt() and 0xFF) shl 24)
            if (chunkId == "data") {
                val dataStart = offset + 8
                val dataSize = chunkSize.coerceAtMost(wavBytes.size - dataStart)
                return wavBytes.copyOfRange(dataStart, dataStart + dataSize)
            }
            offset += 8 + chunkSize
            // Align to even boundary
            if (offset % 2 != 0) offset++
        }
        // Didn't find "data" chunk — return as-is
        return wavBytes
    }
}

private class DashScopeAsrClient(
    private val config: RealtimeConfig,
    private val httpClient: OkHttpClient = defaultHttpClient()
) : RealtimeAsrClient {
    override fun transcribe(pcm16kMono: ByteArray): String {
        val audioBase64 = Base64.encodeToString(pcm16kMono, Base64.NO_WRAP)
        val audioUri = "data:audio/pcm;base64,$audioBase64"
        val body = JSONObject().apply {
            put("model", config.asrModel)
            put("stream", false)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put(
                            "content",
                            JSONArray().put(
                                JSONObject().apply {
                                    put("type", "input_audio")
                                    put(
                                        "input_audio",
                                        JSONObject().apply {
                                            put("data", audioUri)
                                        }
                                    )
                                }
                            )
                        )
                    }
                )
            )
            put(
                "asr_options",
                JSONObject().apply {
                    put("language", config.asrLanguage)
                    put("enable_itn", config.asrEnableItn)
                    put("enable_punctuation", config.asrEnablePunctuation)
                }
            )
        }
        val response = httpClient.newCall(
            Request.Builder()
                .url("${config.baseUrl}/chat/completions")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        ).execute()
        return response.use {
            if (!it.isSuccessful) {
                throw IllegalStateException("ASR request failed: ${it.code}")
            }
            val json = JSONObject(it.body?.string().orEmpty())
            json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim()
                .orEmpty()
        }
    }
}

private class DashScopeLlmClient(
    private val config: RealtimeConfig,
    private val httpClient: OkHttpClient = defaultHttpClient()
) : RealtimeLlmClient {
    override fun reply(userText: String): String {
        val body = JSONObject().apply {
            put("model", config.chatModel)
            put("stream", false)
            put("temperature", config.chatTemperature)
            put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject().apply {
                            put("role", "system")
                            put("content", config.systemPrompt)
                        }
                    )
                    .put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", userText)
                        }
                    )
            )
        }
        val response = httpClient.newCall(
            Request.Builder()
                .url("${config.baseUrl}/chat/completions")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        ).execute()
        return response.use {
            if (!it.isSuccessful) {
                throw IllegalStateException("LLM request failed: ${it.code}")
            }
            val json = JSONObject(it.body?.string().orEmpty())
            json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content", "")
                ?.trim()
                .orEmpty()
        }
    }
}

private class DashScopeRealtimeTtsClient(
    private val config: RealtimeConfig,
    private val httpClient: OkHttpClient = defaultHttpClient()
) : RealtimeTtsClient {
    @Volatile
    private var webSocket: WebSocket? = null
    private val cancelled = AtomicBoolean(false)

    override fun synthesizeToPcm16kMono(text: String): ByteArray {
        cancelled.set(false)
        val output = ByteArrayOutputStream()
        val finished = CountDownLatch(1)
        var errorMessage: String? = null
        val request = Request.Builder()
            .url(buildTtsUrl(config.ttsWsUrl, config.ttsModel))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    JSONObject().apply {
                        put("type", "session.update")
                        put(
                            "session",
                            JSONObject().apply {
                                put("voice", config.ttsVoice)
                                put("mode", "server_commit")
                                put("response_format", "pcm")
                                put("sample_rate", config.ttsSampleRate)
                                put("speech_rate", config.ttsSpeed)
                                put("volume", config.ttsVolume)
                                put("pitch_rate", config.ttsPitch)
                            }
                        )
                    }.toString()
                )
                chunkText(text).forEach { chunk ->
                    webSocket.send(
                        JSONObject().apply {
                            put("type", "input_text_buffer.append")
                            put("text", chunk)
                        }.toString()
                    )
                }
                webSocket.send(JSONObject().apply { put("type", "session.finish") }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = JSONObject(text)
                when (event.optString("type")) {
                    "response.audio.delta" -> {
                        val delta = event.optString("delta")
                        if (delta.isNotBlank()) {
                            output.write(Base64.decode(delta, Base64.DEFAULT))
                        }
                    }
                    "response.done", "session.finished" -> {
                        finished.countDown()
                    }
                    "error" -> {
                        errorMessage = event.toString()
                        finished.countDown()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                errorMessage = t.message ?: "tts websocket failure"
                finished.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finished.countDown()
            }
        })
        finished.await(20, TimeUnit.SECONDS)
        webSocket?.cancel()
        webSocket = null
        if (cancelled.get()) {
            return ByteArray(0)
        }
        if (errorMessage != null) {
            throw IllegalStateException(errorMessage)
        }
        val audio = output.toByteArray()
        return if (config.ttsSampleRate == 16000) audio else PcmResampler.downsamplePcm16BitMono(
            audio,
            srcRate = config.ttsSampleRate,
            dstRate = 16000
        )
    }

    override fun cancel() {
        cancelled.set(true)
        webSocket?.cancel()
        webSocket = null
    }

    private fun chunkText(text: String, chunkSize: Int = 20): List<String> {
        if (text.isBlank()) {
            return emptyList()
        }
        val chunks = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + chunkSize, text.length)
            chunks.add(text.substring(start, end))
            start = end
        }
        return chunks
    }
}

private class DefaultAsrClient : RealtimeAsrClient {
    override fun transcribe(pcm16kMono: ByteArray): String {
        if (pcm16kMono.size < 3200) {
            return ""
        }
        return "未配置 DashScope API Key，当前仍在使用本地占位 ASR。"
    }
}

private class DefaultLlmClient : RealtimeLlmClient {
    override fun reply(userText: String): String {
        return "未配置直连阿里云能力，请在 local.properties 中设置 dashscope.apiKey 后重试。"
    }
}

private object PcmToneGenerator {
    fun sineWave16k(seconds: Int, frequencyHz: Double, amplitude: Double): ByteArray {
        val sampleRate = 16000
        val totalSamples = sampleRate * seconds
        val out = java.io.ByteArrayOutputStream(totalSamples * 2)
        val amp = (Short.MAX_VALUE * amplitude).toInt().coerceAtLeast(500)
        for (i in 0 until totalSamples) {
            val sample = (kotlin.math.sin(2.0 * kotlin.math.PI * frequencyHz * i / sampleRate) * amp).toInt().toShort()
            out.write(sample.toInt() and 0xFF)
            out.write((sample.toInt() shr 8) and 0xFF)
        }
        return out.toByteArray()
    }
}

private class DefaultTtsClient : RealtimeTtsClient {
    override fun synthesizeToPcm16kMono(text: String): ByteArray {
        val seconds = (text.length / 8).coerceIn(1, 4)
        return PcmToneGenerator.sineWave16k(seconds = seconds, frequencyHz = 240.0, amplitude = 0.25)
    }

    override fun cancel() {
    }
}



/**
 * Streaming PCM pusher: accepts 24000Hz 16-bit mono chunks from the backend,
 * downsamples to 16000Hz on-the-fly, and pushes 320-byte frames to DUIX.
 *
 * 线程安全：synchronized 保护 internal buffer，防止 WebSocket 线程和
 * ioExecutor 线程同时访问导致崩溃或数据损坏。
 */
private class StreamingPusher(
    private val engine: DigitalHumanEngine,
    private val callback: RealtimeSessionManager.Callback
) {
    private val inputBuf = ByteArrayOutputStream()
    /**
     * 防止同一个 WebSocket 会话中多个 tts_start 重复创建 DUIX session。
     *
     * 工具调用场景下，后端会在同一个 WebSocket 连接中发送两个 tts_start：
     *   1. 提示语（如"好的，正在查询..."）
     *   2. 工具结果（如新闻内容）
     *
     * 如果第二次 tts_start 再次调用 startPush()，DUIX SDK 会 finsession(old) +
     * newsession()，新 session 需要约 700-800ms 才能产出第一帧口型数据，
     * 导致提示语到新闻内容切换期间嘴型不动（声音已开始播但口型未同步）。
     *
     * 由于 stopPush() 只在 WebSocket 会话结束时调用一次（不在两个 TTS 流之间），
     * 因此用 started 标志跳过第二次 startPush()，让第二段 PCM 继续流入同一个 session，
     * 避免 session 重建开销。
     */
    private var started = false

    fun start() {
        if (started) {
            callback.onInfo(">>> STREAM: already started, keeping session alive for second TTS")
            return
        }
        callback.onInfo(">>> STREAM: calling engine.startPush()")
        try {
            engine.startPush()
            started = true
        } catch (e: Exception) {
            callback.onError("stream startPush error: ${e.message}")
        }
    }

    /**
     * Push a raw 24000Hz PCM chunk. Accumulates and downsamples to 16000Hz
     * in 320-byte frames (10ms each).
     */
    fun pushAudio(pcm24kChunk: ByteArray) {
        val frames16k: ByteArray
        synchronized(inputBuf) {
            inputBuf.write(pcm24kChunk)
            val inputBytes = inputBuf.toByteArray()
            val neededInputBytes = 480  // 10ms at 24kHz → 320 bytes at 16kHz

            if (inputBytes.size < neededInputBytes) {
                return  // not enough data yet
            }

            val numCompleteFrames = inputBytes.size / neededInputBytes
            val bytesToProcess = numCompleteFrames * neededInputBytes

            val chunk24k = inputBytes.copyOfRange(0, bytesToProcess)
            val remaining = inputBytes.copyOfRange(bytesToProcess, inputBytes.size)

            frames16k = PcmResampler.downsamplePcm16BitMono(chunk24k, 24000, 16000)

            inputBuf.reset()
            if (remaining.isNotEmpty()) {
                inputBuf.write(remaining)
            }
        }

        // 在 synchronized 块外推送到 DUIX，避免持有锁时调用 native 方法
        if (frames16k.isNotEmpty()) {
            try {
                var cursor = 0
                while (cursor < frames16k.size) {
                    val end = minOf(cursor + 320, frames16k.size)
                    engine.pushPcm(frames16k.copyOfRange(cursor, end))
                    cursor = end
                }
            } catch (e: Exception) {
                callback.onError("stream pushPcm error: ${e.message}")
            }
        }
    }

    /**
     * Flush remaining buffered audio and stop DUIX push.
     */
    fun stop() {
        val remaining: ByteArray
        synchronized(inputBuf) {
            remaining = inputBuf.toByteArray()
            inputBuf.reset()
        }

        if (remaining.size >= 240) {  // at least 5ms worth
            try {
                val chunk16k = PcmResampler.downsamplePcm16BitMono(remaining, 24000, 16000)
                if (chunk16k.isNotEmpty()) {
                    var cursor = 0
                    while (cursor < chunk16k.size) {
                        val end = minOf(cursor + 320, chunk16k.size)
                        engine.pushPcm(chunk16k.copyOfRange(cursor, end))
                        cursor = end
                    }
                }
            } catch (e: Exception) {
                callback.onError("stream flush error: ${e.message}")
            }
        }

        callback.onInfo(">>> STREAM: calling engine.stopPush()")
        try {
            engine.stopPush()
        } catch (e: Exception) {
            callback.onError("stream stopPush error: ${e.message}")
        } finally {
            started = false
        }
    }
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private fun defaultHttpClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
}

private fun buildTtsUrl(baseWsUrl: String, model: String): String {
    return if (baseWsUrl.contains("?")) {
        "$baseWsUrl&model=$model"
    } else {
        "$baseWsUrl?model=$model"
    }
}

private fun estimatePlaybackMs(pcm16kMonoBytes: Int): Long {
    if (pcm16kMonoBytes <= 0) {
        return 0L
    }
    val bytesPerSecond = 16000 * 2
    return pcm16kMonoBytes * 1000L / bytesPerSecond
}


// ==================== WebSocket 语音交互客户端 ====================

/**
 * VoiceWebSocketClient — WebSocket 模式语音交互客户端
 *
 * 替代 HTTP POST + NDJSON 流式模式，支持全双工通信：
 * - 发送音频 binary (PCM 16kHz) → 接收 TTS binary (PCM 24kHz)
 * - 接收 tool_call JSON → 执行设备控制 → 发送 tool_result
 *
 * 协议文档见后端 /ws/voice 端点。
 */
private class VoiceWebSocketClient(
    private val config: RealtimeConfig,
    private val engine: DigitalHumanEngine,
    private val deviceController: DeviceController?,
    private val callback: RealtimeSessionManager.Callback
) {
    private var webSocket: WebSocket? = null
    private val httpClient: OkHttpClient = defaultHttpClient()
    private val pusher = StreamingPusher(engine, callback)
    private val sessionDone = CountDownLatch(1)
    private val connected = AtomicBoolean(false)
    /** 最近一次 tts_start 事件的回复文字，用于视频数字人引擎 */
    private var pendingWsReplyText: String = ""

    private val connectLatch = CountDownLatch(1)

    /**
     * 连接后端 WebSocket /ws/voice 端点
     */
    fun connect(): Boolean {
        // 后端 WebSocket 端点注册在根路径（/ws/voice），不在 /api/v1 下
        // 需要从 HTTP base URL 中剥离 API 前缀
        var wsUrl = config.backendBaseUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .trimEnd('/')
            .removeSuffix("/api/v1")
            .removeSuffix("/api")
            .removeSuffix("/v1") + "/ws/voice"

        // 附加 device_id 用于跨轮次对话上下文
        if (config.deviceId.isNotBlank()) {
            wsUrl += "?device_id=${config.deviceId}"
        }

        callback.onInfo("ws connecting to $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                callback.onInfo("ws connected")
                connected.set(true)
                connectLatch.countDown()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleTextMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                handleBinaryMessage(bytes.toByteArray())
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                callback.onError("ws failure: ${t.message}")
                connected.set(false)
                connectLatch.countDown()
                sessionDone.countDown()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                callback.onInfo("ws closed: $code $reason")
                sessionDone.countDown()
            }
        })

        // 等待连接建立（最多 5 秒）
        try {
            connectLatch.await(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            callback.onError("ws connect timeout: ${e.message}")
        }

        if (!connected.get()) {
            callback.onError("ws connect failed")
            return false
        }
        return true
    }

    /**
     * 发送整段 PCM 音频 binary (16kHz 16-bit mono)
     */
    fun sendAudio(pcm16kMono: ByteArray) {
        if (connected.get()) {
            webSocket?.send(pcm16kMono.toByteString())
            callback.onInfo("ws sent audio: ${pcm16kMono.size} bytes")
        }
    }

    /**
     * 发送流式 PCM 音频块 (16kHz 16-bit mono)
     * 不等录音结束，边录边发。后端累积这些块直到收到 audio_end。
     */
    fun sendAudioChunk(pcm16kChunk: ByteArray) {
        if (connected.get()) {
            webSocket?.send(pcm16kChunk.toByteString())
        }
    }

    /**
     * 发送音频结束标记
     */
    fun sendAudioEnd() {
        if (connected.get()) {
            webSocket?.send(
                JSONObject().apply { put("type", "audio_end") }.toString()
            )
            callback.onInfo("ws audio_end sent")
        }
    }

    /**
     * 发送工具执行结果
     */
    fun sendToolResult(id: String, result: Any) {
        if (connected.get()) {
            val msg = JSONObject().apply {
                put("type", "tool_result")
                put("id", id)
                when (result) {
                    is Map<*, *> -> put("result", JSONObject(result as Map<String, Any>))
                    is String -> put("result", result)
                    else -> put("result", result.toString())
                }
            }
            webSocket?.send(msg.toString())
            callback.onInfo("ws tool_result sent: id=$id")
        }
    }

    /**
     * 等待会话完成（阻塞，直到收到 done 或断开连接）
     */
    fun waitForSession() {
        try {
            // 等待后端处理完成（ASR + LLM + 流式TTS），最长 40 秒。
            // 此前为 60 秒，后端同步阻塞（如 tts.connect()）挂死时用户等待过久。
            // 后端 TTS 流式部分已有 90 秒超时保护，此处 40 秒作为兜底。
            if (!sessionDone.await(40, TimeUnit.SECONDS)) {
                callback.onError("ws session timed out after 40s, no response from backend")
            }
        } catch (e: InterruptedException) {
            callback.onError("ws session interrupted")
        } finally {
            // 如果是视频数字人引擎，传入回复文字用于后端生成口型同步视频
            if (pendingWsReplyText.isNotBlank() && engine is com.yuanshi.avatar.engine.VideoAvatarEngineImpl) {
                engine.pendingReplyText = pendingWsReplyText
            }
            // 仅在有回复文字时才调用 pusher.stop()。
            // 如果后端处理失败（未收到 tts_start），pendingWsReplyText 为空，
            // 此时调用 pusher.stop() → engine.stopPush() 会使用默认文字 "你好，欢迎使用智能关怀系统"
            // 向后端发起 speak 请求生成视频，导致 TOGGLE 模式下出现重复默认文字视频的循环。
            if (pendingWsReplyText.isNotBlank()) {
                pusher.stop()
            } else {
                callback.onInfo("ws: skip pusher.stop() (no tts_start)")
            }
            webSocket?.close(1000, "session done")
            webSocket = null
        }
    }

    /**
     * 强制断开连接（从中断或主线程调用）。
     * 触发 sessionDone 让 waitForSession() 退出，由 waitForSession 的 finally 清理。
     * 不会调 pusher.stop() 以免与 waitForSession 的 finally 并发执行 duix 操作。
     */
    fun disconnect() {
        webSocket?.close(1000, "interrupted")
        webSocket = null
        sessionDone.countDown()
    }

    private fun handleTextMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "connected" -> {
                    val clientId = json.optString("client_id", "")
                    callback.onInfo("ws ready: client_id=$clientId")
                }
                "tts_start" -> {
                    val replyText = json.optString("text", "")
                    callback.onInfo("ws tts_start: ${replyText.take(50)}")
                    pendingWsReplyText = replyText
                    pusher.start()
                }
                "tool_call" -> {
                    handleToolCall(json)
                }
                "done" -> {
                    val timing = json.optJSONObject("timing_ms")
                    callback.onInfo("ws done: $timing")
                    sessionDone.countDown()
                }
                "error" -> {
                    callback.onError("ws error: ${json.optString("message")}")
                    sessionDone.countDown()
                }
                else -> {
                    callback.onInfo("ws msg: type=${json.optString("type")}")
                }
            }
        } catch (e: Exception) {
            callback.onError("ws parse error: ${e.message}")
        }
    }

    private fun handleBinaryMessage(bytes: ByteArray) {
        try {
            // TTS 音频块 (24kHz PCM) → 通过 StreamingPusher 下采样后推给 DUIX
            pusher.pushAudio(bytes)
        } catch (e: Exception) {
            callback.onError("ws binary error: ${e.message}")
        }
    }

    private fun handleToolCall(json: JSONObject) {
        val id = json.optString("id", "")
        val tool = json.optString("tool", "")
        val args = json.optJSONObject("args") ?: JSONObject()

        callback.onInfo("ws tool_call: $tool args=$args")

        if (deviceController != null) {
            val result = deviceController.execute(tool, args)
            callback.onInfo("ws tool_result: ${result.toString().take(80)}")
            sendToolResult(id, result)
        } else {
            callback.onError("ws no DeviceController for tool: $tool")
            sendToolResult(id, mapOf("error" to "DeviceController not available"))
        }
    }
}


// ==================== Android 设备控制器 ====================

/**
 * DeviceController — Android 设备控制服务
 *
 * 处理 LLM 通过 Function Calling 下发的工具调用指令：
 * - set_volume: 调节媒体音量
 * - open_app:  打开指定应用
 * - take_photo: 拍照（占位实现）
 *
 * 需要 Android Context 来访问系统服务。
 */
open class DeviceController(private val context: Context) {

    /**
     * 启动应用前的回调。
     *
     * 用于在 startActivity 之前触发画中画（PiP）模式，
     * 确保 DUIX 数字人在其他应用覆盖时仍以悬浮窗可见。
     * 由 CallActivity 在创建 DeviceController 时绑定。
     */
    var onBeforeLaunchApp: (() -> Unit)? = null

    /**
     * 拍照结果回调。
     * success=true 表示拍照成功，message 包含提示文字；
     * success=false 表示拍照失败，message 包含错误描述。
     * 用于在 CallActivity 中显示 Toast 反馈以及日志。
     */
    var onPhotoTaken: ((success: Boolean, message: String) -> Unit)? = null

    // 缓存已安装应用列表，避免频繁调用 getInstalledApplications（慢 + 阻塞 WebSocket 线程）
    private var cachedAllApps: List<android.content.pm.ApplicationInfo>? = null
    private var cachedAllAppsTimeMs: Long = 0L
    private val cacheValidDurationMs: Long = 30_000L  // 缓存有效期 30 秒

    /** 安全地检查 Activity 上下文是否仍有效（防止 startActivity 在已销毁的 Activity 上调用） */
    private fun isActivityValid(): Boolean {
        if (context is android.app.Activity) {
            return !context.isFinishing && !context.isDestroyed
        }
        return true  // 非 Activity 上下文（如 Application），始终有效
    }

    /** 在主线程上显示 Toast（安全地，带 isFinishing 检查） */
    private fun showToastOnMainThread(message: String) {
        if (context !is android.app.Activity) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (!isActivityValid()) return@post
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** 获取已安装应用列表（带缓存） */
    @Suppress("DEPRECATION")
    private fun getInstalledApps(pm: PackageManager): List<android.content.pm.ApplicationInfo> {
        val now = System.currentTimeMillis()
        if (cachedAllApps != null && now - cachedAllAppsTimeMs < cacheValidDurationMs) {
            return cachedAllApps!!
        }
        val apps = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }
        cachedAllApps = apps
        cachedAllAppsTimeMs = now
        return apps
    }

    /**
     * 在主线程上安全地启动 Activity（带 isFinishing 检查）
     * 防止在 Activity 已销毁时调用 startActivity 导致闪退
     */
    private fun safeStartActivityOnMainThread(intent: android.content.Intent) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (!isActivityValid()) {
                android.util.Log.w("DeviceController", "safeStartActivity: activity not valid, skipping")
                return@post
            }
            try {
                context.startActivity(intent)
                // 启动成功后才触发 PiP（防止启动失败时 PiP 已被触发但目标应用未打开）
                onBeforeLaunchApp?.invoke()
            } catch (e: android.content.ActivityNotFoundException) {
                android.util.Log.e("DeviceController", "startActivity not found: ${e.message}")
            } catch (e: Exception) {
                android.util.Log.e("DeviceController", "startActivity failed: ${e.message}")
            }
        }
    }

    /**
     * 执行指定的设备控制工具
     * @return 执行结果（Map 会被序列化为 JSON 发送回后端）
     */
    open fun execute(tool: String, args: JSONObject): Map<String, Any> {
        return when (tool) {
            "set_volume" -> setVolume(args.optInt("level", 50))
            "open_app" -> openApp(
                args.optString("package", ""),
                args.optString("app_name", "")
            )
            "media_control" -> mediaControl(
                args.optString("action", "play_pause"),
                args.optString("app_name", "")
            )
            "close_app" -> closeApp(
                args.optString("package", ""),
                args.optString("app_name", "")
            )
            "take_photo" -> takePhoto()
            else -> mapOf("error" to "unknown tool: $tool")
        }
    }

    /**
     * 设置媒体音量
     * @param level 0-100 的百分比音量
     */
    private fun setVolume(level: Int): Map<String, Any> {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVol = (level * maxVol / 100).coerceIn(0, maxVol)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            mapOf(
                "status" to "ok",
                "level" to level,
                "android_volume" to targetVol,
                "max_volume" to maxVol
            )
        } catch (e: Exception) {
            mapOf("status" to "error", "message" to e.message.orEmpty())
        }
    }

    /**
     * 通过包名或应用名启动应用
     *
     * 优先使用 packageName 精确匹配；如果未提供或找不到，则按 appName
     * 在所有已安装应用中搜索匹配的启动 Activity。
     */
    private fun openApp(packageName: String, appName: String): Map<String, Any> {
        return try {
            val pm = context.packageManager

            // 1) 先尝试包名精确匹配
            if (packageName.isNotBlank()) {
                val intent = pm.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    safeStartActivityOnMainThread(intent)
                    return mapOf("status" to "ok", "app" to appName, "package" to packageName)
                }
            }

            // 2) 常用应用包名映射表（app_name → package_name）
            // 当 LLM 只传了 app_name 没传 package 时，直接从映射表查找
            if (appName.isNotBlank()) {
                val knownApps = mapOf(
                    "网易云音乐" to "com.netease.cloudmusic",
                    "QQ音乐" to "com.tencent.qqmusic",
                    "酷狗音乐" to "com.kugou.android",
                    "网易云" to "com.netease.cloudmusic",
                    "微信" to "com.tencent.mm",
                    "支付宝" to "com.eg.android.AlipayGphone",
                    "抖音" to "com.ss.android.ugc.aweme",
                    "抖音短视频" to "com.ss.android.ugc.aweme",
                    "淘宝" to "com.taobao.taobao",
                    "京东" to "com.jingdong.app.mall",
                    "哔哩哔哩" to "tv.danmaku.bili",
                    "B站" to "tv.danmaku.bili",
                    "百度" to "com.baidu.searchbox",
                    "高德地图" to "com.autonavi.minimap",
                    "美团" to "com.sankuai.meituan",
                    "饿了么" to "me.ele",
                    "微博" to "com.sina.weibo",
                    "小红书" to "com.xingin.xhs",
                    "爱奇艺" to "com.qiyi.video",
                    "腾讯视频" to "com.tencent.qqlive",
                    "优酷" to "com.youku.phone",
                    "钉钉" to "com.alibaba.android.rimet",
                    "企业微信" to "com.tencent.wework",
                )
                val knownPkg = knownApps.entries.firstOrNull { (name, _) ->
                    appName.contains(name, ignoreCase = true) || name.contains(appName, ignoreCase = true)
                }?.value
                if (knownPkg != null) {
                    val intent = pm.getLaunchIntentForPackage(knownPkg)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        safeStartActivityOnMainThread(intent)
                        return mapOf("status" to "ok", "app" to appName, "package" to knownPkg)
                    }
                }
            }

            // 3) 如果映射表没命中，按显示名称搜索（带评分排序）
            if (appName.isNotBlank()) {
                // 方式 A: 标准 Launcher 查询（带评分排序，避免"网易云音乐"误匹配到"网易"）
                val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val activities: List<ResolveInfo> = pm.queryIntentActivities(mainIntent, 0)
                val scored = activities.mapNotNull { info ->
                    val label = info.loadLabel(pm).toString()
                    val score = matchAppNameScore(label, appName)
                    if (score > 0) Pair(score, info) else null
                }.sortedByDescending { it.first }

                if (scored.isNotEmpty()) {
                    val best = scored.first().second
                    return launchApp(pm, best.activityInfo.packageName, best.loadLabel(pm).toString())
                }

                // 方式 B: 已安装应用缓存兜底（覆盖未声明 LAUNCHER 的应用）
                val allApps = getInstalledApps(pm)
                val scoredAll = allApps.mapNotNull { app ->
                    try {
                        val label = app.loadLabel(pm).toString()
                        val score = matchAppNameScore(label, appName)
                        if (score > 0) Pair(score, app) else null
                    } catch (_: Exception) { null }
                }.sortedByDescending { it.first }

                if (scoredAll.isNotEmpty()) {
                    val best = scoredAll.first().second
                    val label = best.loadLabel(pm).toString()
                    val result = launchApp(pm, best.packageName, label)
                    if (result["status"] == "ok") return result
                }

                showToastOnMainThread("未找到应用: $appName")
                return mapOf("status" to "error", "message" to "未找到应用: $appName")
            }

            showToastOnMainThread("未指定应用名称或包名")
            mapOf("status" to "error", "message" to "未指定应用名称或包名")
        } catch (e: Exception) {
            showToastOnMainThread("打开应用失败: ${e.message}")
            mapOf("status" to "error", "message" to e.message.orEmpty())
        }
    }

    /** 启动应用并返回结果 */
    private fun launchApp(pm: PackageManager, pkg: String, label: String): Map<String, Any> {
        val intent = pm.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // 使用安全启动方法（带 isFinishing 检查和异常捕获）
            safeStartActivityOnMainThread(intent)
            return mapOf("status" to "ok", "app" to label, "package" to pkg)
        }
        return mapOf("status" to "error", "message" to "can't launch: $pkg")
    }

    /**
     * 媒体控制：播放/暂停/下一首/上一首/停止
     *
     * 策略（按优先级）：
     * 1. 如果有 appName，先尝试通过包名打开该应用（确保它在运行）
     * 2. AudioManager.dispatchMediaKeyEvent() 发送媒体按键事件（通用方案）
     * 3. 兜底：com.android.music.musicservicecommand 广播（国内应用兼容）
     */
    private fun mediaControl(action: String, appName: String): Map<String, Any> {
        val keyCode = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }

        // 如果指定了 appName，尝试打开该应用（确保音乐 App 在前台可接收媒体按键）
        if (appName.isNotBlank()) {
            try {
                val pm = context.packageManager
                // 搜索匹配的应用名
                val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val activities = pm.queryIntentActivities(mainIntent, 0)
                val matched = activities.firstOrNull { info ->
                    val label = info.loadLabel(pm).toString()
                    label.contains(appName, ignoreCase = true) ||
                    appName.contains(label, ignoreCase = true)
                }
                if (matched != null) {
                    val launchIntent = pm.getLaunchIntentForPackage(matched.activityInfo.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        // 使用安全启动方法（不触发 PiP，因为媒体控制不需要覆盖全屏）
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (context !is android.app.Activity || (!context.isFinishing && !context.isDestroyed)) {
                                try { context.startActivity(launchIntent) }
                                catch (_: Exception) { }
                            }
                        }
                    }
                }
            } catch (_: Exception) { }
        }

        try {
            // 方式 1（首选）：AudioManager.dispatchMediaKeyEvent
            // 在主线程上执行，避免 SystemClock.sleep 阻塞 WebSocket 线程
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            mainHandler.post {
                try {
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val downTime = android.os.SystemClock.uptimeMillis()
                    audioManager.dispatchMediaKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0))
                    audioManager.dispatchMediaKeyEvent(KeyEvent(downTime, downTime + 15, KeyEvent.ACTION_UP, keyCode, 0))
                } catch (_: Exception) { }
            }
            return mapOf("status" to "ok", "action" to action, "method" to "dispatch_key")
        } catch (e: Exception) {
            // 方式 2（兜底）：传统音乐广播
            try {
                val broadcastIntent = Intent("com.android.music.musicservicecommand").apply {
                    val cmd = when (action) {
                        "play" -> "play"
                        "pause" -> "pause"
                        "play_pause" -> "togglepause"
                        "next" -> "next"
                        "previous" -> "previous"
                        "stop" -> "stop"
                        else -> action
                    }
                    putExtra("command", cmd)
                }
                context.sendBroadcast(broadcastIntent)
                return mapOf("status" to "ok", "action" to action, "method" to "music_broadcast")
            } catch (_: Exception) {
                return mapOf("status" to "error", "action" to action, "message" to e.message.orEmpty())
            }
        }
    }

    /**
     * 关闭/停止指定应用
     *
     * Android 没有给第三方应用提供真正的"关闭其他应用" API（Android 10+ 限制）。
     * 我们能做的最佳方案：
     * 1. 停止媒体播放（dispatchMediaKeyEvent STOP）
     * 2. 杀后台进程（killBackgroundProcesses）
     * 3. 进入画中画（让 DUIX 保持可见，目标应用被停止后用户能看到数字人）
     *
     * 注意：不再发送 HOME Intent，因为那会同时隐藏 DUIX。改为触发 PiP 悬浮窗。
     */
    private fun closeApp(packageName: String, appName: String): Map<String, Any> {
        return try {
            val pm = context.packageManager
            val pkg = if (packageName.isNotBlank()) {
                packageName
            } else if (appName.isNotBlank()) {
                // 使用缓存版本，避免阻塞 WebSocket 线程
                val allApps = getInstalledApps(pm)
                val matched = allApps.firstOrNull { app ->
                    try {
                        val label = app.loadLabel(pm).toString()
                        label.contains(appName, ignoreCase = true) ||
                        appName.contains(label, ignoreCase = true)
                    } catch (_: Exception) { false }
                }
                matched?.packageName ?: return mapOf("status" to "error", "message" to "未找到应用: $appName")
            } else {
                return mapOf("status" to "error", "message" to "未指定应用名称或包名")
            }

            // 自我保护：拒绝关闭自己（防止 LLM 误调用 close_app 传入本应用包名或匹配到自身）
            if (pkg == context.packageName) {
                return mapOf("status" to "error", "message" to "不能关闭自己")
            }

            // 1. 停止媒体播放（先发 PAUSE 再发 STOP，提高兼容性）
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            mainHandler.post {
                try {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val dt = android.os.SystemClock.uptimeMillis()
                    // 先暂停，再停止，覆盖更多音乐 App 的实现
                    am.dispatchMediaKeyEvent(KeyEvent(dt, dt, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
                    am.dispatchMediaKeyEvent(KeyEvent(dt, dt + 15, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
                    am.dispatchMediaKeyEvent(KeyEvent(dt + 30, dt + 30, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP, 0))
                    am.dispatchMediaKeyEvent(KeyEvent(dt + 30, dt + 45, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP, 0))
                } catch (_: Exception) { }
            }

            // 2. 杀后台进程（Android 10+ 限制只能杀后台，不能杀前台）
            try {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                activityManager.killBackgroundProcesses(pkg)
            } catch (_: Exception) { }

            // 注意：不进入 PiP！closeApp 只是停止音乐 + 杀后台，不会启动其他应用覆盖 DUIX
            // 进入 PiP 会使录音按钮隐藏，PUSH_TO_TALK 模式下用户无法再操作，导致"卡死"

            mapOf("status" to "ok", "package" to pkg, "action" to "close")
        } catch (e: Exception) {
            mapOf("status" to "error", "message" to e.message.orEmpty())
        }
    }

    /**
     * 应用名称匹配评分（0=不匹配，分数越高匹配越精确）
     *
     * 避免"网易云音乐"误匹配到"网易"、"音乐"等包含子串的应用。
     * 评分规则：
     *   - 100: 完全相等
     *   - 90:  搜索词以应用名开头（如"网易云" → "网易云音乐"）
     *   - 80:  应用名以搜索词开头（如"网易云音乐" → "网易云音乐HD"）
     *   - 50:  搜索词包含应用名，但应用名字数 >= 4（不太可能是误匹配）
     *   - 30:  应用名包含搜索词，且搜索词字数 >= 4
     *   - 0:   其他情况（应用名太短且仅是子串，很可能是误匹配）
     */
    private fun matchAppNameScore(label: String, query: String): Int {
        val labelNorm = label.trim().lowercase()
        val queryNorm = query.trim().lowercase()
        if (labelNorm.isEmpty() || queryNorm.isEmpty()) return 0
        if (labelNorm == queryNorm) return 100
        if (queryNorm.startsWith(labelNorm) && labelNorm.length >= 2) return 90
        if (labelNorm.startsWith(queryNorm) && queryNorm.length >= 2) return 80
        // 双向包含检查（要求较长的一方 >= 4 个字，避免"网""音乐"等短字误匹配）
        if (labelNorm.length >= 4 && labelNorm.contains(queryNorm)) return 50
        if (queryNorm.length >= 4 && queryNorm.contains(labelNorm)) return 60
        if (queryNorm.length >= 3 && queryNorm.contains(labelNorm) && labelNorm.length >= 3) return 40
        return 0
    }

    /**
     * 拍照（CameraX 实现）
     *
     * 使用 CameraX 的 ImageCapture 拍摄全尺寸 JPEG 照片，保存到临时文件后
     * 读取为 base64 编码，通过 tool_result 返回给后端 LLM 处理。
     *
     * 注意：需要在主线程执行（CameraX 要求），通过 CountDownLatch 同步等待。
     */
    private fun takePhoto(): Map<String, Any> {
        return try {
            // 检查 CAMERA 权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val hasCameraPermission = context.checkSelfPermission(android.Manifest.permission.CAMERA)
                if (hasCameraPermission != PackageManager.PERMISSION_GRANTED) {
                    val err = "未授予相机权限"
                    onPhotoTaken?.invoke(false, err)
                    return mapOf("status" to "error", "message" to err)
                }
            }

            // 检查是否有摄像头
            val pm = context.packageManager
            if (!pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                val err = "设备没有摄像头"
                onPhotoTaken?.invoke(false, err)
                return mapOf("status" to "error", "message" to err)
            }

            val latch = CountDownLatch(1)
            var resultMap = mapOf<String, Any>("status" to "error", "message" to "capture failed")

            val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
            if (lifecycleOwner == null) {
                val err = "context is not LifecycleOwner"
                onPhotoTaken?.invoke(false, err)
                return mapOf("status" to "error", "message" to err)
            }

            // 在主线程上执行 CameraX 操作
            ContextCompat.getMainExecutor(context).execute {
                try {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()

                            val imageCapture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .setTargetRotation(Surface.ROTATION_0)
                                .build()

                            // 先用后置摄像头，如果失败再试前置
                            var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)

                            // 等待 500ms 让 camera 完全 ready 后再 takePicture
                            // 部分机型 bindToLifecycle 后立即 takePicture 会失败
                            try {
                                Thread.sleep(500)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }

                            val photoFile = File(
                                context.cacheDir,
                                "photo_${System.currentTimeMillis()}.jpg"
                            )
                            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                            imageCapture.takePicture(
                                outputOptions,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                        try {
                                            val bytes = photoFile.readBytes()
                                            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                            val msg = "拍照成功 (${bytes.size} bytes)"
                                            android.util.Log.i("DeviceController", "takePhoto: $msg")
                                            onPhotoTaken?.invoke(true, msg)

                                            val savedFilename = "DUIX_${System.currentTimeMillis()}.jpg"

                                            // 保存到系统相册，让用户在相册 App 中能看到
                                            try {
                                                val contentValues = ContentValues().apply {
                                                    put(MediaStore.Images.Media.DISPLAY_NAME, savedFilename)

                                                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                        put(MediaStore.Images.Media.IS_PENDING, 1)
                                                    }
                                                }
                                                val uri = context.contentResolver.insert(
                                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                                    contentValues
                                                )
                                                if (uri != null) {
                                                    context.contentResolver.openOutputStream(uri)?.use { out ->
                                                        out.write(bytes)
                                                    }
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                        contentValues.clear()
                                                        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                                                        context.contentResolver.update(uri, contentValues, null, null)
                                                    }
                                                    val saveMsg = "照片已保存到相册: $savedFilename"
                                                    android.util.Log.i("DeviceController", "takePhoto: $saveMsg")
                                                    onPhotoTaken?.invoke(true, saveMsg)
                                                }
                                            } catch (e: Exception) {
                                                android.util.Log.w("DeviceController", "保存到相册失败: ${e.message}")
                                            }

                                            resultMap = mapOf(
                                                "status" to "ok",
                                                "image_base64" to base64,
                                                "size_bytes" to bytes.size,
                                                "format" to "jpeg",
                                                "saved_to" to "$savedFilename"
                                            )
                                        } catch (e: Exception) {
                                            val err = "拍照后读取文件失败: ${e.message}"
                                            android.util.Log.e("DeviceController", "takePhoto: $err", e)
                                            onPhotoTaken?.invoke(false, err)
                                            resultMap = mapOf("status" to "error", "message" to err)
                                        } finally {
                                            // 清理临时文件
                                            try { photoFile.delete() } catch (_: Exception) {}
                                            latch.countDown()
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        val err = "拍照失败: ${exception.message} (code=${exception.imageCaptureError})"
                                        android.util.Log.e("DeviceController", "takePhoto: $err")
                                        onPhotoTaken?.invoke(false, err)
                                        resultMap = mapOf(
                                            "status" to "error",
                                            "message" to err,
                                            "error_code" to exception.imageCaptureError
                                        )
                                        latch.countDown()
                                    }
                                })
                        } catch (e: Exception) {
                            val err = "CameraX 异常: ${e.message}"
                            android.util.Log.e("DeviceController", "takePhoto: $err", e)
                            onPhotoTaken?.invoke(false, err)
                            resultMap = mapOf("status" to "error", "message" to err)
                            latch.countDown()
                        }
                    }, ContextCompat.getMainExecutor(context))
                } catch (e: Exception) {
                    val err = "CameraX 初始化异常: ${e.message}"
                    android.util.Log.e("DeviceController", "takePhoto: $err", e)
                    onPhotoTaken?.invoke(false, err)
                    resultMap = mapOf("status" to "error", "message" to err)
                    latch.countDown()
                }
            }

            // 等待拍照完成（最长 15 秒）
            latch.await(15, TimeUnit.SECONDS)
            resultMap

        } catch (e: Exception) {
            mapOf("status" to "error", "message" to e.message.orEmpty())
        }
    }
}
