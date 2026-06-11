package com.yuanshi.avatar.engine

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.MediaPlayer.OnCompletionListener
import android.media.MediaPlayer.OnPreparedListener
import android.media.MediaPlayer.OnErrorListener
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import com.yuanshi.avatar.BuildConfig as AppBuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 视频数字人引擎 — 通过后端服务实现真人视频数字人
 *
 * 工作流程：
 * 1. attach() — 在容器中创建 TextureView + 占位文字
 * 2. init() — 创建后端视频数字人会话
 * 3. startPush() — 清除 PCM 缓冲区
 * 4. pushPcm() — 累积 PCM 数据
 * 5. stopPush() — 将文本/音频发送到后端 speak 端点
 *    → 后端返回视频 URL → 引擎自动播放视频
 * 6. release() — 关闭后端会话，释放 MediaPlayer
 */
class VideoAvatarEngineImpl(private val context: Context) : DigitalHumanEngine {

    /** 由 VoiceSessionManager 在 startPush 前设置，用于 speak 端点的文字 */
    var pendingReplyText: String = ""

    /** 外层容器（内含 TextureView + 占位层） */
    private var container: FrameLayout? = null
    /** 视频播放表面 */
    private var textureView: TextureView? = null
    /** 占位文字（无视频时显示） */
    private var placeholderContainer: FrameLayout? = null

    private var callback: DigitalHumanEngine.Callback? = null

    @Volatile
    private var sessionId: String? = null

    private var mediaPlayer: MediaPlayer? = null
    @Volatile
    private var isPlaying = false

    /** PCM 缓冲区 */
    private val pcmBuffer = mutableListOf<ByteArray>()

    /** 用于普通请求（会话创建等） */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)  // 15秒整体超时（必须先于UI的20秒超时触发）
        .build()

    /** 用于 speak 请求（MuseTalk/Wav2Lip 生成视频需要时间） */
    private val speakHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)  // 2分钟（原5分钟，减少等待时间）
        .callTimeout(130, java.util.concurrent.TimeUnit.SECONDS)  // 整体超时略大于 readTimeout
        .build()

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override val isReady: Boolean get() = true

    override fun attach(
        container: ViewGroup,
        modelPath: String,
        callback: DigitalHumanEngine.Callback
    ) {
        this.callback = callback
        container.removeAllViews()

        // 外层 FrameLayout
        val outer = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#1A1A2E"))
        }

        // TextureView（视频播放）
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
            val textView = android.widget.TextView(context).apply {
                text = "视频数字人引擎\n等待说话..."
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = android.view.Gravity.CENTER
            }
            addView(textView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            ))
        }
        outer.addView(placeholder)
        placeholderContainer = placeholder

        container.addView(outer)
        this.container = outer

        Log.i(TAG, "attach: 视频数字人引擎已挂载")
    }

    override fun init(modelPath: String, licensePath: String): Boolean {
        Log.i(TAG, "init: 立即返回，后台创建会话...")

        // 像 DUIX 引擎一样：立即回调 onReady，不阻塞 UI
        mainHandler.post {
            callback?.onReady(emptyList())
        }

        // 后台静默创建会话（失败也不阻塞 init）
        executor.submit {
            try {
                createSession()
            } catch (e: Throwable) {
                Log.w(TAG, "init: 后台创建会话失败，stopPush 时会重试", e)
            }
        }

        return true
    }

    override fun loadModel(modelDir: String): Boolean = true

    override fun startPush() {
        pcmBuffer.clear()
    }

    override fun pushPcm(pcmData: ByteArray) {
        pcmBuffer.add(pcmData)
    }

    override fun stopPush() {
        // 防御性检查：如果没有回复文字且没有 PCM 数据，跳过 speak 请求。
        // 这防止了在后端交互失败（未收到 tts_start）时使用默认文字生成视频，
        // 避免 TOGGLE 模式下出现重复"你好，欢迎使用智能关怀系统"视频的无限循环。
        if (pendingReplyText.isBlank() && pcmBuffer.isEmpty()) {
            Log.i(TAG, "stopPush: skipped (no reply text and no PCM data)")
            showPlaceholderText("未获取到回复内容")
            return
        }

        val sid = sessionId ?: run {
            // 会话还没创建，先尝试创建（同步，最多等10秒）
            Log.i(TAG, "stopPush: 会话未就绪，立即创建...")
            val created = ensureSession()
            if (!created) {
                Log.w(TAG, "stopPush: 创建会话失败")
                pcmBuffer.clear()
                showPlaceholderText("无法连接后端服务器\n请检查网络和后端地址")
                return
            }
            // 重新读取 sessionId
            sessionId ?: run {
                pcmBuffer.clear()
                return
            }
        }

        // 记录 PCM 数据量用于日志（实际不发送，后端会根据 text 重新生成 TTS）
        val pcmSize = pcmBuffer.sumOf { it.size }
        pcmBuffer.clear()

        val textToSpeak = pendingReplyText.ifBlank { "你好，欢迎使用智能关怀系统" }
        Log.i(TAG, "stopPush: 请求视频生成, text='${textToSpeak.take(40)}', pcm_size=${pcmSize}")

        // 显示更友好的状态信息
        val displayText = if (textToSpeak.length > 20) {
            textToSpeak.take(20) + "..."
        } else {
            textToSpeak
        }
        showPlaceholderText("正在生成视频...\n\"${displayText}\"")
        val requestStartMs = System.currentTimeMillis()
        executor.submit {
            try {
                val baseUrl = getBackendBaseUrl()
                val jsonBody = JSONObject().apply {
                    put("text", textToSpeak)
                }
                val url = "$baseUrl/avatar/sessions/$sid/speak"
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = speakHttpClient.newCall(request).execute()
                val elapsed = System.currentTimeMillis() - requestStartMs
                val respBody = response.body?.string()
                if (response.isSuccessful && respBody != null) {
                    val json = JSONObject(respBody)
                    val videoUrl = json.optString("video_url", "")
                    if (videoUrl.isNotBlank() && videoUrl != "null") {
                        Log.i(TAG, "stopPush: 获取到视频 URL: $videoUrl (${elapsed}ms)")
                        playVideo(videoUrl)
                    } else {
                        Log.i(TAG, "stopPush: 后端未返回视频 URL（mock 模式）")
                        showPlaceholderText("语音播报中...（后端未配置视频引擎）")
                    }
                } else {
                    Log.w(TAG, "stopPush: speak 失败 code=${response.code} body=${respBody?.take(100)}")
                    showPlaceholderText("视频生成失败: HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "stopPush: 请求异常", e)
                showPlaceholderText("视频生成异常: ${e.message?.take(40) ?: "未知错误"}")
            }
        }
    }

    override fun stopAudio() {
        // 只停止视频播放，不清除 PCM 缓冲区。
        // PCM 缓冲区属于 push 生命周期（startPush→pushPcm→stopPush），
        // 不应在 stopAudio 中清除，否则 interrupt() 之后调用 stopPush()
        // 会因空缓冲区而生成错误的默认文字视频。
        stopVideo()
    }

    override fun setMotion(motionName: String, now: Boolean) = Unit
    override fun startRandomMotion(loop: Boolean) = Unit

    override fun playAudio(filePath: String) {
        Log.i(TAG, "playAudio: $filePath")
        playVideo("file://$filePath")
    }

    override fun getSupportedMotions(): List<String> = emptyList()
    override fun setVolume(volume: Float) = Unit
    override fun requestRender() = Unit

    override fun release() {
        stopVideo()
        executor.submit {
            val sid = sessionId
            if (sid != null) {
                try {
                    val baseUrl = getBackendBaseUrl()
                    val url = "$baseUrl/avatar/sessions/$sid"
                    httpClient.newCall(Request.Builder().url(url).delete().build()).execute()
                    Log.i(TAG, "release: 会话已关闭 sid=$sid")
                } catch (e: Exception) {
                    Log.w(TAG, "release: 关闭会话异常", e)
                }
            }
            sessionId = null
        }
        pcmBuffer.clear()
        container = null
        textureView = null
        placeholderContainer = null
        mediaPlayer = null
        callback = null
    }

    override fun getVersion(): String = "video-avatar-impl-with-playback"

    // ── 视频播放 ──

    private fun playVideo(videoUrl: String) {
        mainHandler.post {
            try {
                // 释放旧播放器
                stopVideo()
                showPlaceholder(false)

                val tv = textureView ?: return@post
                val url = if (videoUrl.startsWith("http") || videoUrl.startsWith("file://")) {
                    videoUrl
                } else if (videoUrl.startsWith("/static/")) {
                    // 静态文件挂载在 /static，不在 /api/v1 下
                    getBackendBaseUrl().trimEnd('/')
                        .removeSuffix("/api/v1")
                        .removeSuffix("/api") + videoUrl
                } else {
                    getBackendBaseUrl().trimEnd('/') + "/" + videoUrl.trimStart('/')
                }

                Log.i(TAG, "playVideo: $url")

                val mp = MediaPlayer().apply {
                    setOnPreparedListener { preparedMp ->
                        this@VideoAvatarEngineImpl.isPlaying = true
                        preparedMp.start()
                        Log.i(TAG, "playVideo: 开始播放")
                        mainHandler.post { callback?.onAudioPlayStart() }
                        // 视频尺寸已知后立即调整布局
                        val vw = preparedMp.videoWidth
                        val vh = preparedMp.videoHeight
                        if (vw > 0 && vh > 0) {
                            scaleTextureView(tv, vw, vh)
                        }
                    }
                    setOnVideoSizeChangedListener { _, width, height ->
                        if (width > 0 && height > 0) {
                            scaleTextureView(tv, width, height)
                        }
                    }
                    setOnCompletionListener {
                        this@VideoAvatarEngineImpl.isPlaying = false
                        Log.i(TAG, "playVideo: 播放完成")
                        showPlaceholder(true)
                        mainHandler.post { callback?.onAudioPlayEnd() }
                    }
                    setOnErrorListener { _, what, extra ->
                        this@VideoAvatarEngineImpl.isPlaying = false
                        Log.e(TAG, "playVideo: 错误 what=$what extra=$extra")
                        showPlaceholder(true)
                        mainHandler.post {
                            callback?.onAudioPlayError("MediaPlayer error: $what/$extra")
                        }
                        true
                    }
                }

                // 当 TextureView surface 可用时设置 Surface
                if (tv.isAvailable) {
                    mp.setSurface(Surface(tv.surfaceTexture))
                    mp.setDataSource(context, Uri.parse(url))
                    mp.prepareAsync()
                } else {
                    tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture, width: Int, height: Int
                        ) {
                            mp.setSurface(Surface(surface))
                            try {
                                mp.setDataSource(context, Uri.parse(url))
                                mp.prepareAsync()
                            } catch (e: Exception) {
                                Log.e(TAG, "playVideo: 设置数据源失败", e)
                            }
                        }
                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture, width: Int, height: Int
                        ) = Unit
                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = false
                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                    }
                }

                mediaPlayer = mp
            } catch (e: Exception) {
                Log.e(TAG, "playVideo: 异常", e)
                showPlaceholder(true)
            }
        }
    }

    private fun stopVideo() {
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.stop()
                }
                mp.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopVideo: 释放异常", e)
        }
        mediaPlayer = null
        isPlaying = false
    }

    private fun showPlaceholder(show: Boolean) {
        placeholderContainer?.let {
            it.post {
                it.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    /** 更新占位文字内容（显示错误/状态信息） */
    private fun showPlaceholderText(text: String) {
        placeholderContainer?.let { container ->
            container.post {
                val tv = container.getChildAt(0) as? android.widget.TextView
                tv?.text = text
                container.visibility = android.view.View.VISIBLE
            }
        }
    }

    /**
     * 缩放 TextureView 适配视频（FIT_CENTER）
     *
     * 通过调整 TextureView 自身的大小和位置来实现视频适配，
     * 避免使用 setTransform 矩阵（可能不稳定）。
     *
     * 策略：计算视频在容器内完整显示的最大尺寸，
     * 将 TextureView 设置为该尺寸并居中。
     */
    private fun scaleTextureView(tv: TextureView, videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        mainHandler.post {
            val container = tv.parent as? ViewGroup ?: return@post
            val containerW = container.width
            val containerH = container.height
            if (containerW <= 0 || containerH <= 0) return@post

            // 计算 fit-center 尺寸
            val scale = minOf(
                containerW.toFloat() / videoWidth,
                containerH.toFloat() / videoHeight
            )
            val newW = (videoWidth * scale).toInt()
            val newH = (videoHeight * scale).toInt()

            // 调整 TextureView 大小并居中，视频内容自然填满 TextureView
            val lp = tv.layoutParams
            lp.width = newW
            lp.height = newH
            if (lp is FrameLayout.LayoutParams) {
                lp.gravity = android.view.Gravity.CENTER
            }
            tv.layoutParams = lp

            // 清除矩阵变换，让视频内容自然填满 TextureView
            tv.setTransform(android.graphics.Matrix())

            Log.i(TAG, "scaleTextureView: ${videoWidth}x${videoHeight} -> ${newW}x${newH} in ${containerW}x${containerH}")
        }
    }

    // ── 辅助 ──

    /**
     * 创建后端会话（同步，最多等 ~10 秒）
     * 使用 HttpURLConnection 而非 OkHttp，超时机制更可靠
     */
    private fun createSession(): Boolean {
        val baseUrl = getBackendBaseUrl()
        Log.i(TAG, "createSession: $baseUrl")
        try {
            val url = java.net.URL("$baseUrl/avatar/sessions")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput = true
            conn.doInput = true

            // 发送空请求体
            conn.outputStream.write("".toByteArray(Charsets.UTF_8))
            conn.outputStream.flush()
            conn.outputStream.close()

            val responseCode = conn.responseCode
            val body = if (responseCode in 200..299) {
                conn.inputStream?.bufferedReader()?.readText() ?: ""
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            conn.disconnect()

            if (responseCode in 200..299 && body.isNotBlank()) {
                val json = JSONObject(body)
                val sid = json.optString("session_id")
                if (sid.isNotBlank()) {
                    sessionId = sid
                    Log.i(TAG, "createSession: 成功 sessionId=$sid")
                    return true
                }
            }
            Log.w(TAG, "createSession: 失败 code=$responseCode body=${body.take(100)}")
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "createSession: 超时", e)
        } catch (e: java.net.ConnectException) {
            Log.e(TAG, "createSession: 连接被拒绝", e)
        } catch (e: Throwable) {
            Log.e(TAG, "createSession: 异常", e)
        }
        return false
    }

    /**
     * 确保会话已创建，未创建则同步创建
     */
    private fun ensureSession(): Boolean {
        if (sessionId != null) return true
        return createSession()
    }

    private fun getBackendBaseUrl(): String {
        val prefs = context.getSharedPreferences("duix_settings", Context.MODE_PRIVATE)
        // 优先读取设置界面保存的 key "backend_url"（ListeningSettingsDialog.KEY_BACKEND_URL）
        // 兼容旧版 key "backend_base_url"（此引擎早期使用的 key）
        // 都为空时使用编译期默认值
        val fromDialog = prefs.getString("backend_url", "") ?: ""
        val saved = if (fromDialog.isNotBlank()) fromDialog
            else prefs.getString("backend_base_url", "") ?: ""
        return saved.ifBlank { AppBuildConfig.BACKEND_BASE_URL }
    }

    companion object {
        private const val TAG = "VideoAvatarEngine"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
