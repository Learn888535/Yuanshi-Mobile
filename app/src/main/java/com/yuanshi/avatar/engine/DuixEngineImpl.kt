package com.yuanshi.avatar.engine

import ai.guiji.duix.sdk.client.Constant
import ai.guiji.duix.sdk.client.DUIX
import ai.guiji.duix.sdk.client.loader.ModelInfo
import ai.guiji.duix.sdk.client.render.DUIXRenderer
import ai.guiji.duix.sdk.client.render.DUIXTextureView
import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.ViewGroup
import java.util.concurrent.Executors

/**
 * DUIX SDK 引擎实现
 *
 * 将 DigitalHumanEngine 接口映射到 DUIX SDK 的底层 API。
 * DUIX SDK 使用 NCNN 进行神经网络推理 + OpenGL 渲染，
 * 实现数字人的加载、渲染和口型同步。
 *
 * 注意：DUIX 实例通常在外部创建（构造时需传入 modelName、RenderSink、Callback），
 * 创建后通过 [setDuix] 注入到此实现中。
 */
class DuixEngineImpl(private val context: Context) : DigitalHumanEngine {

    private var duix: DUIX? = null
    private var textureView: DUIXTextureView? = null
    private var renderer: DUIXRenderer? = null
    private var callback: DigitalHumanEngine.Callback? = null
    private var supportedMotions: List<String> = emptyList()

    /** NCNN 冷启动预热 */
    private val warmupExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var warmupDone = false
    @Volatile
    private var isWarmupRunning = false

    override val isReady: Boolean
        get() = duix?.isReady ?: false

    override fun attach(container: ViewGroup, modelPath: String, callback: DigitalHumanEngine.Callback) {
        this.callback = callback

        // 安全校验：空 modelPath 会导致 DUIX SDK native 崩溃
        if (modelPath.isBlank()) {
            Log.e(TAG, "attach: modelPath is empty, cannot initialize DUIX")
            callback.onInitError("模型路径为空，无法初始化 DUIX 3D 数字人")
            return
        }

        container.removeAllViews()

        val view = DUIXTextureView(context).apply {
            setEGLContextClientVersion(GL_CONTEXT_VERSION)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            isOpaque = false
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val duixRenderer = DUIXRenderer(context, view)
        view.setRenderer(duixRenderer)
        view.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        container.addView(view)

        textureView = view
        renderer = duixRenderer
        duix = DUIX(context, modelPath, duixRenderer) { event, msg, info ->
            when (event) {
                Constant.CALLBACK_EVENT_INIT_READY -> {
                    val motions = extractMotions(info as? ModelInfo)
                    supportedMotions = motions
                    Log.i(TAG, "CALLBACK_EVENT_INIT_READY: $info")

                    // NCNN 冷启动预热：在回调给 CallActivity 之前，
                    // 后台推送 600ms 近静音 PCM 触发首次 ONNX 推理。
                    // 这样用户说第一句话时 NCNN 已完成初始化，嘴型同步立即生效。
                    isWarmupRunning = true
                    warmupExecutor.submit {
                        try {
                            Log.i(TAG, "warmup: 开始 NCNN 冷启动预热...")
                            duix?.startPush()
                            // 推送 6 × 100ms = 600ms 近静音 PCM
                            repeat(6) {
                                if (!isWarmupRunning) return@submit
                                duix?.pushPcm(WARMUP_SILENCE)
                            }
                            duix?.stopPush()
                            warmupDone = true
                            Log.i(TAG, "warmup: NCNN 预热完成")
                        } catch (e: Exception) {
                            Log.w(TAG, "warmup: 预热异常（不影响后续使用）", e)
                        } finally {
                            isWarmupRunning = false
                        }
                    }

                    callback.onReady(motions)
                }
                Constant.CALLBACK_EVENT_INIT_ERROR -> {
                    Log.e(TAG, "CALLBACK_EVENT_INIT_ERROR: $msg")
                    callback.onInitError(msg.orEmpty())
                }
                Constant.CALLBACK_EVENT_AUDIO_PLAY_START -> {
                    if (isWarmupRunning) {
                        Log.d(TAG, "忽略预热期的 AUDIO_PLAY_START")
                    } else {
                        callback.onAudioPlayStart()
                    }
                }
                Constant.CALLBACK_EVENT_AUDIO_PLAY_END -> {
                    if (isWarmupRunning) {
                        Log.d(TAG, "忽略预热期的 AUDIO_PLAY_END")
                    } else {
                        callback.onAudioPlayEnd()
                    }
                }
                Constant.CALLBACK_EVENT_AUDIO_PLAY_ERROR -> callback.onAudioPlayError(msg.orEmpty())
                Constant.CALLBACK_EVENT_MOTION_START -> callback.onMotionStart()
                Constant.CALLBACK_EVENT_MOTION_END -> callback.onMotionEnd()
            }
        }
    }

    override fun init(modelPath: String, licensePath: String): Boolean {
        return try {
            duix?.init()
            Log.i(TAG, "DUIX 引擎初始化成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "DUIX 引擎初始化失败: ${e.message}", e)
            false
        }
    }

    override fun loadModel(modelDir: String): Boolean {
        // DUIX SDK 通过 init() 自动从外部文件目录加载模型，无需单独加载
        Log.i(TAG, "loadModel: DUIX SDK 自动管理模型加载，忽略参数: $modelDir")
        return true
    }

    override fun startPush() {
        duix?.startPush()
    }

    override fun pushPcm(pcmData: ByteArray) {
        duix?.pushPcm(pcmData)
    }

    override fun stopPush() {
        duix?.stopPush()
    }

    override fun stopAudio() {
        duix?.stopAudio()
    }

    override fun setMotion(motionName: String, now: Boolean) {
        duix?.startMotion(motionName, now)
    }

    override fun startRandomMotion(loop: Boolean) {
        duix?.startRandomMotion(loop)
    }

    override fun playAudio(filePath: String) {
        duix?.playAudio(filePath)
    }

    override fun getSupportedMotions(): List<String> = supportedMotions

    fun setSupportedMotions(motions: List<String>) {
        supportedMotions = motions
    }

    override fun setVolume(volume: Float) {
        duix?.setVolume(volume)
    }

    override fun release() {
        isWarmupRunning = false
        warmupExecutor.shutdownNow()
        duix?.release()
        duix = null
        renderer = null
        textureView = null
        callback = null
        Log.i(TAG, "DUIX 引擎已释放")
    }

    override fun requestRender() {
        textureView?.requestRender()
    }

    override fun getVersion(): String {
        return duix?.let { "DUIX-SDK" } ?: "uninitialized"
    }

    private fun extractMotions(modelInfo: ModelInfo?): List<String> {
        if (modelInfo == null) return emptyList()
        return modelInfo.motionRegions
            .mapNotNull { it.name }
            .filter { it.isNotBlank() && it != "unknown" }
    }

    companion object {
        private const val TAG = "DuixEngine"
        private const val GL_CONTEXT_VERSION = 2

        /**
         * 预热用近静音 PCM（100ms @ 16kHz = 1600 bytes = 800 shorts）
         *
         * 使用 +/-1~3 的非零值而非全零，避免 NCNN 内部可能的静音帧跳过逻辑。
         * 此音量远低于人耳可感知阈值（-60dB 以下），播放时完全无声。
         */
        private val WARMUP_SILENCE: ByteArray = ByteArray(1600).apply {
            val random = java.util.Random()
            var i = 0
            while (i < size) {
                val sample = (random.nextInt(5) - 2).coerceIn(-3, 3).toShort()
                this[i] = (sample.toInt() and 0xFF).toByte()
                this[i + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
                i += 2
            }
        }
    }
}
