package com.yuanshi.avatar.engine

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.PI

/**
 * MNN TaoAvatar 引擎实现
 *
 * 本地 MNN 推理的数字人引擎，使用 3D Gaussian Splatting 渲染真人形象。
 * 当前为架构就绪实现：
 * - 渲染层使用 SurfaceView + Canvas 绘制占位动画
 * - 收到音频时显示波形反馈
 * - 模型加载、音频推流、生命周期接口完备
 *
 * 接入实际 MNN TaoAvatar SDK 时替换以下部分：
 * 1. SurfaceView → GLSurfaceView / MNN 推理渲染表面
 * 2. pushPcm() → 送入 MNN 音频驱动模块作口型同步
 * 3. init/loadModel → 加载 MNN TaoAvatar 模型文件 (.mnn)
 */
class MnnTaoAvatarEngineImpl(private val context: Context) : DigitalHumanEngine {

    private var surfaceView: AudioVisualizerView? = null
    private var callback: DigitalHumanEngine.Callback? = null

    @Volatile
    private var initialized = false

    /** 模型文件目录（init 时传入） */
    private var modelDir: String = ""

    /** 上次收到音频的时间戳（用于波形衰减） */
    @Volatile
    private var lastAudioTimeMs: Long = 0L

    override val isReady: Boolean get() = initialized

    override fun attach(
        container: ViewGroup,
        modelPath: String,
        callback: DigitalHumanEngine.Callback
    ) {
        this.callback = callback
        container.removeAllViews()

        val visualizer = AudioVisualizerView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(visualizer)
        surfaceView = visualizer

        Log.i(TAG, "attach: MNN TaoAvatar 引擎已挂载")
        callback.onReady(emptyList())
    }

    override fun init(modelPath: String, licensePath: String): Boolean {
        modelDir = modelPath
        Log.i(TAG, "init: 模型路径=$modelPath")
        // ── 接入点 ──
        // val config = MNNNetConfig()
        // MNNNetInstance.createFromFile(modelPath, config)
        initialized = true
        return true
    }

    override fun loadModel(modelDir: String): Boolean {
        Log.i(TAG, "loadModel: $modelDir")
        return true
    }

    override fun startPush() {
        // ── 接入点 ──
        // audioProcessor.reset()
    }

    override fun pushPcm(pcmData: ByteArray) {
        // ── 接入点 ──
        // audioProcessor.feed(ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer())
        // 触发口型同步推理
        lastAudioTimeMs = System.currentTimeMillis()
    }

    override fun stopPush() {
        // ── 接入点 ──
        // audioProcessor.finish()
    }

    override fun stopAudio() {
        // 清空音频缓冲区
        lastAudioTimeMs = 0L
    }

    override fun setMotion(motionName: String, now: Boolean) {
        Log.i(TAG, "setMotion: $motionName — MNN TaoAvatar 由音频驱动，不支持预设动作")
    }

    override fun startRandomMotion(loop: Boolean) {
        // MNN TaoAvatar 不需要随机动作
    }

    override fun playAudio(filePath: String) {
        Log.w(TAG, "playAudio: 本地音频播放不支持，请通过 pushPcm 推送音频")
    }

    override fun getSupportedMotions(): List<String> = emptyList()

    override fun setVolume(volume: Float) {
        // 音量由外部音频播放器控制
    }

    override fun requestRender() {
        surfaceView?.invalidate()
    }

    override fun release() {
        initialized = false
        surfaceView?.stop()
        surfaceView = null
        callback = null
        Log.i(TAG, "release: 引擎已释放")
    }

    override fun getVersion(): String = "mnn-tao-avatar-phase5"

    // ── 音频可视化 View ──

    /**
     * 音频可视化 SurfaceView
     *
     * 占位期间绘制动态波形表示音频活动。
     * 实际 MNN 渲染管线接入后替换为 GLSurfaceView 或 MNN 自有渲染表面。
     */
    private inner class AudioVisualizerView(
        context: Context
    ) : SurfaceView(context), SurfaceHolder.Callback {

        private val drawThread = DrawThread()
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A1A2E")
        }
        private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4A90D9")
            style = Paint.Style.FILL
            alpha = 180
        }
        private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6C5CE7")
            style = Paint.Style.FILL
            alpha = 120
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            textAlign = Paint.Align.CENTER
            alpha = 160
        }
        private val textPaintSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            textAlign = Paint.Align.CENTER
            alpha = 100
        }

        /** 音频活动程度 0.0~1.0 */
        @Volatile
        var activityLevel: Float = 0f

        init {
            holder.addCallback(this)
        }

        fun stop() {
            drawThread.stopRunning()
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            drawThread.start(holder)
        }

        override fun surfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            drawThread.stopRunning()
        }

        /** 渲染线程 — 持续绘制占位动画 */
        private inner class DrawThread {
            private var running = AtomicBoolean(false)
            private var holder: SurfaceHolder? = null
            private var frameCount = 0L

            fun start(holder: SurfaceHolder) {
                this.holder = holder
                if (running.compareAndSet(false, true)) {
                    thread {
                        while (running.get()) {
                            draw()
                            frameCount++
                            Thread.sleep(33) // ~30fps
                        }
                    }
                }
            }

            fun stopRunning() {
                running.set(false)
            }

            private fun draw() {
                val h = holder ?: return
                val canvas = h.lockCanvas() ?: return
                try {
                    val w = canvas.width.toFloat()
                    val hh = canvas.height.toFloat()
                    val cx = w / 2f
                    val cy = hh / 2f
                    val now = System.currentTimeMillis()

                    // 计算音频活动衰减
                    val elapsed = now - lastAudioTimeMs
                    val activity = if (elapsed < 200) {
                        // 刚收到音频，显示波形
                        0.6f + 0.4f * sin(now.toDouble() / 100.0).toFloat().let { abs(it) }
                    } else {
                        // 衰减
                        (1.0f - (elapsed - 200).coerceAtMost(3000) / 3000f).coerceAtLeast(0f)
                    }
                    activityLevel = activity

                    // 背景
                    canvas.drawRect(0f, 0f, w, hh, bgPaint)

                    // 引擎状态
                    val status = if (activity > 0.1f) "SPEAKING" else "IDLE"
                    textPaint.alpha = if (activity > 0.1f) 220 else 120
                    canvas.drawText("MNN TaoAvatar", cx, cy - 80f, textPaint)
                    canvas.drawText("[$status]", cx, cy - 40f, textPaintSmall)

                    // 波形动画
                    val baseRadius = minOf(w, hh) * 0.25f
                    val pulseRadius = baseRadius + activity * 40f
                    accentPaint.alpha = (80 + activity * 100).toInt().coerceAtMost(200)
                    wavePaint.alpha = (100 + activity * 120).toInt().coerceAtMost(220)

                    // 外圈脉冲
                    canvas.drawCircle(cx, cy, pulseRadius, accentPaint)

                    // 内圆
                    val innerRadius = baseRadius * 0.5f + activity * 20f
                    canvas.drawCircle(cx, cy, innerRadius, wavePaint)

                    // 底部音频波形条
                    if (activity > 0.05f) {
                        val barCount = 24
                        val barW = w / barCount / 2f
                        val gap = barW * 0.5f
                        val totalW = (barW + gap) * barCount
                        val startX = cx - totalW / 2f
                        val baseY = cy + baseRadius + 60f

                        for (i in 0 until barCount) {
                            val barH = 4f + activity * 50f *
                                (0.5f + 0.5f * sin(
                                    now.toDouble() / 150.0 + i * 0.5
                                ).toFloat().let { abs(it) })
                            val bx = startX + i * (barW + gap)
                            wavePaint.alpha = (100 + activity * 120).toInt().coerceAtMost(220)
                            canvas.drawRect(bx, baseY - barH, bx + barW, baseY, wavePaint)
                        }
                    }

                    // 引擎信息
                    textPaintSmall.alpha = 80
                    canvas.drawText(
                        "Audio-driven real-time digital human",
                        cx, hh - 40f, textPaintSmall
                    )
                    canvas.drawText(
                        "Model: $modelDir",
                        cx, hh - 12f, textPaintSmall
                    )
                } finally {
                    h.unlockCanvasAndPost(canvas)
                }
            }

            private fun thread(block: () -> Unit) {
                Thread(block, "MnnTaoAvatarDraw").apply {
                    isDaemon = true
                    start()
                }
            }
        }
    }

    companion object {
        private const val TAG = "MnnTaoAvatarEngine"
    }
}
