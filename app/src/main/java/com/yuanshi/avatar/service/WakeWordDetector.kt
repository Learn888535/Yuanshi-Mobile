package com.yuanshi.avatar.service

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import com.yuanshi.avatar.ui.settings.VadSensitivity
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * WakeWordDetector — 基于后端 ASR 的触发词检测器
 *
 * 在后台运行 AudioRecord + VAD（语音活动检测），
 * 检测到人声后录制音频片段，发送到后端 ASR，
 * 如果识别结果包含唤醒词则回调通知上层。
 *
 * 优点：
 * - 无需任何第三方语音库（无额外依赖）
 * - 唤醒词任意自定义（后端 ASR 识别任何文本）
 * - 后端 ASR 已就绪，无需额外配置
 * - 适合中文唤醒词（如"原宝"），直接换文本即可
 *
 * 流程：
 *   VAD 检测人声 → 录制 ~1-5s 音频 → HTTP POST 后端/voice/asr-wake
 *   → ASR 识别 → 匹配唤醒词 → onWakeWordDetected()
 */
class WakeWordDetector(
    private val backendBaseUrl: String,
    private val vadSensitivity: VadSensitivity = VadSensitivity.NORMAL,
    private val callback: Callback
) {

    interface Callback {
        /** 唤醒词被检测到（纯唤醒词，无命令内容） */
        fun onWakeWordDetected(recognizedText: String)
        /** 唤醒词+命令一键说出（后端已直接处理 LLM+TTS，返回音频直接播放） */
        fun onWakeWordCommand(recognizedText: String, commandText: String,
                              responseText: String, audioBase64: String, sampleRate: Int)
        /** 检测器状态变更 */
        fun onWakeWordStateChanged(isListening: Boolean)
        /** 发生错误 */
        fun onWakeWordError(message: String)
        /** VAD 检测到人声，开始录音（用于 UI 反馈） */
        fun onWakeWordRecordingStarted()
        /** ASR 识别结果（用于调试反馈） */
        fun onWakeWordAsrResult(text: String, isMatch: Boolean)
    }

    companion object {
        private const val TAG = "WakeWordDetector"

        /** 采样率 */
        private const val SAMPLE_RATE = 16000

        /** 每帧 640 bytes = 20ms (16kHz 16-bit mono) */
        private const val FRAME_SIZE = 640

        /** 音节间最大停顿帧数（"原"→"宝"间隔不重置计数） */
        private const val SYLLABLE_HANGOVER_FRAMES = 2

        /** 最长录音帧数 (~3s，防止背景噪音持续时永不停录) */
        private const val MAX_RECORD_FRAMES = 150

        /** 背景采样帧数 (~2s) */
        private const val BG_SAMPLE_FRAMES = 100

        /** 唤醒词（含同音字元/原，适配 ASR 识别结果） */
        private val WAKE_WORDS = listOf("原宝", "原宝你好", "你好原宝", "小原", "小原宝",
                                        "元宝", "元宝你好", "你好元宝", "小元", "小元宝")
    }

    // ── VAD 灵敏度参数（根据 VadSensitivity 映射） ──

    /** 语音检测：连续多少帧认为是有效语音 */
    private val voiceFrameThreshold: Int = when (vadSensitivity) {
        VadSensitivity.LOW -> 12
        VadSensitivity.NORMAL -> 8
        VadSensitivity.HIGH -> 5
    }

    /** 录音结束：连续多少帧静音后停止 */
    private val silenceFrameThreshold: Int = when (vadSensitivity) {
        VadSensitivity.LOW -> 60
        VadSensitivity.NORMAL -> 50
        VadSensitivity.HIGH -> 35
    }

    /** 自适应阈值最低值（V2.2 调低的敏感值，更容易检测到语音） */
    private val minRmsThreshold: Float = when (vadSensitivity) {
        VadSensitivity.LOW -> 500f
        VadSensitivity.NORMAL -> 300f
        VadSensitivity.HIGH -> 200f
    }

    /** 自适应阈值最高值 */
    private val maxRmsThreshold: Float = when (vadSensitivity) {
        VadSensitivity.LOW -> 1500f
        VadSensitivity.NORMAL -> 1200f
        VadSensitivity.HIGH -> 1000f
    }

    // ── 状态 ──
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var isListening = false
    private var detectThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    // VAD 状态
    private var bgRmsSum = 0.0
    private var bgFrameCount = 0
    private var adaptiveThreshold = minRmsThreshold
    private var voiceFrameCount = 0
    private var silenceFrameCount = 0
    private var totalFrameCount = 0
    private var isRecording = false
    /** 录音开始时的总帧数（用于最长录音限制，不受背景噪音持续刷新影响） */
    private var recordingStartFrame = 0
    private var pcmBuffer = ByteArrayOutputStream()
    private var lastVoiceFrameCount = 0

    /** 连续静音帧数（用于持续背景噪音跟踪） */
    private var consecutiveSilenceFrames = 0
    /** 连续静音期间的 RMS 累积值（用于更新自适应阈值） */
    private var silenceRmsSum = 0.0
    /** 连续静音期间的有效采样帧数 */
    private var silenceRmsCount = 0
    /** 当前背景噪音 RMS 估计值（指数移动平均） */
    private var currentBgRms = minRmsThreshold.toDouble()

    // ── 公共方法 ──

    fun start() {
        if (isListening) return
        isListening = true
        callback.onWakeWordStateChanged(true)
        detectThread = Thread({ detectionLoop() }, "wakeword-detect")
        detectThread?.start()
        Log.i(TAG, "唤醒检测已启动")
    }

    fun stop() {
        isListening = false
        audioRecord?.apply {
            try { stop() } catch (_: Exception) {}
            try { release() } catch (_: Exception) {}
        }
        audioRecord = null
        detectThread = null
        resetVad()
        callback.onWakeWordStateChanged(false)
        Log.i(TAG, "唤醒检测已停止")
    }

    val isActive: Boolean get() = isListening

    // ── 检测循环 ──

    private fun detectionLoop() {
        val buffer = ByteArray(FRAME_SIZE)

        while (isListening) {
            try {
                // 1. 初始化 AudioRecord
                if (audioRecord == null) {
                    val minBuffer = AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuffer.coerceAtLeast(FRAME_SIZE * 2)
                    )
                }

                val record = audioRecord ?: continue
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Thread.sleep(500)
                    continue
                }

                // 2. 开始录音
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    try { record.startRecording() } catch (_: Exception) {}
                    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        Thread.sleep(500)
                        continue
                    }
                }

                // 3. 读取音频帧
                val bytesRead = record.read(buffer, 0, FRAME_SIZE)
                if (bytesRead <= 0) continue

                totalFrameCount++

                // 4. 计算 RMS 能量
                val rms = computeRms(buffer, bytesRead)

                // 5. 自适应阈值（前 100 帧采样背景噪音）
                if (bgFrameCount < BG_SAMPLE_FRAMES) {
                    bgRmsSum += rms
                    bgFrameCount++
                    if (bgFrameCount >= BG_SAMPLE_FRAMES) {
                        val bgAvg = (bgRmsSum / BG_SAMPLE_FRAMES).toFloat()
                        adaptiveThreshold = (bgAvg * 2 + 200).coerceIn(
                            minRmsThreshold,
                            maxRmsThreshold
                        )
                        Log.d(TAG, "背景噪音校准完成: avg=$bgAvg, threshold=$adaptiveThreshold")
                    }
                    continue
                }

                // 6. VAD 判断
                val isVoice = rms >= adaptiveThreshold

                if (isVoice) {
                    voiceFrameCount++
                    silenceFrameCount = 0
                    consecutiveSilenceFrames = 0
                    silenceRmsSum = 0.0
                    silenceRmsCount = 0
                    // 始终更新最后语音帧数（防止静音时 voiceFrameCount 被错误重置）
                    lastVoiceFrameCount = totalFrameCount
                    if (!isRecording && voiceFrameCount >= voiceFrameThreshold) {
                        // 语音足够长，开始录音
                        isRecording = true
                        recordingStartFrame = totalFrameCount
                        Log.d(TAG, "语音检测到，开始录音 (voiceFrameCount=$voiceFrameCount)")
                        callback.onWakeWordRecordingStarted()
                    }
                    // 始终缓冲语音帧（录音前也开始缓冲，确保 ASR 获得完整语音）
                    pcmBuffer.write(buffer, 0, bytesRead)
                } else {
                    silenceFrameCount++
                    if (isRecording) {
                        // 音节间容忍（"原"→"宝"之间的停顿）
                        if (totalFrameCount - lastVoiceFrameCount <= SYLLABLE_HANGOVER_FRAMES) {
                            // 还在音节间，继续录音
                            pcmBuffer.write(buffer, 0, bytesRead)
                        } else if (silenceFrameCount >= silenceFrameThreshold) {
                            // 静音超时，结束录音
                            Log.d(TAG, "静音超时，结束录音 (silenceFrames=$silenceFrameCount)")
                            processRecordedAudio()
                            resetVad()
                        } else {
                            // 静音中但还没超时，继续录
                            pcmBuffer.write(buffer, 0, bytesRead)
                        }
                    } else {
                        // 不在录音状态，重置语音计数（音节间容忍）
                        if (totalFrameCount - lastVoiceFrameCount > SYLLABLE_HANGOVER_FRAMES) {
                            voiceFrameCount = 0
                            // 同时重置缓冲，防止上次未达阈值的语音片段混入下次检测
                            pcmBuffer = ByteArrayOutputStream()
                        }
                        // 持续背景噪音跟踪：在长时间静音时更新自适应阈值
                        if (!isRecording && rms < adaptiveThreshold) {
                            consecutiveSilenceFrames++
                            silenceRmsSum += rms
                            silenceRmsCount++
                            // 每积累约 30 帧（~600ms）的连续静音，更新一次背景噪音估计
                            if (silenceRmsCount >= 30) {
                                val avgSilenceRms = silenceRmsSum / silenceRmsCount
                                // 指数移动平均：平滑更新背景噪音估计
                                if (currentBgRms <= 0) {
                                    currentBgRms = avgSilenceRms
                                } else {
                                    currentBgRms = currentBgRms * 0.85 + avgSilenceRms * 0.15
                                }
                                // 重新计算自适应阈值
                                val newThreshold = (currentBgRms * 2 + 200).toFloat()
                                    .coerceIn(minRmsThreshold, maxRmsThreshold)
                                // 阈值变化超过 10% 才实际更新，避免频繁抖动
                                if (kotlin.math.abs(newThreshold - adaptiveThreshold) > adaptiveThreshold * 0.1f) {
                                    Log.d(TAG, "背景噪音更新: bgRms=${"%.0f".format(currentBgRms)}, " +
                                            "threshold=${"%.0f".format(adaptiveThreshold)}→${"%.0f".format(newThreshold)}")
                                    adaptiveThreshold = newThreshold
                                }
                                silenceRmsSum = 0.0
                                silenceRmsCount = 0
                            }
                        }
                    }
                }

                // 最长录音限制（用录音开始帧数，防止持续背景噪音导致永不停录）
                if (isRecording && totalFrameCount - recordingStartFrame > MAX_RECORD_FRAMES) {
                    Log.d(TAG, "最长录音限制 (${MAX_RECORD_FRAMES} frames)，强制结束录音")
                    processRecordedAudio()
                    resetVad()
                }

            } catch (e: Exception) {
                if (isListening) {
                    Log.e(TAG, "detection loop error: ${e.message}")
                    Thread.sleep(200)
                }
            }
        }
    }

    // ── 录音处理 ──

    private fun processRecordedAudio() {
        isRecording = false
        val pcmData = pcmBuffer.toByteArray()
        if (pcmData.size < FRAME_SIZE * voiceFrameThreshold) {
            Log.d(TAG, "录音太短，忽略 (${pcmData.size} bytes)")
            return
        }

        Log.d(TAG, "录音完成: ${pcmData.size} bytes, 发送 ASR...")

        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("audio", "wakeword.pcm",
                    pcmData.toRequestBody("audio/pcm".toMediaType()))
                .addFormDataPart("language", "zh")
                .build()

            // 与 VoiceWebSocketClient 保持一致：从 BACKEND_BASE_URL 中剥离 /api/v1 前缀
            // 因为 BACKEND_BASE_URL 已包含 /api/v1（例: http://192.168.31.25:8000/api/v1）
            val baseUrl = backendBaseUrl
                .trimEnd('/')
                .removeSuffix("/api/v1")
                .removeSuffix("/api")
                .removeSuffix("/v1")
            val request = Request.Builder()
                .url("$baseUrl/api/v1/voice/asr-wake")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val json = JSONObject(body)
            val text = json.optString("text", "")
            val hasCommand = json.optBoolean("has_command", false)

            Log.d(TAG, "ASR result: '$text' hasCommand=$hasCommand")

            val isMatch = isWakeWordMatch(text)
            callback.onWakeWordAsrResult(text, isMatch)

            if (isMatch) {
                if (hasCommand) {
                    // 一键说出：后端已直接 LLM+TTS，返回音频播放
                    val commandText = json.optString("command_text", "")
                    val responseText = json.optString("response_text", "")
                    val audioBase64 = json.optString("audio_base64", "")
                    val sampleRate = json.optInt("sample_rate", 24000)
                    Log.i(TAG, "一键说出: 命令='$commandText', 回复='${responseText.take(40)}...'")
                    callback.onWakeWordCommand(text, commandText, responseText, audioBase64, sampleRate)
                } else {
                    Log.i(TAG, "唤醒词命中: '$text'")
                    callback.onWakeWordDetected(text)
                }
            } else {
                Log.d(TAG, "非唤醒词: '$text'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ASR request error: ${e.message}")
            callback.onWakeWordError("ASR请求失败: ${e.message}")
        }
    }

    /**
     * 唤醒词匹配（两级策略）
     * 1. 精确子串匹配
     * 2. 字符级匹配（解决同音字问题）
     */
    private fun isWakeWordMatch(asrText: String): Boolean {
        val text = asrText.trim().lowercase()
        if (text.isBlank()) return false

        // 1. 精确子串匹配
        for (word in WAKE_WORDS) {
            if (text.contains(word.lowercase())) return true
        }

        // 2. 字符级匹配（解决同音字问题）
        // ceil(len/2) = 一半以上字符匹配即可，适配 ASR 同音字（元/原）
        // 例如"元宝"→"原宝"：两个字匹配一个就算命中
        for (word in WAKE_WORDS) {
            val required = (word.length + 1) / 2  // ceil(len/2)
            var matchCount = 0
            for (ch in word) {
                if (text.contains(ch.lowercase())) {
                    matchCount++
                    if (matchCount >= required) return true
                }
            }
        }

        return false
    }

    // ── 工具 ──

    private fun computeRms(buffer: ByteArray, bytesRead: Int): Double {
        var sumSquares = 0.0
        val samples = bytesRead / 2
        for (i in 0 until samples) {
            val sample = ((buffer[i * 2].toInt() and 0xFF) or
                    (buffer[i * 2 + 1].toInt() shl 8)).toShort()
            sumSquares += sample.toDouble() * sample.toDouble()
        }
        return Math.sqrt(sumSquares / samples)
    }

    private fun resetVad() {
        voiceFrameCount = 0
        silenceFrameCount = 0
        totalFrameCount = 0
        isRecording = false
        lastVoiceFrameCount = 0
        recordingStartFrame = 0
        pcmBuffer = ByteArrayOutputStream()
        // 背景噪音跟踪状态：保留 currentBgRms 和 adaptiveThreshold 以便持续自适应
        consecutiveSilenceFrames = 0
        silenceRmsSum = 0.0
        silenceRmsCount = 0
    }
}
