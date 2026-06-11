package com.yuanshi.avatar.engine

import android.view.ViewGroup

/**
 * 数字人引擎抽象接口
 *
 * 定义数字人渲染引擎的核心操作，当前实现为 DUIX SDK。
 * 后续如需切换引擎（如自研引擎），只需新增实现类即可，
 * 上层业务代码无需修改。
 */
interface DigitalHumanEngine {

    interface Callback {
        fun onReady(motions: List<String>)
        fun onInitError(message: String)
        fun onAudioPlayStart()
        fun onAudioPlayEnd()
        fun onAudioPlayError(message: String)
        fun onMotionStart()
        fun onMotionEnd()
    }

    /**
     * 引擎是否就绪
     */
    val isReady: Boolean

    /**
     * 挂载渲染视图
     */
    fun attach(container: ViewGroup, modelPath: String, callback: Callback)

    /**
     * 初始化引擎
     * @param modelPath 数字人模型文件路径
     * @param licensePath 授权文件路径（可选）
     * @return 初始化是否成功
     */
    fun init(modelPath: String, licensePath: String = ""): Boolean

    /**
     * 加载数字人模型
     * @param modelDir 模型文件所在目录
     * @return 加载是否成功
     */
    fun loadModel(modelDir: String): Boolean

    /**
     * 开始推送音频（准备播放）
     * 必须在 pushPcm 之前调用
     */
    fun startPush()

    /**
     * 推送 PCM 音频数据到引擎
     * 引擎会自动驱动数字人口型同步
     * @param pcmData 16kHz 16-bit mono PCM 数据
     */
    fun pushPcm(pcmData: ByteArray)

    /**
     * 停止推送音频
     * 在全部音频推送完成后调用
     */
    fun stopPush()

    /**
     * 停止音频播放并清空缓冲区
     */
    fun stopAudio()

    /**
     * 设置数字人动作/动画
     * @param motionName 动作名称
     * @param now 是否立即播放
     */
    fun setMotion(motionName: String, now: Boolean = true)

    /**
     * 播放随机动作
     */
    fun startRandomMotion(loop: Boolean = true)

    /**
     * 播放本地音频文件
     */
    fun playAudio(filePath: String)

    /**
     * 获取当前模型支持的动作列表
     */
    fun getSupportedMotions(): List<String>

    /**
     * 设置音量
     * @param volume 0.0 ~ 1.0
     */
    fun setVolume(volume: Float)

    /**
     * 释放引擎资源
     */
    fun release()

    /**
     * 请求渲染刷新
     */
    fun requestRender()

    /**
     * 获取引擎版本
     */
    fun getVersion(): String
}
