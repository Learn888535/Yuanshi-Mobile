package com.yuanshi.avatar.engine

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * 占位实现：服务端真人视频数字人引擎。
 * 当前仅做架构验证，不提供实际渲染。
 */
class VideoAvatarEngineStub(private val context: Context) : DigitalHumanEngine {

    private var placeholderView: View? = null
    private var callback: DigitalHumanEngine.Callback? = null

    override val isReady: Boolean get() = false

    override fun attach(container: ViewGroup, modelPath: String, callback: DigitalHumanEngine.Callback) {
        this.callback = callback
        container.removeAllViews()
        val tv = TextView(context).apply {
            text = "Video Avatar Engine (stub)"
            setTextColor(0xFF888888.toInt())
            textSize = 18f
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(tv)
        placeholderView = tv
        Log.i(TAG, "attach: stub engine ready")
        callback.onInitError("Video Avatar engine not implemented yet")
    }

    override fun init(modelPath: String, licensePath: String): Boolean {
        Log.i(TAG, "init stub (no-op)")
        return false
    }

    override fun loadModel(modelDir: String): Boolean = false

    override fun startPush() {}
    override fun pushPcm(pcmData: ByteArray) {}
    override fun stopPush() {}
    override fun stopAudio() {}
    override fun setMotion(motionName: String, now: Boolean) {}
    override fun startRandomMotion(loop: Boolean) {}
    override fun playAudio(filePath: String) {}
    override fun getSupportedMotions(): List<String> = emptyList()
    override fun setVolume(volume: Float) {}
    override fun requestRender() {}
    override fun release() {
        placeholderView = null
        callback = null
    }
    override fun getVersion(): String = "video-avatar-stub"

    companion object {
        private const val TAG = "VideoAvatarStub"
    }
}
