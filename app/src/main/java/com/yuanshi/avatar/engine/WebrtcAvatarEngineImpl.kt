package com.yuanshi.avatar.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.yuanshi.avatar.BuildConfig as AppBuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 实时视频数字人引擎 — 通过 WebSocket 接收后端 MuseTalk 生成的视频帧
 *
 * 工作流程：
 * 1. attach() — 创建 TextureView 显示视频
 * 2. init() — 连接后端 MuseTalk 流式端点
 * 3. startPush() — 打开音频采集
 * 4. pushPcm() — 发送 PCM 音频到后端
 * 5. stopPush() — 完成发送
 * 6. 后端实时生成口型同步视频帧 → WebSocket → 解码 → TextureView 渲染
 *
 * 与 VideoAvatarEngineImpl 的区别：
 * - VideoAvatarEngineImpl: 发送完整音频 → 等待后端生成完整视频 → 播放 MP4
 * - WebrtcAvatarEngineImpl: 流式发送音频块 → 实时接收视频帧 → 逐帧渲染
 *   （延迟更小，体验更流畅）
 */
class WebrtcAvatarEngineImpl(private val context: Context) : DigitalHumanEngine {

    // ── 内部状态 ──

    private var textureView: TextureView? = null
    private var container: FrameLayout? = null
    /** 占位文字层（无视频时显示） */
    private var placeholderContainer: FrameLayout? = null
    private var callback: DigitalHumanEngine.Callback? = null
    private var webSocket: WebSocket? = null

    @Volatile
    private var sessionId: String = ""

    @Volatile
    private var connected = false

    /** 帧渲染队列（生产-消费模式） */
    private val frameQueue = ConcurrentLinkedQueue<Bitmap>()

    /** 渲染循环是否运行 */
    private val rendering = AtomicBoolean(false)

    /** 当前会话是否在推送音频 */
    @Volatile
    private var pushing = false

    /** AudioTrack 用于流式播放本地 PCM */
    private var audioTrack: AudioTrack? = null

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** WebSocket 客户端（连接后端流式端点） */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)  // 无超时（流式）
        .build()

    override val isReady: Boolean get() = true

    // ── 接口实现 ──

    override fun attach(
        container: ViewGroup,
        modelPath: String,
        callback: DigitalHumanEngine.Callback
    ) {
        this.callback = callback
        container.removeAllViews()

        val outer = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#1A1A2E"))
        }

        val tv = TextureView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        outer.addView(tv)
        textureView = tv

        // 占位文字层（无视频时显示）
        val placeholder = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = false
            val textView = TextView(context).apply {
                text = "实时视频数字人引擎\n等待视频流..."
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
            }
            val density = context.resources.displayMetrics.density
            val textLp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = (200 * density).toInt()  // 距底部 200dp，位于按钮区域上方
            }
            addView(textView, textLp)
        }
        outer.addView(placeholder)
        placeholderContainer = placeholder

        container.addView(outer)

        this.container = outer

        // 开始渲染循环
        startRendering()

        Log.i(TAG, "attach: 实时视频引擎已挂载")
    }

    override fun init(modelPath: String, licensePath: String): Boolean {
        Log.i(TAG, "init: 立即返回，后台创建会话...")

        // 像 DUIX 引擎一样：立即回调 onReady，不阻塞 UI
        mainHandler.post {
            callback?.onReady(emptyList())
        }

        // 后台静默创建会话（失败也不阻塞 init）
        ioExecutor.submit {
            try {
                val baseUrl = getBackendBaseUrl()
                val url = "$baseUrl/avatar/sessions"
                val request = Request.Builder()
                    .url(url)
                    .post("".toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    val json = JSONObject(body)
                    sessionId = json.optString("session_id", "")
                    connected = true
                    Log.i(TAG, "init: 会话创建成功 sessionId=$sessionId")
                } else {
                    Log.e(TAG, "init: 会话创建失败 code=${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "init: 请求异常", e)
            }
        }

        return true
    }

    override fun loadModel(modelDir: String): Boolean = true

    override fun startPush() {
        pushing = true
        // 初始化流式 AudioTrack
        val sampleRate = 16000
        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufSize > 0) {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBufSize * 4) // 4倍缓冲减少卡顿
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            audioTrack = track
        }
        showPlaceholderText("实时视频数字人引擎\n播报中...（无视频画面）")
        mainHandler.post {
            callback?.onAudioPlayStart()
        }
        Log.i(TAG, "startPush: AudioTrack streaming started")
    }

    override fun pushPcm(pcmData: ByteArray) {
        if (!pushing) return
        // 流式写入 AudioTrack，音频实时播放
        val track = audioTrack
        if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            try {
                track.write(pcmData, 0, pcmData.size)
            } catch (e: Exception) {
                Log.w(TAG, "pushPcm: write error: ${e.message}")
            }
        }
    }

    override fun stopPush() {
        pushing = false
        Log.i(TAG, "stopPush")
        // 关闭流式 AudioTrack
        val track = audioTrack
        if (track != null) {
            try {
                // 等待播放缓冲数据排空
                val pendingFrames = track.playbackHeadPosition
                val pendingSamples = (track.bufferSizeInFrames - pendingFrames).coerceAtLeast(0)
                if (pendingSamples > 0) {
                    val drainMs = pendingSamples * 1000L / 16000 + 100
                    Thread.sleep(drainMs.coerceAtMost(500))
                }
            } catch (_: Exception) {}
            stopAudioPlayback()
        }
        mainHandler.post {
            showPlaceholderText("实时视频数字人引擎\n等待视频流...")
            callback?.onAudioPlayEnd()
        }
    }

    override fun stopAudio() {
        pushing = false
        frameQueue.clear()
        stopAudioPlayback()
        webSocket?.close(1000, "stop audio")
        webSocket = null
    }

    override fun setMotion(motionName: String, now: Boolean) = Unit
    override fun startRandomMotion(loop: Boolean) = Unit

    override fun playAudio(filePath: String) {
        Log.w(TAG, "playAudio: 不支持本地音频播放，使用后端流式模式")
    }

    override fun getSupportedMotions(): List<String> = emptyList()
    override fun setVolume(volume: Float) = Unit
    override fun requestRender() = Unit

    override fun release() {
        pushing = false
        rendering.set(false)
        frameQueue.clear()
        stopAudioPlayback()
        webSocket?.close(1000, "release")
        webSocket = null
        ioExecutor.submit {
            if (sessionId.isNotBlank()) {
                try {
                    val baseUrl = getBackendBaseUrl()
                    val url = "$baseUrl/avatar/sessions/$sessionId"
                    httpClient.newCall(Request.Builder().url(url).delete().build()).execute()
                    Log.i(TAG, "release: 会话已关闭")
                } catch (e: Exception) {
                    Log.w(TAG, "release: 关闭会话异常", e)
                }
            }
            sessionId = ""
            connected = false
        }
        textureView = null
        container = null
        placeholderContainer = null
        callback = null
    }

    override fun getVersion(): String = "webrtc-avatar-impl-v1"

    // ── 渲染循环 ──

    private fun startRendering() {
        if (rendering.getAndSet(true)) return
        ioExecutor.submit {
            while (rendering.get()) {
                val bitmap = frameQueue.poll()
                if (bitmap != null) {
                    mainHandler.post {
                        renderFrame(bitmap)
                    }
                }
                Thread.sleep(33) // ~30fps
            }
        }
    }

    private var hasShownFirstFrame = false

    private fun renderFrame(bitmap: Bitmap) {
        val tv = textureView ?: return
        if (!tv.isAvailable) return

        val canvas = tv.lockCanvas() ?: return
        try {
            // 将 Bitmap 绘制到 TextureView
            val destRect = android.graphics.Rect(0, 0, tv.width, tv.height)
            canvas.drawBitmap(bitmap, null, destRect, null)
            // 第一次渲染到视频帧时隐藏占位文字
            if (!hasShownFirstFrame) {
                hasShownFirstFrame = true
                showPlaceholder(false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "renderFrame: ${e.message}")
        } finally {
            tv.unlockCanvasAndPost(canvas)
        }
    }

    /**
     * 接收一帧 JPEG 数据并加入渲染队列
     * 由外部（VoiceSessionManager 等）调用
     */
    fun onFrameReceived(jpegData: ByteArray) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
            if (bitmap != null) {
                frameQueue.offer(bitmap)
                // 限制队列大小，丢弃旧帧
                while (frameQueue.size > 5) {
                    frameQueue.poll()?.recycle()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "onFrameReceived: ${e.message}")
        }
    }

    // ── 占位文字控制 ──

    private fun showPlaceholder(show: Boolean) {
        placeholderContainer?.let {
            it.post {
                it.visibility = if (show) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showPlaceholderText(text: String) {
        placeholderContainer?.let { container ->
            container.post {
                val tv = container.getChildAt(0) as? TextView
                tv?.text = text
                container.visibility = View.VISIBLE
            }
        }
    }

    // ── 音频播放 ──

    /**
     * 停止音频播放（释放 AudioTrack）
     */
    private fun stopAudioPlayback() {
        audioTrack?.let { track ->
            try {
                track.pause()
                track.flush()
                track.release()
            } catch (_: Exception) {}
            audioTrack = null
        }
    }

    // ── WebSocket 流式连接 ──

    /**
     * 连接到后端的 MuseTalk 流式端点
     * 调用后，后端开始接收音频并返回视频帧
     */
    fun connectStream(streamSessionId: String): Boolean {
        val baseUrl = getBackendBaseUrl()
        val wsUrl = baseUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .trimEnd('/')
            .removeSuffix("/api/v1")
            .removeSuffix("/api") + "/api/v1/avatar/stream/$streamSessionId"

        Log.i(TAG, "connectStream: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        val connectedLatch = java.util.concurrent.CountDownLatch(1)
        val connectResult = AtomicBoolean(false)

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "stream WS connected")
                connectResult.set(true)
                connectedLatch.countDown()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleStreamMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "stream WS failure: ${t.message}")
                connectResult.set(false)
                connectedLatch.countDown()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "stream WS closed: $code $reason")
            }
        })

        try {
            connectedLatch.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.e(TAG, "connectStream timeout: ${e.message}")
        }

        return connectResult.get()
    }

    /**
     * 通过 WebSocket 发送音频数据到后端
     */
    fun sendAudio(audioData: ByteArray) {
        webSocket?.send(okio.ByteString.of(*audioData))
    }

    /**
     * 断开流式连接
     */
    fun disconnectStream() {
        webSocket?.close(1000, "client done")
        webSocket = null
    }

    private fun handleStreamMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "frame" -> {
                    val data = json.optString("data", "")
                    if (data.isNotBlank()) {
                        val jpegBytes = Base64.decode(data, Base64.DEFAULT)
                        onFrameReceived(jpegBytes)
                    }
                }
                "done" -> {
                    Log.i(TAG, "stream done")
                    mainHandler.post {
                        callback?.onAudioPlayEnd()
                    }
                }
                "error" -> {
                    val msg = json.optString("message", "unknown error")
                    Log.e(TAG, "stream error: $msg")
                    mainHandler.post {
                        callback?.onAudioPlayError(msg)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleStreamMessage: ${e.message}")
        }
    }

    // ── 辅助 ──

    private fun getBackendBaseUrl(): String {
        val prefs = context.getSharedPreferences("duix_settings", Context.MODE_PRIVATE)
        // 优先读取设置界面保存的 key "backend_url"（ListeningSettingsDialog.KEY_BACKEND_URL）
        // 兼容旧版 key "backend_base_url"
        val fromDialog = prefs.getString("backend_url", "") ?: ""
        val saved = if (fromDialog.isNotBlank()) fromDialog
            else prefs.getString("backend_base_url", "") ?: ""
        return saved.ifBlank { AppBuildConfig.BACKEND_BASE_URL }
    }

    companion object {
        private const val TAG = "WebrtcEngine"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** 扩展函数：创建 RequestBody */
private fun String.toRequestBody(contentType: okhttp3.MediaType): okhttp3.RequestBody {
    return okhttp3.RequestBody.create(contentType, this)
}