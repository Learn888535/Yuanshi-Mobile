package com.yuanshi.avatar.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Base64
import android.util.Log
import com.yuanshi.avatar.engine.DigitalHumanEngine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * PushWebSocketClient — 持久化推送 WebSocket 客户端
 *
 * 连接到后端 /ws/push 端点，接收服务器推送的警报和 TTS 语音消息。
 * 与语音会话独立，此连接持久保持并在断线时自动重连。
 *
 * 消息类型（新版分块协议）：
 * - connected: 连接确认 { type:"connected", client_id:"push_..." }
 * - alert: 告警通知 { type:"alert", data:{...} }
 * - alert_resolved: 告警已解决 { type:"alert_resolved", data:{...} }
 * - tts_speak_start: TTS 开始 { type:"tts_speak_start", text:"...", sample_rate, total_bytes, total_chunks }
 * - binary: PCM 24kHz 16-bit 分块（4字节块序号 + PCM 数据）
 * - tts_speak_done: TTS 结束 { type:"tts_speak_done", text:"...", total_chunks, total_bytes }
 *
 * 兼容旧版：
 * - tts_speak: 旧版单次 JSON { type:"tts_speak", text:"...", audio_base64:"...", sample_rate }
 */
class PushWebSocketClient(
    context: Context,
    private val backendBaseUrl: String,
    @Volatile private var engine: DigitalHumanEngine?,
    private val callback: Callback
) {
    interface Callback {
        /** WebSocket 连接状态变更 */
        fun onPushWsStateChanged(connected: Boolean)
        /** 收到告警通知（可用于弹出通知栏） */
        fun onAlertReceived(alertData: JSONObject)
        /**
         * 收到跌倒主动询问监听指令
         * 数字人 TTS 播报询问后，Android 需在此回调中等待播报完成，然后自动启动录音。
         * @param text 询问文本（与 TTS 播报内容一致）
         * @param deviceId 目标设备 ID
         * @param timeoutSeconds 后端等待用户回应的超时秒数
         */
        fun onInquiryListen(text: String, deviceId: String, timeoutSeconds: Int)
    }

    companion object {
        private const val TAG = "PushWSClient"
        private const val RECONNECT_DELAY_MS = 5000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
        private const val PUSH_FRAME_SIZE = 3200
    }

    /** 应用 Context（避免持有 Activity 引用导致泄漏） */
    private val appContext: Context = context.applicationContext

    /** 网络状态监听器 — 网络恢复时立即触发重连 */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val ioExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var webSocket: WebSocket? = null
    private val connected = AtomicBoolean(false)
    private val shouldReconnect = AtomicBoolean(true)
    private val reconnectAttempt = AtomicInteger(0)
    private var clientId: String = ""

    // ── 流式播放状态 ─────────────────────────────────
    @Volatile
    private var streamingText: String = ""
    @Volatile
    private var streamingSampleRate: Int = 24000
    private val chunkBuffer = hashMapOf<Int, ByteArray>()
    private val chunkLock = Any()
    private var totalChunksExpected: Int = 0
    private var chunkReceivedCount: Int = 0
    private val streamingActive = AtomicBoolean(false)

    fun connect() {
        if (connected.get()) {
            Log.d(TAG, "already connected, skip")
            return
        }
        shouldReconnect.set(true)
        registerNetworkCallback()
        ioExecutor.execute { doConnect() }
    }

    fun disconnect() {
        shouldReconnect.set(false)
        connected.set(false)
        stopStreaming()
        unregisterNetworkCallback()
        webSocket?.close(1000, "client disconnect")
        webSocket = null
        reconnectAttempt.set(0)
    }

    fun release() {
        disconnect()
        ioExecutor.shutdownNow()
    }

    /**
     * 更新数字人引擎引用。
     */
    fun updateEngine(newEngine: DigitalHumanEngine?) {
        engine = newEngine
        Log.d(TAG, "digital human engine reference updated: ${newEngine != null}")
    }

    val isConnected: Boolean get() = connected.get()
    fun getClientId(): String = clientId

    // ── 连接管理 ──

    /**
     * 注册网络状态监听，网络恢复时立即触发重连。
     * 不等 OkHttp ping 超时（最长 30s），提升断网恢复后的响应速度。
     */
    private fun registerNetworkCallback() {
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm == null) return

            // 先取消旧的监听，防止重复注册
            unregisterNetworkCallback()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // 网络恢复 → 立即尝试重连（只触发一次）
                    Log.d(TAG, "network available, triggering reconnect")
                    ioExecutor.execute {
                        if (shouldReconnect.get() && !connected.get()) {
                            Log.d(TAG, "reconnecting due to network available")
                            doConnect()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "network lost")
                }
            }
            networkCallback = callback

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            Log.d(TAG, "network callback registered")
        } catch (e: Exception) {
            Log.w(TAG, "register network callback failed: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            val cb = networkCallback ?: return
            networkCallback = null
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
            Log.d(TAG, "network callback unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "unregister network callback failed: ${e.message}")
        }
    }

    private fun doConnect() {
        if (!shouldReconnect.get()) return

        try {
            var wsUrl = backendBaseUrl
                .replace("http://", "ws://")
                .replace("https://", "wss://")
                .trimEnd('/')
                .removeSuffix("/api/v1")
                .removeSuffix("/api")
                .removeSuffix("/v1") + "/ws/push"

            Log.d(TAG, "connecting to $wsUrl")

            val request = Request.Builder()
                .url(wsUrl)
                .build()

            webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d(TAG, "connected")
                    connected.set(true)
                    reconnectAttempt.set(0)
                    callback.onPushWsStateChanged(true)
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handleTextMessage(text)
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    handleBinaryMessage(bytes.toByteArray())
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "connection failure: ${t.message}")
                    connected.set(false)
                    stopStreaming()
                    callback.onPushWsStateChanged(false)
                    scheduleReconnect()
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "closed: $code $reason")
                    connected.set(false)
                    stopStreaming()
                    callback.onPushWsStateChanged(false)
                    scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "connect error: ${e.message}")
            connected.set(false)
            callback.onPushWsStateChanged(false)
            scheduleReconnect()
        }
    }

    // ── 消息处理 ──

    private fun handleTextMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "connected" -> {
                    clientId = json.optString("client_id", "")
                    Log.i(TAG, "push ws ready: client_id=$clientId")
                }
                "tts_speak_start" -> handleTtsSpeakStart(json)
                "tts_speak_done" -> handleTtsSpeakDone(json)
                "tts_speak" -> handleTtsSpeakLegacy(json)
                "alert" -> {
                    val alertData = json.optJSONObject("data")
                    if (alertData != null) {
                        callback.onAlertReceived(alertData)
                    }
                }
                "inquiry_listen" -> handleInquiryListen(json)
                "pong" -> { /* 心跳响应 */ }
                else -> Log.d(TAG, "unhandled text type: ${json.optString("type")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "text message parse error: ${e.message}")
        }
    }

    private fun handleBinaryMessage(data: ByteArray) {
        if (data.size < 4) {
            Log.w(TAG, "binary message too short (< 4 bytes), ignore")
            return
        }

        val chunkIndex = ((data[0].toInt() and 0xFF) shl 24) or
                ((data[1].toInt() and 0xFF) shl 16) or
                ((data[2].toInt() and 0xFF) shl 8) or
                (data[3].toInt() and 0xFF)
        val pcmData = data.copyOfRange(4, data.size)

        Log.d(TAG, "binary chunk #$chunkIndex (${pcmData.size} bytes)")

        synchronized(chunkLock) {
            chunkBuffer[chunkIndex] = pcmData
            chunkReceivedCount++
        }
    }

    private fun handleTtsSpeakStart(json: JSONObject) {
        stopStreaming()
        synchronized(chunkLock) {
            chunkBuffer.clear()
            chunkReceivedCount = 0
            totalChunksExpected = json.optInt("total_chunks", 0)
        }
        streamingText = json.optString("text", "")
        streamingSampleRate = json.optInt("sample_rate", 24000)
        Log.i(TAG, "tts_speak_start: '$streamingText' ($totalChunksExpected chunks, ${streamingSampleRate}Hz)")
    }

    private fun handleTtsSpeakDone(json: JSONObject) {
        val expected = json.optInt("total_chunks", 0)
        val actual = synchronized(chunkLock) { chunkReceivedCount }
        Log.i(TAG, "tts_speak_done: received $actual / $expected chunks")

        if (!streamingActive.get()) {
            startBufferedPlayback()
        }
    }

    /**
     * 处理跌倒主动询问监听指令。
     * 后端发送此消息表示数字人刚播报了跌倒询问 TTS，Android 应在播报完成后自动启麦。
     */
    private fun handleInquiryListen(json: JSONObject) {
        val text = json.optString("text", "")
        val deviceId = json.optString("device_id", "")
        val timeoutSeconds = json.optInt("timeout_seconds", 60)
        Log.i(TAG, "inquiry_listen: text='${text.take(40)}...', deviceId=$deviceId, timeout=${timeoutSeconds}s")
        callback.onInquiryListen(text, deviceId, timeoutSeconds)
    }

    private fun startBufferedPlayback() {
        if (streamingActive.getAndSet(true)) return

        ioExecutor.execute {
            try {
                val engine = engine
                if (engine == null || !engine.isReady) {
                    Log.w(TAG, "buffered playback: digital human engine not ready, skipping")
                    resetStreamState()
                    return@execute
                }

                // VideoAvatarEngineImpl 将 pushPcm/stopPush 解释为"向后端发送 speak 请求生成视频"，
                // 而非本地播放音频。推送 TTS（告警播报、跌倒询问）不需要生成视频，
                // 跳过 push 避免触发不必要的视频生成。
                if (engine is com.yuanshi.avatar.engine.VideoAvatarEngineImpl) {
                    Log.w(TAG, "buffered playback: VideoAvatarEngine does not support push playback, skipping")
                    resetStreamState()
                    return@execute
                }

                val srcSampleRate = streamingSampleRate

                // 等所有分块到齐
                val totalChunks = synchronized(chunkLock) { totalChunksExpected }
                val received = synchronized(chunkLock) { chunkReceivedCount }
                if (received < totalChunks) {
                    var waited = 0
                    while (waited < 2000) {
                        Thread.sleep(50)
                        waited += 50
                        val now = synchronized(chunkLock) { chunkReceivedCount }
                        if (now >= totalChunks) break
                    }
                }

                // 拼装完整 PCM
                val fullPcm24k = synchronized(chunkLock) {
                    val totalBytes = chunkBuffer.entries
                        .sortedBy { it.key }
                        .sumOf { it.value.size }
                    val buffer = ByteArray(totalBytes)
                    var offset = 0
                    for (i in 0 until totalChunksExpected) {
                        val chunkData = chunkBuffer[i] ?: continue
                        System.arraycopy(chunkData, 0, buffer, offset, chunkData.size)
                        offset += chunkData.size
                    }
                    buffer
                }

                Log.d(TAG, "buffered playback: assembled ${fullPcm24k.size} bytes PCM (${srcSampleRate}Hz)")

                // 下采样 24kHz → 16kHz
                val pcm16k = if (srcSampleRate == 16000) {
                    fullPcm24k
                } else {
                    PcmResampler.downsamplePcm16BitMono(fullPcm24k, srcSampleRate, 16000)
                }

                Log.d(TAG, "buffered playback: ${pcm16k.size} bytes after downsampling to 16kHz")

                // 推送给 DUIX
                engine.startPush()
                try {
                    var cursor = 0
                    while (cursor < pcm16k.size) {
                        val end = minOf(cursor + PUSH_FRAME_SIZE, pcm16k.size)
                        engine.pushPcm(pcm16k.copyOfRange(cursor, end))
                        cursor = end
                    }
                } finally {
                    engine.stopPush()
                }

                Log.i(TAG, "buffered playback finished: '$streamingText'")
            } catch (e: Exception) {
                Log.e(TAG, "buffered playback error: ${e.message}")
            } finally {
                resetStreamState()
            }
        }
    }

    private fun resetStreamState() {
        streamingActive.set(false)
        streamingText = ""
        streamingSampleRate = 24000
        synchronized(chunkLock) {
            chunkBuffer.clear()
            chunkReceivedCount = 0
            totalChunksExpected = 0
        }
    }

    private fun stopStreaming() {
        streamingActive.set(false)
        resetStreamState()
    }

    // ── TTS 旧版兼容 ──

    private fun handleTtsSpeakLegacy(json: JSONObject) {
        val text = json.optString("text", "")
        val audioBase64 = json.optString("audio_base64", "")
        val sampleRate = json.optInt("sample_rate", 24000)

        if (audioBase64.isBlank()) {
            Log.w(TAG, "tts_speak (legacy): empty audio_base64")
            return
        }

        Log.i(TAG, "tts_speak (legacy): '$text' (${audioBase64.length} base64 chars, ${sampleRate}Hz)")

        ioExecutor.execute {
            try {
                val engine = engine
                if (engine == null || !engine.isReady) {
                    Log.w(TAG, "tts_speak (legacy): digital human engine not ready, skipping")
                    return@execute
                }

                val pcmSrc = Base64.decode(audioBase64, Base64.DEFAULT)
                if (pcmSrc.isEmpty()) {
                    Log.w(TAG, "tts_speak (legacy): decoded audio empty")
                    return@execute
                }

                val pcm16k = if (sampleRate == 16000) pcmSrc
                else PcmResampler.downsamplePcm16BitMono(pcmSrc, sampleRate, 16000)

                Log.d(TAG, "tts_speak (legacy): decoded ${pcmSrc.size} bytes → ${pcm16k.size} bytes (16kHz)")

                // VideoAvatarEngineImpl 不支持推流式本地播放，跳过
                if (engine is com.yuanshi.avatar.engine.VideoAvatarEngineImpl) {
                    Log.w(TAG, "tts_speak (legacy): VideoAvatarEngine does not support push playback, skipping")
                    return@execute
                }

                engine.startPush()
                try {
                    var cursor = 0
                    while (cursor < pcm16k.size) {
                        val end = minOf(cursor + PUSH_FRAME_SIZE, pcm16k.size)
                        engine.pushPcm(pcm16k.copyOfRange(cursor, end))
                        cursor = end
                    }
                } finally {
                    engine.stopPush()
                }

                Log.d(TAG, "tts_speak (legacy): playback finished (${pcm16k.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "tts_speak (legacy) playback error: ${e.message}")
            }
        }
    }

    // ── 重连 ──

    private fun scheduleReconnect() {
        if (!shouldReconnect.get()) return

        val attempt = reconnectAttempt.incrementAndGet()
        val delayMs = minOf(
            RECONNECT_DELAY_MS * (1L shl minOf(attempt - 1, 3)),
            MAX_RECONNECT_DELAY_MS
        )
        Log.d(TAG, "scheduling reconnect #$attempt in ${delayMs}ms")

        ioExecutor.schedule({
            if (shouldReconnect.get() && !connected.get()) {
                Log.d(TAG, "reconnecting... (attempt #$attempt)")
                doConnect()
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }
}
