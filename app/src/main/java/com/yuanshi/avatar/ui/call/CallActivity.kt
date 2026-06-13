package com.yuanshi.avatar.ui.call

import com.yuanshi.avatar.BuildConfig as AppBuildConfig
import com.yuanshi.avatar.engine.DigitalHumanEngine
import com.yuanshi.avatar.engine.DigitalHumanEngineFactory
import com.yuanshi.avatar.engine.DigitalHumanEngineType
import com.yuanshi.avatar.engine.VideoAvatarEngineImpl
import com.yuanshi.avatar.service.RealtimeSessionManager
import com.yuanshi.avatar.service.ListeningMode
import com.yuanshi.avatar.service.RealtimeConfig
import com.yuanshi.avatar.service.RealtimeState
import com.yuanshi.avatar.service.DeviceController
import com.yuanshi.avatar.service.PushWebSocketClient
import com.yuanshi.avatar.service.WakeWordDetector
import com.yuanshi.avatar.service.PcmResampler
import com.yuanshi.avatar.R
import com.yuanshi.avatar.audio.AudioRecorder
import com.yuanshi.avatar.databinding.ActivityCallBinding
import com.yuanshi.avatar.ui.component.MotionAdapter
import com.yuanshi.avatar.ui.component.AudioRecordDialog
import com.yuanshi.avatar.ui.settings.ListeningSettingsDialog
import com.yuanshi.avatar.ui.settings.VadSensitivity
import com.yuanshi.avatar.util.StringUtils
import com.yuanshi.avatar.ui.base.BaseActivity
import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.media.AudioManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.util.Rational
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.bumptech.glide.Glide
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit


class CallActivity : BaseActivity() {

    companion object {
        const val GL_CONTEXT_VERSION = 2
    }

    private var modelUrl = ""
    private var debug = false
    private var mMessage = ""

    // WebSocket 模式开关（true=使用 WebSocket 双向通信，false=使用 HTTP 流式）
    private val useWebSocketMode: Boolean = true

    @SuppressLint("SetTextI18n")
    private fun applyMessage(msg: String){
        if (debug){
            runOnUiThread {
                binding.tvDebug.visibility = View.VISIBLE
                if (mMessage.length > 10000){
                    mMessage = ""
                }
                mMessage = "${StringUtils.dateToStringMS4()} $msg\n$mMessage"
                binding.tvDebug.text = mMessage
            }
        }
    }

    /**
     * 更新底部状态提示（tvAITips），始终可见（不依赖 debug 开关）。
     * 在录音状态切换、处理阶段变更时调用，帮助用户了解当前系统状态。
     */
    private fun updateListeningStatus(text: String) {
        runOnUiThread {
            binding.tvAITips.text = text
            binding.tvAITips.visibility = android.view.View.VISIBLE
        }
    }

    private lateinit var binding: ActivityCallBinding
    private var digitalHumanEngine: DigitalHumanEngine? = null
    private var supportedMotions: List<String> = emptyList()
    private lateinit var realtimeManager: RealtimeSessionManager
    private var deviceController: DeviceController? = null

    // ===== 录音模式 =====
    private var listeningMode: ListeningMode = ListeningMode.TOGGLE
    private var vadSensitivity: VadSensitivity = VadSensitivity.NORMAL
    private var isListening: Boolean = false          // TOGGLE 模式标志
    private var isSystemSpeaking: Boolean = false     // DUIX 正在播放 TTS，此时不检测语音
    private var audioRecorder: AudioRecorder? = null  // 直接录音（不经过 Dialog）
    private var vadSilentStartMs: Long = 0L           // VAD 无声起始时间
    private var vadLastLogMs: Long = 0L               // VAD 上次日志时间
    private var vadSpeechFrames: Int = 0              // 连续有语音帧数（要求 ≥5 才视为有效语音）
    private var vadIgnoreUntilMs: Long = 0L           // 在此时间之前忽略 VAD（录音刚启动时的回声/噪音）
    private var vadBackgroundRms: Double = 0.0        // 背景噪音 RMS（ignore 期间采样计算）
    private var vadBackgroundSamples: Int = 0         // 背景 RMS 采样计数
    private var vadMusicStartMs: Long = 0L            // 持续音乐检测起始时间（超过5秒无语音则强制停止）
    private var recordingStartMs: Long = 0L           // 录音开始时间（用于最大时长检查）
    private var pendingRestart: Runnable? = null      // 待处理的录音重启（用于取消）
    private var audioManager: AudioManager? = null    // 音频管理器（用于音频焦点/闪避）
    private var isSwitchingMode: Boolean = false      // 正在切换模式时阻止 onFinish 启动新会话
    @Volatile
    private var isStreamingMode: Boolean = false      // 流式录音模式（WebSocket 边录边发）
    private var wakeWordDetector: WakeWordDetector? = null  // 唤醒词检测器
    private var androidTts: TextToSpeech? = null            // 唤醒确认语音播报
    private var pushWsClient: PushWebSocketClient? = null   // 推送 WebSocket 客户端（接收告警/数字人语音）
    @Volatile
    private var hasPendingInquiry: Boolean = false           // 跌倒询问标志：TTS 播报完成后自动启麦
    private var pendingProviderRestart: Boolean = false      // 防止引擎切换和 provider 切换同时触发两次重启
    /** 加载超时任务（20秒后显示连接提示） */
    private var loadingTimeoutRunnable: Runnable? = null

    // ===== WAV/PCM 文件选择器 =====
    private val wavFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            playSelectedAudioFile(uri, "user_audio.wav", isPcm = false)
        } else {
            applyMessage("wav file picker cancelled")
        }
    }
    private val pcmFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            playSelectedAudioFile(uri, "user_audio.pcm", isPcm = true)
        } else {
            applyMessage("pcm file picker cancelled")
        }
    }

    /** 将 URI 中的音频文件复制到内部存储并播放 */
    private fun playSelectedAudioFile(uri: Uri, fileName: String, isPcm: Boolean) {
        try {
            val audioDir = File(filesDir, "audio")
            audioDir.mkdirs()
            val targetFile = File(audioDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            applyMessage("audio saved: ${targetFile.absolutePath}")
            Toast.makeText(this, "已选择文件: $fileName", Toast.LENGTH_SHORT).show()
            if (isPcm) {
                playPCMStream(targetFile.absolutePath)
            } else {
                playWAVFile(targetFile.absolutePath)
            }
        } catch (e: Exception) {
            applyMessage("file copy error: ${e.message}")
            Toast.makeText(this, "文件读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 根据设置显示/隐藏 WAV/PCM 播放按钮 */
    private fun applyPlayButtonsVisibility() {
        val showPlayButtons = getSharedPreferences("duix_settings", MODE_PRIVATE)
            .getBoolean("show_play_buttons", true)
        val visibility = if (showPlayButtons) View.VISIBLE else View.GONE
        binding.btnPlayWAV.visibility = visibility
        binding.btnPlayPCM.visibility = visibility
    }

    // ===== 服务端音频文件选择（方式一 + 方式三组合） =====

    /** 点击播放按钮：先查服务器文件列表，有则弹出选择器，无则给用户选择是从本地选还是取消 */
    private fun showAudioFileSelector(fileType: String) {
        val backendUrl = getRuntimeBackendUrl()
        if (backendUrl.isBlank()) {
            launchLocalFilePicker(fileType)
            return
        }
        // 后台线程获取服务器文件列表
        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val url = "${backendUrl}/dashboard/audio-files?file_type=${fileType}"
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()

                if (body != null && response.isSuccessful) {
                    val json = JSONObject(body)
                    val filesArray = json.getJSONArray("files")
                    runOnUiThread { showAudioFilePickerDialog(fileType, filesArray) }
                } else {
                    runOnUiThread {
                        showAudioErrorDialog(fileType, "获取服务器文件失败 (${response.code})")
                    }
                }
            } catch (e: Exception) {
                applyMessage("audio list error: ${e.message}")
                runOnUiThread {
                    showAudioErrorDialog(fileType, "无法连接到服务器: ${e.message}")
                }
            }
        }.start()
    }

    /** 弹出服务器文件选择对话框，最后一项为"从本地选择" */
    private fun showAudioFilePickerDialog(fileType: String, filesArray: JSONArray) {
        if (filesArray.length() == 0) {
            showAudioErrorDialog(fileType, "服务器暂无 ${fileType.uppercase()} 文件")
            return
        }
        val n = filesArray.length()
        val items = arrayOfNulls<String>(n + 1)
        val fileIds = IntArray(n)
        for (i in 0 until n) {
            val obj = filesArray.getJSONObject(i)
            val sizeStr = formatFileSize(obj.optInt("file_size", 0))
            items[i] = "${obj.getString("name")}  (${sizeStr})"
            fileIds[i] = obj.getInt("id")
        }
        items[n] = "📁 从本地选择文件..."

        AlertDialog.Builder(this)
            .setTitle(if (fileType == "wav") "选择 WAV 文件" else "选择 PCM 文件")
            .setItems(items) { _, which ->
                if (which < n) {
                    downloadAndPlayServerAudio(fileIds[which], fileType)
                } else {
                    launchLocalFilePicker(fileType)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 服务器无文件或出错时的提示对话框，让用户选择从本地选或取消 */
    private fun showAudioErrorDialog(fileType: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle("播放 ${fileType.uppercase()}")
            .setMessage("$message\n\n是否从手机本地选择文件播放？")
            .setPositiveButton("📁 从本地选择") { _, _ ->
                launchLocalFilePicker(fileType)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 从服务器下载音频文件并播放 */
    private fun downloadAndPlayServerAudio(fileId: Int, fileType: String) {
        val backendUrl = getRuntimeBackendUrl()
        if (backendUrl.isBlank()) return
        applyMessage("downloading server audio #$fileId...")
        Toast.makeText(this, "正在下载...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                val url = "${backendUrl}/dashboard/audio-files/${fileId}/download"
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                val body = response.body

                if (body != null && response.isSuccessful) {
                    val audioDir = File(filesDir, "audio")
                    audioDir.mkdirs()
                    val targetFile = File(audioDir, "server_audio_${fileId}.${fileType}")
                    FileOutputStream(targetFile).use { it.write(body.bytes()) }

                    runOnUiThread {
                        applyMessage("server audio saved: ${targetFile.absolutePath}")
                        Toast.makeText(this, "已下载并播放", Toast.LENGTH_SHORT).show()
                        if (fileType == "pcm") playPCMStream(targetFile.absolutePath)
                        else playWAVFile(targetFile.absolutePath)
                    }
                } else {
                    runOnUiThread {
                        applyMessage("download failed: ${response.code}")
                        Toast.makeText(this, "下载失败 (${response.code})", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    applyMessage("download error: ${e.message}")
                    Toast.makeText(this, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 打开本地文件选择器 */
    private fun launchLocalFilePicker(fileType: String) {
        if (fileType == "wav") {
            wavFilePickerLauncher.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*"))
        } else {
            pcmFilePickerLauncher.launch(arrayOf("*/*"))
        }
    }

    private fun formatFileSize(bytes: Int): String {
        return if (bytes < 1024 * 1024) {
            "${bytes / 1024} KB"
        } else {
            "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepScreenOn()
//        val audioManager = mContext.getSystemService(AUDIO_SERVICE) as AudioManager
//        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
//        audioManager.isSpeakerphoneOn = true
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modelUrl = intent.getStringExtra("modelUrl") ?: ""
        if (modelUrl.isBlank()) {
            // 没有从 Intent 传入模型名时，尝试从 SharedPreferences 恢复，或使用第一个可用模型
            modelUrl = ListeningSettingsDialog.getSavedModelName(this).ifBlank {
                ListeningSettingsDialog.getAvailableModels(this).firstOrNull() ?: ""
            }
        }
        // 保存 modelUrl 到 SharedPreferences，供 restartEngine 切换引擎时使用
        if (modelUrl.isNotBlank()) {
            getSharedPreferences("duix_settings", MODE_PRIVATE)
                .edit().putString("model_url", modelUrl).apply()
        }
        debug = intent.getBooleanExtra("debug", false)

        Glide.with(mContext).load("file:///android_asset/bg/bg1.png").into(binding.ivBg)

        binding.switchMute.setOnCheckedChangeListener(object : CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton?,
                isChecked: Boolean,
            ) {
                if (isChecked) {
                    digitalHumanEngine?.setVolume(0.0F)
                } else {
                    digitalHumanEngine?.setVolume(1.0F)
                }
            }
        })

        // 初始化音频管理器（用于音频焦点/闪避）
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager?
        // 确保扬声器输出（某些设备因 VOICE_RECOGNITION 录音导致音频路由改变后无声）
        audioManager?.isSpeakerphoneOn = true

        // 初始化 Android TTS（用于唤醒确认播报）
        androidTts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                androidTts?.language = java.util.Locale.CHINESE
                androidTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "wake_confirm") {
                            // TTS 播完 → 检查是否还在监听模式，再开始录音
                            // 防止用户在"我在..."播报期间退出唤醒模式导致误启动录音
                            runOnUiThread {
                                if (isListening && listeningMode == ListeningMode.WAKE_WORD) {
                                    startRecording()
                                } else {
                                    Log.d(TAG, "wake_confirm onDone: not listening anymore, skip recording")
                                }
                            }
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        // TTS 出错不影响功能（同样检查 isListening）
                        runOnUiThread {
                            if (isListening && listeningMode == ListeningMode.WAKE_WORD) {
                                startRecording()
                            } else {
                                Log.d(TAG, "wake_confirm onError: not listening anymore, skip recording")
                            }
                        }
                    }
                })
                Log.d(TAG, "Android TTS initialized (CHINESE)")
            } else {
                Log.w(TAG, "Android TTS init failed: $status")
                androidTts = null
            }
        }

        // 加载保存的录音模式和 VAD 灵敏度
        listeningMode = ListeningSettingsDialog.getSavedMode(this)
        vadSensitivity = ListeningSettingsDialog.getSavedVadSensitivity(this)
        applyMessage("loaded saved mode: ${listeningMode.name}, vad: ${vadSensitivity.name}")

        // 生成/读取持久化设备标识（用于跨轮次对话上下文）
        val deviceIdPrefs = getSharedPreferences("duix_settings", MODE_PRIVATE)
        var deviceId = deviceIdPrefs.getString("device_id", "")
        if (deviceId.isNullOrBlank()) {
            deviceId = java.util.UUID.randomUUID().toString()
            deviceIdPrefs.edit().putString("device_id", deviceId).apply()
        }
        applyMessage("device_id: ${deviceId.take(8)}...")

        // 设置按钮（右上角齿轮）
        binding.btnSettings.setOnClickListener {
            // 获取可用 DUIX 3D 模型列表和当前模型名
            val availableDuixModels = ListeningSettingsDialog.getAvailableModels(this)
            val currentDuixModel = ListeningSettingsDialog.getSavedModelName(this)
                .ifEmpty { availableDuixModels.firstOrNull() ?: "" }

            ListeningSettingsDialog(
                context = this,
                currentMode = listeningMode,
                currentBackendUrl = getRuntimeBackendUrl(),
                currentVadSensitivity = vadSensitivity,
                currentEngineType = ListeningSettingsDialog.getSavedEngineType(this),
                currentBackendProvider = ListeningSettingsDialog.getSavedBackendProvider(this),
                availableModels = availableDuixModels,
                currentModelName = currentDuixModel,
                onVadSensitivityChanged = { newSensitivity ->
                    vadSensitivity = newSensitivity
                    applyMessage("VAD 灵敏度: ${newSensitivity.name}")
                },
                onModeSelected = { newMode ->
                    // 设置切换中标志，阻止 onFinish 在模式切换期间启动新会话
                    isSwitchingMode = true
                    // 用 post 延迟到对话框 dismiss 之后再执行模式切换，
                    // 避免对话框 dismiss 动画期间修改按钮行为导致触摸事件冲突
                    binding.btnRecord.post {
                        if (isFinishing || isDestroyed) return@post
                        listeningMode = newMode
                        val modeName = when (newMode) {
                            ListeningMode.PUSH_TO_TALK -> "按住说话"
                            ListeningMode.TOGGLE -> "持续监听"
                            ListeningMode.WAKE_WORD -> "唤醒词"
                        }
                        applyMessage("录音模式: $modeName")
                        // 切换模式时如果正在监听，先退出监听
                        if (isListening) {
                            exitListeningMode()
                        }
                        setupRecordButtonForMode()
                        isSwitchingMode = false
                    }
                },
                onBackendUrlChanged = { newUrl ->
                    applyMessage("后端地址已更新: $newUrl")
                    Log.i(TAG, "backend URL changed to: $newUrl")
                    // 地址变了不需要立即生效，用户下次进入数字人页会读取新地址
                },
                onShowPlayButtonsChanged = { show ->
                    applyMessage("WAV/PCM 按钮: ${if (show) "显示" else "隐藏"}")
                    runOnUiThread { applyPlayButtonsVisibility() }
                },
                onEngineTypeChanged = { newType ->
                    applyMessage("数字人引擎: ${newType.id}，重启中...")
                    // 如果新引擎是 VIDEO_AVATAR，标记后续 provider 回调不需要再重启
                    pendingProviderRestart = (newType == DigitalHumanEngineType.VIDEO_AVATAR)
                    restartEngine(newType)
                },
                onBackendProviderChanged = { newProvider ->
                    applyMessage("后端引擎模式: $newProvider")
                    // 将 provider 设置发送到后端 API（使设置立即生效）
                    val backendUrl = getRuntimeBackendUrl()
                    if (backendUrl.isNotBlank()) {
                        Thread {
                            try {
                                val jsonBody = org.json.JSONObject().apply {
                                    put("mode", newProvider)
                                }
                                val client = OkHttpClient.Builder()
                                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                                    .build()
                                val request = Request.Builder()
                                    .url("${backendUrl.trimEnd('/')}/api/v1/avatar/provider")
                                    .put(jsonBody.toString().toRequestBody(
                                        "application/json; charset=utf-8".toMediaType()
                                    ))
                                    .build()
                                val response = client.newCall(request).execute()
                                if (response.isSuccessful) {
                                    applyMessage("后端引擎模式已切换: $newProvider")
                                    runOnUiThread {
                                        val modeName = when (newProvider) {
                                            "musetalk" -> "MuseTalk（实时）"
                                            "wav2lip" -> "Wav2Lip（高质量，约99秒）"
                                            else -> "Mock（测试模式）"
                                        }
                                        Toast.makeText(this@CallActivity,
                                            "视频后端引擎已切换为: $modeName", Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    val errBody = response.body?.string() ?: ""
                                    applyMessage("后端切换失败: ${response.code} $errBody")
                                    // 从 FastAPI 错误响应中提取 detail 字段
                                    val detailMsg = try {
                                        org.json.JSONObject(errBody).optString("detail", "未知错误")
                                    } catch (_: Exception) { errBody.take(60) }
                                    runOnUiThread {
                                        Toast.makeText(this@CallActivity,
                                            "引擎切换失败: $detailMsg",
                                            Toast.LENGTH_LONG).show()
                                    }
                                }
                            } catch (e: Exception) {
                                applyMessage("后端切换异常: ${e.message?.take(50)}")
                                runOnUiThread {
                                    Toast.makeText(this@CallActivity,
                                        "后端引擎切换失败: ${e.message?.take(40) ?: "未知错误"}",
                                        Toast.LENGTH_LONG).show()
                                }
                            }
                        }.start()
                    }
                    if (pendingProviderRestart) {
                        // 引擎类型也变了，restartEngine 已经在 onEngineTypeChanged 中执行
                        pendingProviderRestart = false
                    } else if (digitalHumanEngine is VideoAvatarEngineImpl) {
                        // 仅 provider 变化且当前已经是 VIDEO_AVATAR，重启使其生效
                        restartEngine(DigitalHumanEngineType.VIDEO_AVATAR)
                    }
                },
                onModelChanged = { newModel ->
                    applyMessage("DUIX 模型切换: $newModel")
                    // 保存模型名（已在 dialog 中保存，这里确保持久化）
                    ListeningSettingsDialog.saveModelName(this@CallActivity, newModel)
                    // 更新 modelUrl 变量供下次使用
                    modelUrl = newModel
                    // 重启引擎使新模型生效
                    restartEngine(DigitalHumanEngineType.DUIX_3D)
                }
            ).show()
        }

        // 根据当前模式设置录音按钮行为
        setupRecordButtonForMode()

        binding.btnPlayPCM.setOnClickListener {
            applyMessage("play pcm: checking server files...")
            showAudioFileSelector("pcm")
        }

        binding.btnPlayWAV.setOnClickListener {
            applyMessage("play wav: checking server files...")
            showAudioFileSelector("wav")
        }

        binding.btnRandomMotion.setOnClickListener {
            applyMessage("start random motion")
            digitalHumanEngine?.startRandomMotion(true)
        }
        binding.btnStopPlay.setOnClickListener {
            // interrupt() 内部已处理 engine.stopAudio() + WebSocket 断开 + 状态重置
            realtimeManager.interrupt(digitalHumanEngine)
        }

        // ── 创建数字人引擎 ——
        // 从 SharedPreferences 读取已保存的引擎类型，不存在则使用默认（DUIX_3D）
        val engineType = ListeningSettingsDialog.getSavedEngineType(this)
        val engine = DigitalHumanEngineFactory.create(
            type = engineType,
            context = mContext
        )
        digitalHumanEngine = engine
        engine.attach(binding.avatarContainer, modelUrl, object : DigitalHumanEngine.Callback {
            override fun onReady(motions: List<String>) {
                supportedMotions = motions
                Log.i(TAG, "engine onReady: $motions")
                removeLoadingOverlay()
                initOk()
            }

            override fun onInitError(message: String) {
                runOnUiThread {
                    applyMessage("init error: $message")
                    Log.e(TAG, "engine init error: $message")
                    showLoadingError("加载失败: $message")
                    Toast.makeText(mContext, "数字人加载失败: $message", Toast.LENGTH_LONG).show()
                }
            }

            override fun onAudioPlayStart() {
                isSystemSpeaking = true
                applyMessage("callback audio play start")
                Log.i(TAG, "engine audio play start")
                requestMicAudioFocus()
                resetAudioModeBeforePlayback()
                updateListeningStatus("正在播报...")
            }

            override fun onAudioPlayEnd() {
                isSystemSpeaking = false
                applyMessage("callback audio play end")
                Log.i(TAG, "engine audio play end")
                abandonMicAudioFocus()
                runOnUiThread {
                    pendingRestart?.let { binding.btnRecord.removeCallbacks(it) }
                    pendingRestart = null

                    // 跌倒询问：TTS 播报完成后自动启动录音（间隔 500ms 避免录到 TTS 尾音）
                    if (hasPendingInquiry) {
                        hasPendingInquiry = false
                        applyMessage("inquiry tts finished, start recording for response")
                        Log.i(TAG, "inquiry tts finished, starting recording in 500ms")
                        binding.btnRecord.postDelayed({
                            enterListeningMode()
                        }, 500)
                        return@runOnUiThread
                    }

                    // TTS 播完后自动开始下一轮监听
                    // 仅当没有正在进行的录音时才直接重启。
                    // 如果 audioRecorder != null，等现有录音结束 → onRecordFinish → scheduleNextListeningCycle。
                    // 避免直接 restart 中断现有录音导致 streaming 会话泄漏。
                    if (listeningMode == ListeningMode.TOGGLE && isListening && audioRecorder == null) {
                        applyMessage("tts finished, start next listening")
                        val restartRunnable = Runnable {
                            if (isListening) startRecording()
                        }
                        pendingRestart = restartRunnable
                        binding.btnRecord.postDelayed(restartRunnable, 800)
                    }
                    if (listeningMode == ListeningMode.WAKE_WORD && isListening && audioRecorder == null) {
                        applyMessage("tts finished, restart wake word detection")
                        val restartRunnable = Runnable {
                            if (isListening) enterWakeWordMode()
                        }
                        pendingRestart = restartRunnable
                        binding.btnRecord.postDelayed(restartRunnable, 1000)
                    }
                }
            }

            override fun onAudioPlayError(message: String) {
                isSystemSpeaking = false
                applyMessage("callback audio play error: $message")
                Log.e(TAG, "engine audio play error: $message")
                abandonMicAudioFocus()
                runOnUiThread {
                    pendingRestart?.let { binding.btnRecord.removeCallbacks(it) }
                    pendingRestart = null
                    if (listeningMode == ListeningMode.TOGGLE && isListening) {
                        binding.btnRecord.postDelayed({
                            if (isListening) startRecording()
                        }, 300)
                    }
                }
            }

            override fun onMotionStart() {
                applyMessage("callback motion play start")
                Log.e(TAG, "engine motion start")
            }

            override fun onMotionEnd() {
                applyMessage("callback motion play end")
                Log.e(TAG, "engine motion end")
            }
        })
        // 加载覆盖层必须在 attach() 之后添加，因为 attach() 会调用 removeAllViews()
        showLoadingOverlay()
        applyMessage("start init")
        engine.init(modelUrl)

        // 创建 DeviceController（WebSocket 模式下需要）
        val dc = if (useWebSocketMode) {
            DeviceController(this@CallActivity).also { dc ->
                // 绑定 PiP 回调：启动其他应用前自动进入画中画模式
                dc.onBeforeLaunchApp = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        runOnUiThread { enterPipMode() }
                    }
                }
                // 绑定拍照结果回调：显示 Toast 反馈给用户
                dc.onPhotoTaken = { success, msg ->
                    runOnUiThread {
                        applyMessage("photo: $msg")
                        Toast.makeText(
                            this@CallActivity,
                            if (success) "📸 拍照成功" else "📸 $msg",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        } else null
        deviceController = dc

        initRealtimeManager(deviceController = deviceController)
    }

    // ========================================================================
    // 双模式录音
    // ========================================================================

    /** 检查录音和相机权限是否都已授予 */
    private fun hasRecordPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRecordButtonForMode() {
        when (listeningMode) {
            ListeningMode.PUSH_TO_TALK -> {
                // 按住说话：OnTouchListener
                // 先清除 OnClickListener（防止 TOGGLE 模式遗留的点击事件与触摸事件冲突）
                binding.btnRecord.setOnClickListener(null)
                binding.btnRecord.text = getString(R.string.ptt_hold_to_talk)
                binding.btnRecord.setOnTouchListener { _, event ->
                    when (event?.action) {
                        MotionEvent.ACTION_DOWN -> {
                            if (hasRecordPermissions()) {
                                // 权限已授予：直接开始录音
                                startRecording()
                            } else {
                                // 权限未授予：请求权限（弹窗会吃掉 UP 事件，所以录音在 permissionsGet 中不自动开始）
                                requestPermission(
                                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
                                    1
                                )
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            // 松开：停止录音
                            stopRecording()
                            true
                        }
                        else -> false
                    }
                }
            }

            ListeningMode.TOGGLE -> {
                // 持续监听：OnClickListener 开关
                binding.btnRecord.text = getString(R.string.record)
                binding.btnRecord.setOnTouchListener(null) // 清除触摸监听
                binding.btnRecord.setOnClickListener {
                    if (isListening) {
                        // 退出监听模式
                        exitListeningMode()
                    } else {
                        // 进入监听模式前先确保权限
                        if (hasRecordPermissions()) {
                            enterListeningMode()
                        } else {
                            requestPermission(
                                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
                                1
                            )
                        }
                    }
                }
            }

            ListeningMode.WAKE_WORD -> {
                // 唤醒词模式：OnClickListener 开关唤醒检测
                binding.btnRecord.text = getString(R.string.record)
                binding.btnRecord.setOnTouchListener(null)
                binding.btnRecord.setOnClickListener {
                    if (isListening) {
                        exitListeningMode()
                    } else {
                        if (hasRecordPermissions()) {
                            enterWakeWordMode()
                        } else {
                            requestPermission(
                                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
                                1
                            )
                        }
                    }
                }
            }
        }
    }

    /** 切换模式后更新按钮文字 */
    private fun updateRecordButtonForMode() {
        when (listeningMode) {
            ListeningMode.PUSH_TO_TALK ->
                binding.btnRecord.text = getString(R.string.ptt_hold_to_talk)
            ListeningMode.WAKE_WORD ->
                binding.btnRecord.text = if (isListening)
                    getString(R.string.wake_word_listening)
                else
                    getString(R.string.wake_word_mode)
            else ->
                binding.btnRecord.text = if (isListening)
                    getString(R.string.listening_active)
                else
                    getString(R.string.record)
        }
    }

    /** 开始录音（直接使用 AudioRecorder，不弹 Dialog） */
    private fun startRecording() {
        applyMessage("start recording")
        updateListeningStatus(getString(R.string.listening_active))
        audioRecorder?.release()

        // 唤醒词模式：先停止唤醒词检测（麦克风互斥）
        if (listeningMode == ListeningMode.WAKE_WORD) {
            stopWakeWordDetector()
        }

        vadSilentStartMs = 0L
        vadSpeechFrames = 0
        vadBackgroundRms = 0.0
        vadBackgroundSamples = 0
        vadMusicStartMs = 0L
        vadIgnoreUntilMs = System.currentTimeMillis() + 800  // 前 800ms 采集背景噪音，忽略 VAD（800ms ≈ 40帧 足够估计背景，减少用户说话被误采集的概率）
        recordingStartMs = System.currentTimeMillis()
        isStreamingMode = false

        // WebSocket 模式：预连接并开始流式发送
        if (useWebSocketMode && !realtimeManager.isStreamingActive) {
            isStreamingMode = realtimeManager.beginStreamingSession(digitalHumanEngine)
            if (isStreamingMode) {
                applyMessage("streaming mode: ws connected")
            } else {
                applyMessage("streaming mode: ws connect failed, fallback to batch")
            }
        }


        // 录音时的视觉反馈（两种模式都有效）
        binding.btnRecord.text = if (listeningMode == ListeningMode.PUSH_TO_TALK) {
            "录音中..."
        } else {
            getString(R.string.listening_active)
        }
        binding.btnRecord.setBackgroundColor(0xFFCC0000.toInt())

        audioRecorder = AudioRecorder(this, object : AudioRecorder.RecorderCallback {
            override fun onReadData(data: ByteArray, offsetInBytes: Int, length: Int) {
                // 流式模式：边录边发 PCM 块到后端
                if (isStreamingMode) {
                    realtimeManager.sendStreamingAudioChunk(data)
                }
                // VAD 检测：TOGGLE 和 WAKE_WORD 模式都需要
                if (listeningMode == ListeningMode.TOGGLE || listeningMode == ListeningMode.WAKE_WORD) {
                    checkVad(data, offsetInBytes, length)
                }
                // 最大录音时长检查
                if (System.currentTimeMillis() - recordingStartMs > 15000) {
                    applyMessage("max recording time reached (15s)")
                    stopRecording()
                }
            }

            override fun onRecordError(code: Int, message: String) {
                applyMessage("record error: $message")
                runOnUiThread {
                    Toast.makeText(this@CallActivity, message, Toast.LENGTH_SHORT).show()
                }
                isStreamingMode = false
                // 监听模式下出错退出监听
                if (listeningMode == ListeningMode.TOGGLE || listeningMode == ListeningMode.WAKE_WORD) {
                    exitListeningMode()
                }
            }

            override fun onFinish(path: String) {
                applyMessage("record finished: $path")
                onRecordFinish(path)
            }
        })
        try {
            audioRecorder?.start()
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            applyMessage("recorder already released: ${e.message}")
        }
    }

    /** 停止录音 */
    private fun stopRecording() {
        applyMessage("stop recording")
        audioRecorder?.stop()
        // release 在 onFinish 或 onRecordError 中做

        // 复位音频路由：某些设备上 VOICE_RECOGNITION 录音后，
        // 音频子系统可能未正确复位，导致 DUIX 的 AudioTrack 输出无声。
        // 显式重置为 MODE_NORMAL 并确保扬声器输出。
        resetAudioModeAfterRecording()
    }

    /** 录音结束后复位音频路由（解决某些设备上 VOICE_RECOGNITION 导致播放无声的问题） */
    private fun resetAudioModeAfterRecording() {
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                am.mode = AudioManager.MODE_NORMAL
            }
            am.isSpeakerphoneOn = true
        } catch (e: Exception) {
            Log.w(TAG, "resetAudioMode failed: ${e.message}")
        }
    }

    /** 播放前确保音频路由正确（在 DUIX 即将播报时再次检查扬声器输出） */
    private fun resetAudioModeBeforePlayback() {
        try {
            val am = audioManager ?: return
            am.isSpeakerphoneOn = true
            // 某些设备上 DUIX 内部 AudioTrack 可能被之前的 MODE_IN_COMMUNICATION 影响，
            // 显式设回 MODE_NORMAL 确保音频路由到扬声器
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                if (am.mode != AudioManager.MODE_NORMAL) {
                    am.mode = AudioManager.MODE_NORMAL
                    Log.d(TAG, "reset audio mode to MODE_NORMAL before playback")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resetAudioModeBeforePlayback failed: ${e.message}")
        }
    }

    /** 录音完成 — 读取 PCM 文件并交给 sessionManager 处理 */
    private fun onRecordFinish(path: String) {
        audioRecorder?.release()
        audioRecorder = null

        // 如果已经退出监听模式，跳过后续会话处理
        if (!isListening && !realtimeManager.isStreamingActive) {
            applyMessage("onRecordFinish: not listening, skip")
            return
        }

        // 如果在切换模式中（设置对话框回调还没执行完），跳过启动新会话
        if (isSwitchingMode) {
            applyMessage("onRecordFinish: mode switching, skip session")
            return
        }

        // 流式模式：PCM 块已在录音中发送，只需结束会话
        if (isStreamingMode) {
            isStreamingMode = false
            applyMessage("streaming mode: finish session")
            updateListeningStatus(getString(R.string.realtime_thinking))
            // 设置 onSessionComplete：TOGGLE / WAKE_WORD 模式下自动开始下一轮
            if ((listeningMode == ListeningMode.TOGGLE || listeningMode == ListeningMode.WAKE_WORD) && isListening) {
                realtimeManager.onSessionComplete = {
                    runOnUiThread { scheduleNextListeningCycle() }
                }
            } else {
                realtimeManager.onSessionComplete = null
            }
            realtimeManager.finishStreamingSession()
            return
        }

        try {
            val file = java.io.File(path)
            if (!file.exists() || file.length() < 400) { // 至少 200ms 音频（16kHz = 3200 bytes）
                applyMessage("recording too short or missing")
                // 监听模式下延时重新开始
                if (isListening) {
                    scheduleNextRecordingDelay()
                }
                return
            }

            val pcm = file.readBytes()
            applyMessage("pcm size: ${pcm.size} bytes")
            updateListeningStatus(getString(R.string.realtime_thinking))

            realtimeManager.interrupt(digitalHumanEngine)
            // interrupt() 调用 engine.stopAudio() 停止 TTS 播放，
            // 但 DUIX SDK 的 stopAudio 不会触发 onAudioPlayEnd 回调，
            // 所以 isSystemSpeaking 可能仍为 true，需手动复位。
            // 否则 scheduleNextListeningCycle() 会因 isSystemSpeaking=true 永远等待。
            isSystemSpeaking = false
            // 设置 onSessionComplete：TOGGLE / WAKE_WORD 模式下自动开始下一轮
            if ((listeningMode == ListeningMode.TOGGLE || listeningMode == ListeningMode.WAKE_WORD) && isListening) {
                realtimeManager.onSessionComplete = {
                    runOnUiThread { scheduleNextListeningCycle() }
                }
            } else {
                realtimeManager.onSessionComplete = null
            }

            realtimeManager.startSession(pcm, digitalHumanEngine)
        } catch (e: Exception) {
            applyMessage("read record file error: ${e.message}")
            if (isListening) {
                scheduleNextRecordingDelay()
            }
        }
    }

    /** 安排下一轮监听（TOGGLE / WAKE_WORD 模式，TTS 已播完） */
    private fun scheduleNextListeningCycle() {
        // 清除之前可能残留的 pending restart
        pendingRestart?.let { binding.btnRecord.removeCallbacks(it) }
        pendingRestart = null

        if (isSystemSpeaking) {
            // DUIX 还在播报 TTS。不重启录音——等 onAudioPlayEnd 触发。
            // 但设 8 秒安全兜底：如果 onAudioPlayEnd 因故未触发（如 engine.stopAudio()
            // 未回调 onAudioPlayEnd），强制重启避免死锁。
            applyMessage("session done, waiting for TTS to finish")
            val fallback = Runnable {
                if (isListening) {
                    applyMessage("fallback: TTS timeout, force restart")
                    isSystemSpeaking = false
                    startNextListeningCycle()
                }
            }
            pendingRestart = fallback
            binding.btnRecord.postDelayed(fallback, 8000)
        } else {
            // TTS 已播完（或后端没有返回 TTS），直接启动下一轮
            applyMessage("auto next listening cycle")
            updateListeningStatus("准备重新录音...")
            if (isListening) {
                val restartRunnable = Runnable {
                    if (isListening) startNextListeningCycle()
                }
                pendingRestart = restartRunnable
                binding.btnRecord.postDelayed(restartRunnable, 500)
            }
        }
    }

    /** 根据当前监听类型启动下一轮（TOGGLE → VAD 录音，WAKE_WORD → 唤醒词检测） */
    private fun startNextListeningCycle() {
        when (listeningMode) {
            ListeningMode.TOGGLE -> {
                binding.btnRecord.text = getString(R.string.listening_active)
                binding.btnRecord.setBackgroundColor(0xFFCC0000.toInt())
                binding.btnRecord.postDelayed({
                    if (isListening) {
                        startRecording()
                    }
                }, 500)  // 500ms 等待扬声器停止（修复：录到自己播报的回音）
            }
            ListeningMode.WAKE_WORD -> {
                binding.btnRecord.text = getString(R.string.wake_word_listening)
                binding.btnRecord.setBackgroundColor(0xFFCC0000.toInt())
                // 重新启动唤醒词检测
                enterWakeWordMode()
            }
            else -> {}
        }
    }

    /** 延时重新开始录音（录音太短或出错时的 fallback） */
    private fun scheduleNextRecordingDelay() {
        binding.btnRecord.postDelayed({
            if (isListening) {
                if (listeningMode == ListeningMode.WAKE_WORD) {
                    // 唤醒词模式：回到唤醒监听
                    enterWakeWordMode()
                } else {
                    startRecording()
                }
            }
        }, 300)
    }

    /** VAD 检测 — 自适应阈值 + 最短语音时长过滤 + ZCR 音乐/语音区分 */
    private fun checkVad(data: ByteArray, offsetInBytes: Int, length: Int) {
        val now = System.currentTimeMillis()

        // ignore 期间：采集背景噪音 RMS，不计入 VAD 检测
        if (now < vadIgnoreUntilMs) {
            collectBackgroundRms(data, offsetInBytes, length)
            return
        }

        try {
            // 将 byte[] 转为 short[] 计算 RMS + ZCR（零交叉率）
            var sumSq = 0.0
            var zeroCrossings = 0
            var prevSample: Short = 0
            val sampleCount = length / 2
            for (i in 0 until sampleCount) {
                val low = data[offsetInBytes + i * 2].toInt() and 0xFF
                val high = data[offsetInBytes + i * 2 + 1].toInt()
                val sample = (low or (high shl 8)).toShort()
                val d = sample.toDouble()
                sumSq += d * d
                // ZCR：符号变化计数
                if (i > 0 && (prevSample >= 0 && sample < 0 || prevSample < 0 && sample >= 0)) {
                    zeroCrossings++
                }
                prevSample = sample
            }
            val rms = Math.sqrt(sumSq / sampleCount)
            val zcr = zeroCrossings.toDouble() / sampleCount

            // 检测是否正在播放音乐（外部 App 如网易云音乐）
            val isMusicPlaying = audioManager?.isMusicActive == true
            val musicInfo = if (isMusicPlaying) " music=Y" else ""

            // 每 500ms 打印一次 RMS + ZCR（帮助诊断 VAD）
            if (now - vadLastLogMs > 500) {
                vadLastLogMs = now
                val bgInfo = if (vadBackgroundSamples > 0) "bg=${"%.0f".format(vadBackgroundRms)}" else ""
                applyMessage("vad rms=${"%.0f".format(rms)} zcr=${"%.3f".format(zcr)}$bgInfo$musicInfo")
            }

            // VAD 灵敏度参数
            // VAD 参数：三档线性递减（LOW→HIGH 灵敏度递增、超时递减）
            val (minRms, speechFramesThreshold, silenceTimeoutMs) = when (vadSensitivity) {
                VadSensitivity.LOW -> Triple(700.0, 12, 1200L)
                VadSensitivity.NORMAL -> Triple(500.0, 8, 1000L)
                VadSensitivity.HIGH -> Triple(350.0, 5, 700L)
            }

            // 自适应阈值：不低于灵敏度对应的最低值，且至少比背景噪音 RMS 高 2 倍
            val adaptiveThreshold = maxOf(minRms, vadBackgroundRms * 2.0 + 200.0)

            // ZCR 阈值：语音的零交叉率远高于音乐/噪音
            // 语音通常 ZCR > 0.05，音乐通常 ZCR < 0.03
            val isLikelySpeech = zcr >= 0.035

            if (rms >= adaptiveThreshold) {
                if (isLikelySpeech) {
                    // 高 RMS + 高 ZCR → 语音，累积语音帧数
                    vadSpeechFrames = minOf(vadSpeechFrames + 1, 300)
                    vadSilentStartMs = 0L
                    vadMusicStartMs = 0L  // 检测到语音，重置音乐计时
                } else {
                    // 高 RMS + 低 ZCR → 可能是音乐/噪音，不累积帧数
                    if (vadSpeechFrames >= speechFramesThreshold) {
                        // 已经开始说话了，不重置（让正常静音检测决定何时停止）
                    } else {
                        // 还未开始说话，判断为背景音乐
                        // 核心修复：连续音乐超过5秒无语音 → 强制停止录音
                        // 避免在播放音乐时陷入"录音15秒→重启→录音15秒"的死循环
                        if (vadMusicStartMs == 0L) {
                            vadMusicStartMs = now
                        } else if (now - vadMusicStartMs > 5000) {
                            applyMessage("vad music timeout (5s), stop recording")
                            stopRecording()
                            return
                        }
                        vadSilentStartMs = 0L
                    }
                }
            } else {
                // 无声
                if (vadSpeechFrames >= speechFramesThreshold) {
                    if (vadSilentStartMs == 0L) {
                        vadSilentStartMs = now
                        applyMessage("vad silence start (speech=${vadSpeechFrames}frames)")
                    } else if (now - vadSilentStartMs > silenceTimeoutMs) {
                        applyMessage("vad silent timeout (${silenceTimeoutMs}ms), auto stop")
                        stopRecording()
                        return
                    }
                } else {
                    // 语音太短，视为噪声不触发停止
                    // 重置静音计时防止短音累积触发
                    vadSilentStartMs = 0L
                }
                // 连续静音超过 3 秒，重置语音帧数（防止环境噪音/音乐累积）
                if (vadSilentStartMs != 0L && now - vadSilentStartMs > 3000) {
                    vadSpeechFrames = 0
                    vadSilentStartMs = 0L
                    vadMusicStartMs = 0L  // 同步重置音乐计时，避免下次音乐触发误判
                    applyMessage("vad reset speech frames (idle 3s)")
                }
            }
        } catch (_: Exception) {
            // VAD 出错不影响录音
        }
    }

    /**
     * 采集背景噪音 RMS 值
     * 在 ignore 期间调用，累加计算平均 RMS 作为自适应阈值基准
     *
     * 优化：过滤掉明显是语音的音频帧（高 RMS + 高 ZCR），
     * 防止用户在忽略期内说话导致背景噪音估计值偏高，
     * 进而使自适应阈值过高、听不到用户正常说话。
     *
     * V2.3 增强：每帧都更新 EMA（不受日志节流限制），
     * 使背景估计更快收敛到真实值。同时强化语音帧过滤，
     * 降低漏检率，特别适配唤醒词模式下"我在..."后用户立即说话的场景。
     */
    private fun collectBackgroundRms(data: ByteArray, offsetInBytes: Int, length: Int) {
        try {
            var sumSq = 0.0
            var zeroCrossings = 0
            var prevSample: Short = 0
            val sampleCount = length / 2
            for (i in 0 until sampleCount) {
                val low = data[offsetInBytes + i * 2].toInt() and 0xFF
                val high = data[offsetInBytes + i * 2 + 1].toInt()
                val sample = (low or (high shl 8)).toShort()
                val d = sample.toDouble()
                sumSq += d * d
                if (i > 0 && (prevSample >= 0 && sample < 0 || prevSample < 0 && sample >= 0)) {
                    zeroCrossings++
                }
                prevSample = sample
            }
            val rms = Math.sqrt(sumSq / sampleCount)
            val zcr = zeroCrossings.toDouble() / sampleCount

            // 语音帧过滤（双重防线）：
            // 1. 中高 RMS（>250）→ 大概率是语音或冲击噪音，直接跳过
            // 2. 中等 RMS（>150）+ 高 ZCR（>0.035）→ 轻声说话，跳过
            // 唤醒词模式下用户刚听到"我在..."，大概率会立即说话，过滤需更积极
            if (rms > 250.0) {
                return
            }
            if (rms > 150.0 && zcr > 0.035) {
                return
            }

            // 每帧都更新指数移动平均（不再受 vadLastLogMs 日志节流限制）
            // 这样即使 ignore 期间只有少数几帧是真正的背景噪音，也能及时收敛
            if (vadBackgroundSamples == 0) {
                vadBackgroundRms = rms
            } else {
                vadBackgroundRms = vadBackgroundRms * 0.9 + rms * 0.1
            }
            vadBackgroundSamples++

            // 日志仍保持 500ms 节流，不干扰 EMA 更新
            val now = System.currentTimeMillis()
            if (now - vadLastLogMs > 500) {
                vadLastLogMs = now
                applyMessage("vad bg rms=${"%.0f".format(rms)}, avg=${"%.0f".format(vadBackgroundRms)}")
            }
        } catch (_: Exception) { }
    }

    /** 进入监听模式（TOGGLE） */
    private fun enterListeningMode() {
        isListening = true
        binding.btnRecord.text = getString(R.string.listening_active)
        binding.btnRecord.setBackgroundColor(0xFFCC0000.toInt())
        applyMessage("listening mode ON")
        startRecording()
    }

    /** 进入唤醒词模式 */
    private fun enterWakeWordMode() {
        isListening = true
        binding.btnRecord.text = getString(R.string.wake_word_listening)
        binding.btnRecord.setBackgroundColor(0xFFCC0000.toInt())
        applyMessage("wake word mode ON: listening for '原宝'...")
        Log.i(TAG, "enterWakeWordMode: backend=${getRuntimeBackendUrl()}")
        Toast.makeText(this, "唤醒模式已开启，说\"原宝\"试试", Toast.LENGTH_SHORT).show()

        // 确保没有正在运行的唤醒词检测器（防止 CALLBACK_EVENT_AUDIO_PLAY_END + 手动点击双重触发）
        stopWakeWordDetector()

        // 确保没有正在运行的录音
        audioRecorder?.release()
        audioRecorder = null

        // 创建并启动唤醒词检测器（后端 ASR 触发词）
        wakeWordDetector = WakeWordDetector(
            backendBaseUrl = getRuntimeBackendUrl(),
            vadSensitivity = vadSensitivity,
            callback = object : WakeWordDetector.Callback {
                override fun onWakeWordDetected(recognizedText: String) {
                    Log.i(TAG, "wake word detected: $recognizedText")
                    applyMessage("wake word detected! stopping detector, playing confirmation...")
                    stopWakeWordDetector()
                    if (!isListening) {
                        Log.d(TAG, "wake word detected but not listening anymore, skip confirmation")
                        return
                    }
                    runOnUiThread {
                        binding.btnRecord.text = "我在..."
                        playWakeWordConfirmation()
                    }
                }

                override fun onWakeWordCommand(recognizedText: String, commandText: String,
                                               responseText: String, audioBase64: String, sampleRate: Int) {
                    Log.i(TAG, "wake word + command: '$commandText' → '$responseText'")
                    applyMessage("wake word + command: $commandText → $responseText")
                    stopWakeWordDetector()
                    if (!isListening) return
                    runOnUiThread {
                        binding.btnRecord.text = "处理中..."
                        playWakeWordResponsePcm(audioBase64, responseText)
                    }
                }

                override fun onWakeWordStateChanged(active: Boolean) {
                    val status = if (active) "唤醒监听已启动" else "唤醒监听已停止"
                    applyMessage(status)
                }

                override fun onWakeWordError(message: String) {
                    Log.e(TAG, "wake word error: $message")
                    applyMessage("wake word error: $message")
                    runOnUiThread {
                        Toast.makeText(this@CallActivity, "唤醒词错误: $message", Toast.LENGTH_SHORT).show()
                        if (isListening) exitListeningMode()
                    }
                }

                override fun onWakeWordRecordingStarted() {
                    applyMessage("VAD detected speech, recording...")
                }

                override fun onWakeWordAsrResult(text: String, isMatch: Boolean) {
                    applyMessage("ASR: '$text' ${if (isMatch) "✅匹配" else "❌不匹配"}")
                }
            }
        )
        wakeWordDetector?.start()
    }

    /** 停止唤醒词检测器 */
    private fun stopWakeWordDetector() {
        wakeWordDetector?.stop()
        wakeWordDetector = null
    }

    /**
     * 播报唤醒词+命令的回复音频。
     *
     * 后端已直接做 LLM+TTS，返回 base64 PCM 音频。
     * 此处解码 → 加 WAV 头 → 保存临时文件 → 通过引擎播放。
     * 引擎 onAudioPlayEnd 中会自动重启唤醒词检测。
     */
    private fun playWakeWordResponsePcm(audioBase64: String, responseText: String) {
        val engine = digitalHumanEngine
        if (engine == null || !engine.isReady) {
            Log.w(TAG, "engine not ready, skip playing wake word response")
            // 引擎不可用，但仍要回到唤醒监听状态
            binding.btnRecord.postDelayed({
                if (isListening && listeningMode == ListeningMode.WAKE_WORD) enterWakeWordMode()
            }, 500)
            return
        }
        try {
            val pcmData24000 = Base64.decode(audioBase64, Base64.DEFAULT)
            if (pcmData24000.isEmpty()) {
                Log.e(TAG, "decoded PCM is empty")
                binding.btnRecord.postDelayed({
                    if (isListening && listeningMode == ListeningMode.WAKE_WORD) enterWakeWordMode()
                }, 500)
                return
            }
            // DUIX playAudio() 内部剥离 WAV 头后通过 pushPcm() 以 16kHz 播放，
            // 但后端 TTS 返回的 PCM 是 24000Hz。必须先下采样再写入 WAV 文件。
            val pcmData = PcmResampler.downsamplePcm16BitMono(pcmData24000, 24000, 16000)
            Log.d(TAG, "wake response PCM: ${pcmData24000.size}→${pcmData.size} bytes (24000→16000 Hz)")

            // 构造 WAV 文件头 + PCM 数据（16000Hz）
            val sampleRate = 16000
            val channels: Short = 1
            val bitsPerSample: Short = 16
            val dataSize = pcmData.size
            val fileSize = 36 + dataSize

            val wavHeader = ByteArray(44).apply {
                // RIFF header
                this[0] = 'R'.code.toByte(); this[1] = 'I'.code.toByte()
                this[2] = 'F'.code.toByte(); this[3] = 'F'.code.toByte()
                // file size - 8
                this[4] = (fileSize and 0xFF).toByte()
                this[5] = ((fileSize shr 8) and 0xFF).toByte()
                this[6] = ((fileSize shr 16) and 0xFF).toByte()
                this[7] = ((fileSize shr 24) and 0xFF).toByte()
                // WAVE
                this[8] = 'W'.code.toByte(); this[9] = 'A'.code.toByte()
                this[10] = 'V'.code.toByte(); this[11] = 'E'.code.toByte()
                // fmt chunk
                this[12] = 'f'.code.toByte(); this[13] = 'm'.code.toByte()
                this[14] = 't'.code.toByte(); this[15] = ' '.code.toByte()
                // chunk size = 16
                this[16] = 16; this[17] = 0; this[18] = 0; this[19] = 0
                // audio format = 1 (PCM)
                this[20] = 1; this[21] = 0
                // channels
                this[22] = channels.toByte(); this[23] = 0
                // sample rate 16000
                this[24] = (sampleRate and 0xFF).toByte()
                this[25] = ((sampleRate shr 8) and 0xFF).toByte()
                this[26] = ((sampleRate shr 16) and 0xFF).toByte()
                this[27] = ((sampleRate shr 24) and 0xFF).toByte()
                // byte rate = sampleRate * channels * bitsPerSample/8
                val byteRate = sampleRate * channels * (bitsPerSample / 8)
                this[28] = (byteRate and 0xFF).toByte()
                this[29] = ((byteRate shr 8) and 0xFF).toByte()
                this[30] = ((byteRate shr 16) and 0xFF).toByte()
                this[31] = ((byteRate shr 24) and 0xFF).toByte()
                // block align = channels * bitsPerSample/8
                val blockAlign = channels * (bitsPerSample / 8)
                this[32] = blockAlign.toByte(); this[33] = 0
                // bits per sample
                this[34] = bitsPerSample.toByte(); this[35] = 0
                // data chunk
                this[36] = 'd'.code.toByte(); this[37] = 'a'.code.toByte()
                this[38] = 't'.code.toByte(); this[39] = 'a'.code.toByte()
                // data size
                this[40] = (dataSize and 0xFF).toByte()
                this[41] = ((dataSize shr 8) and 0xFF).toByte()
                this[42] = ((dataSize shr 16) and 0xFF).toByte()
                this[43] = ((dataSize shr 24) and 0xFF).toByte()
            }

            val wavData = wavHeader + pcmData
            val cacheDir = mContext.externalCacheDir
            if (cacheDir == null) {
                Log.e(TAG, "externalCacheDir is null")
                binding.btnRecord.postDelayed({
                    if (isListening && listeningMode == ListeningMode.WAKE_WORD) enterWakeWordMode()
                }, 500)
                return
            }
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val wavFile = File(cacheDir, "wake_response_${System.currentTimeMillis()}.wav")
            FileOutputStream(wavFile).use { it.write(wavData) }

            Log.i(TAG, "播放唤醒词回复音频: ${wavFile.absolutePath} (${wavData.size} bytes)")
            engine.playAudio(wavFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "playWakeWordResponsePcm error: ${e.message}", e)
            // 出错时仍回到唤醒监听状态
            binding.btnRecord.postDelayed({
                if (isListening && listeningMode == ListeningMode.WAKE_WORD) enterWakeWordMode()
            }, 500)
        }
    }

    /**
     * 播报唤醒确认语音，播完后自动开始录音。
     *
     * 使用 Android 自带 TTS（不需要网络），播报"我在，有什么可以帮助您的？"，
     * 让用户明确知道唤醒成功，然后自然地说话下达指令。
     */
    private fun playWakeWordConfirmation() {
        val tts = androidTts
        if (tts != null) {
            // 停止正在播放的 TTS（防止残留）
            if (tts.isSpeaking) tts.stop()
            val params = android.os.Bundle()
            // 播完后 onDone 回调中自动调用 startRecording()
            tts.speak("我在，有什么可以帮助您的?", TextToSpeech.QUEUE_FLUSH, params, "wake_confirm")
        } else {
            // TTS 不可用，直接开始录音（不影响功能）
            Log.w(TAG, "Android TTS not available, skipping confirmation")
            startRecording()
        }
    }

    // ========================================================================
    // 音频焦点管理（录音时降低其他 App 音量）
    // ========================================================================

    /** 请求音频焦点（录音时暂停其他 App 播放，避免音乐/歌词被识别） */
    private fun requestMicAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = android.media.AudioFocusRequest.Builder(
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ).build()
                am.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
            applyMessage("audio focus requested (duck music)")
        } catch (e: Exception) {
            applyMessage("audio focus error: ${e.message}")
        }
    }

    /** 放弃音频焦点（恢复其他 App 播放） */
    private fun abandonMicAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = android.media.AudioFocusRequest.Builder(
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                ).build()
                am.abandonAudioFocusRequest(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
            applyMessage("audio focus abandoned")
        } catch (e: Exception) {
            applyMessage("audio focus abandon error: ${e.message}")
        }
    }


    /** 获取运行时的后端地址（SharedPreferences 优先，BuildConfig 兜底） */
    private fun getRuntimeBackendUrl(): String {
        val saved = ListeningSettingsDialog.getSavedBackendUrl(this)
        return saved.ifBlank { AppBuildConfig.BACKEND_BASE_URL }
    }

    /**
     * 将技术错误信息翻译为用户友好的中文提示。
     * 错误消息来源包括：RealtimeSessionManager、VoiceWebSocketClient、BackendVoiceInteractionClient。
     */
    private fun translateErrorMessage(msg: String): String {
        // 按匹配优先级从高到低排列
        return when {
            msg.contains("engine is not ready") -> "数字人引擎尚未就绪，请稍候"
            msg.contains("asr result is empty") -> "没有听清楚，请再说一遍"
            msg.contains("llm result is empty") -> "AI 暂时无法回答，请稍后再试"
            msg.contains("tts result is empty") -> "语音合成失败，请重试"
            msg.contains("realtime error") -> "处理出错，请重试"
            msg.contains("ws connect timeout") -> "连接服务器超时，请检查网络"
            msg.contains("ws connect failed") -> "连接服务器失败，请检查网络"
            msg.contains("ws failure") -> "网络连接中断，请重试"
            msg.contains("ws session timed out") -> "后端响应超时，请重试"
            msg.contains("ws session interrupted") -> "对话已中断"
            msg.contains("ws error: 音频数据过小") -> "没有检测到声音，请靠近麦克风说话"
            msg.contains("ws error") -> "服务器返回错误，请重试"
            msg.contains("ws parse error") -> "数据解析错误"
            msg.contains("ws binary error") -> "音频数据处理错误"
            msg.contains("backend request failed") -> "服务器请求失败"
            msg.contains("backend stream request failed") -> "服务器流式请求失败"
            msg.contains("startPush error") -> "数字人启动失败"
            msg.contains("pushPcm error") -> "音频播放错误"
            msg.contains("stopPush error") -> "音频停止错误"
            msg.contains("stream flush error") -> "音频刷新错误"
            // 默认：取前 50 个字符
            else -> msg.take(50)
        }
    }

    /** 退出监听/唤醒模式 */
    private fun exitListeningMode() {
        if (!isListening && !realtimeManager.isStreamingActive) return  // 防止重入
        isListening = false
        binding.btnRecord.text = getString(R.string.record)
        binding.btnRecord.setBackgroundResource(R.drawable.shape_common_btn)
        pendingRestart?.let { binding.btnRecord.removeCallbacks(it) }
        pendingRestart = null
        realtimeManager.onSessionComplete = null
        // 停止唤醒词检测
        stopWakeWordDetector()
        // 先停止录音（可能触发 onFinish → onRecordFinish），
        // 再置 isStreamingMode=false，确保 onRecordFinish 中 isStreamingMode 正确。
        stopRecording()
        isStreamingMode = false
        realtimeManager.interrupt(digitalHumanEngine)
        applyMessage("listening mode OFF")
        updateListeningStatus(getString(R.string.ai_tips))
    }

    /**
     * 初始化推送 WebSocket 客户端（接收数字人播报和告警通知）
     *
     * 在 DUIX 初始化完成后调用（initOk），确保 duix 引用可用。
     * 如果客户端已存在（如前一次 initOk 已创建），则只更新 duix 引用，
     * 确保后续 TTS 播报推送到最新的 DUIX 实例。
     * 断线自动重连，不需要手动维护连接状态。
     */
    private fun initPushWebSocket() {
        val backendUrl = getRuntimeBackendUrl()
        if (backendUrl.isBlank()) {
            Log.w(TAG, "push WS: BACKEND_BASE_URL not configured, skipping")
            return
        }

        if (pushWsClient != null) {
            // 客户端已存在，只更新数字人引擎引用（防止模型切换或 Activity 重建后引用失效）
            pushWsClient?.updateEngine(digitalHumanEngine)
            return
        }

        pushWsClient = PushWebSocketClient(
            context = applicationContext,
            backendBaseUrl = backendUrl,
            engine = digitalHumanEngine,
            callback = object : PushWebSocketClient.Callback {
                override fun onPushWsStateChanged(connected: Boolean) {
                    val msg = if (connected) "推送连接成功" else "推送连接断开"
                    applyMessage("push ws: $msg")
                    Log.i(TAG, "push ws state: $msg")
                }

                override fun onAlertReceived(alertData: org.json.JSONObject) {
                    // 收到告警 → 显示 Toast 通知
                    val location = alertData.optString("location", "")
                    val message = alertData.optString("message", "收到告警")
                    val alertType = alertData.optString("type", "unknown")
                    runOnUiThread {
                        val alertIcon = when (alertType) {
                            "fall" -> "🚨"
                            "privacy_check" -> "🔔"
                            else -> "⚠️"
                        }
                        Toast.makeText(
                            this@CallActivity,
                            "$alertIcon $message",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    applyMessage("push alert: $message")
                    Log.w(TAG, "push alert received: type=$alertType location=$location")
                }

                override fun onInquiryListen(text: String, deviceId: String, timeoutSeconds: Int) {
                    // 收到跌倒询问指令 → 标记 pending inquiry，等待 TTS 播报完成后自动启麦
                    Log.i(TAG, "inquiry_listen: text='${text.take(40)}', timeout=${timeoutSeconds}s")
                    applyMessage("inquiry_listen: ${text.take(40)}")
                    hasPendingInquiry = true
                }
            }
        )
        pushWsClient?.connect()
        applyMessage("push ws client created, connecting...")
    }

    /**
     * 创建或重新创建 RealtimeSessionManager
     */
    private fun initRealtimeManager(
        deviceController: com.yuanshi.avatar.service.DeviceController? = null
    ) {
        // 获取持久化设备标识
        val deviceIdPrefs = getSharedPreferences("duix_settings", MODE_PRIVATE)
        val deviceId = deviceIdPrefs.getString("device_id", "") ?: ""

        realtimeManager = RealtimeSessionManager(
            config = RealtimeConfig(
                backendBaseUrl = getRuntimeBackendUrl(),
                apiKey = AppBuildConfig.DASHSCOPE_API_KEY,
                baseUrl = AppBuildConfig.DASHSCOPE_BASE_URL,
                ttsWsUrl = AppBuildConfig.DASHSCOPE_TTS_WS_URL,
                useWebSocket = useWebSocketMode,
                deviceId = deviceId
            ),
            deviceController = deviceController,
            callback = object : RealtimeSessionManager.Callback {
                override fun onStateChanged(state: RealtimeState) {
                    applyMessage("realtime state: $state")
                    runOnUiThread {
                        when (state) {
                            RealtimeState.THINKING -> {
                                binding.btnRecord.text = getString(R.string.realtime_thinking)
                                binding.btnRecord.isEnabled = false
                                updateListeningStatus(getString(R.string.realtime_thinking))
                            }
                            RealtimeState.SPEAKING -> {
                                binding.btnRecord.text = getString(R.string.realtime_thinking)
                                binding.btnRecord.isEnabled = false
                                updateListeningStatus("合成语音中...")
                            }
                            RealtimeState.IDLE -> {
                                when {
                                    listeningMode == ListeningMode.PUSH_TO_TALK -> {
                                        binding.btnRecord.text = getString(R.string.ptt_hold_to_talk)
                                        binding.btnRecord.setBackgroundResource(R.drawable.shape_common_btn)
                                    }
                                    listeningMode == ListeningMode.WAKE_WORD && isListening -> {
                                        binding.btnRecord.text = getString(R.string.wake_word_listening)
                                        binding.btnRecord.setBackgroundColor(0xFFCC0000.toInt())
                                    }
                                    isListening -> {
                                        binding.btnRecord.text = getString(R.string.listening_active)
                                        binding.btnRecord.setBackgroundColor(0xFFCC0000.toInt())
                                        updateListeningStatus(getString(R.string.listening_active))
                                    }
                                    else -> {
                                        binding.btnRecord.text = getString(R.string.record)
                                        binding.btnRecord.setBackgroundResource(R.drawable.shape_common_btn)
                                    }
                                }
                                binding.btnRecord.isEnabled = listeningMode == ListeningMode.PUSH_TO_TALK ||
                                        !realtimeManager.isStreamingActive
                            }
                            else -> {}
                        }
                    }
                }

                override fun onInfo(message: String) {
                    applyMessage(message)
                }

                override fun onError(message: String) {
                    val userMsg = translateErrorMessage(message)
                    applyMessage("error: $message")  // 日志记录原始错误
                    runOnUiThread {
                        Toast.makeText(mContext, userMsg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    /**
     * 重启数字人引擎（切换引擎类型时调用）
     * 释放旧引擎 → 创建新引擎 → attach → init
     * 异常安全：捕获所有异常防止 DUIX SDK 崩溃导致 APK 闪退
     */
    private fun restartEngine(type: DigitalHumanEngineType) {
        applyMessage("restarting engine: ${type.id}")
        // 停止当前交互
        realtimeManager.interrupt(digitalHumanEngine)
        realtimeManager.release()
        // 释放旧引擎
        digitalHumanEngine?.release()
        digitalHumanEngine = null
        pushWsClient?.release()
        pushWsClient = null
        // 清除容器
        binding.avatarContainer.removeAllViews()
        // 获取 modelUrl（DUIX 需要）
        val modelUrl = getSharedPreferences("duix_settings", MODE_PRIVATE)
            .getString("model_url", "") ?: ""

        // 验证 DUIX 3D 引擎的模型路径（防止空路径导致 native 崩溃）
        if (type == DigitalHumanEngineType.DUIX_3D && modelUrl.isBlank()) {
            applyMessage("DUIX 3D 模型路径未配置，回退到 VIDEO_AVATAR")
            Log.w(TAG, "restartEngine: DUIX_3D model_url is empty, falling back to VIDEO_AVATAR")
            showLoadingError("3D 数字人模型未配置\n请在启动时传入 modelUrl 参数")
            // 延时回退，避免在错误对话框未消失时立即重启
            binding.avatarContainer.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    applyMessage("回退到 VIDEO_AVATAR 引擎")
                    showLoadingOverlay()
                    restartEngine(DigitalHumanEngineType.VIDEO_AVATAR)
                }
            }, 2000)
            return
        }

        // 创建新引擎（异常安全）
        val newEngine: DigitalHumanEngine
        try {
            newEngine = DigitalHumanEngineFactory.create(type, mContext)
        } catch (e: Throwable) {
            applyMessage("创建 ${type.id} 引擎失败: ${e.message?.take(50)}")
            Log.e(TAG, "restartEngine: 创建引擎失败", e)
            showLoadingError("引擎创建失败: ${e.message?.take(50)}")
            // 回退到 VideoAvatarEngine
            if (type.id != DigitalHumanEngineType.VIDEO_AVATAR.id) {
                applyMessage("回退到 VIDEO_AVATAR 引擎")
                showLoadingOverlay()
                restartEngine(DigitalHumanEngineType.VIDEO_AVATAR)
            }
            return
        }

        digitalHumanEngine = newEngine
        try {
            newEngine.attach(binding.avatarContainer, modelUrl, object : DigitalHumanEngine.Callback {
                override fun onReady(motions: List<String>) {
                    supportedMotions = motions
                    Log.i(TAG, "engine onReady: $motions")
                    removeLoadingOverlay()
                    // 重新初始化 VoiceSessionManager（传入 deviceController 以支持 Android 工具调用）
                    initRealtimeManager(deviceController = deviceController)
                    initPushWebSocket()
                    initOk()
                }
                override fun onInitError(message: String) {
                    runOnUiThread {
                        applyMessage("init error: $message")
                        Log.e(TAG, "engine init error: $message")
                        showLoadingError("引擎初始化失败: $message")
                        Toast.makeText(mContext, "引擎初始化失败: $message", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onAudioPlayStart() {
                    isSystemSpeaking = true
                    applyMessage("callback audio play start")
                    Log.i(TAG, "engine audio play start")
                    requestMicAudioFocus()
                    resetAudioModeBeforePlayback()
                }
                override fun onAudioPlayEnd() {
                    isSystemSpeaking = false
                    applyMessage("callback audio play end")
                    Log.i(TAG, "engine audio play end")
                    abandonMicAudioFocus()
                    runOnUiThread {
                        pendingRestart?.let { binding.btnRecord.removeCallbacks(it) }
                        pendingRestart = null
                        // 跌倒询问：TTS 播报完成后自动启动录音
                        if (hasPendingInquiry) {
                            hasPendingInquiry = false
                            applyMessage("inquiry tts finished, start recording for response")
                            binding.btnRecord.postDelayed({
                                enterListeningMode()
                            }, 500)
                            return@runOnUiThread
                        }
                        if (listeningMode == ListeningMode.TOGGLE && isListening && audioRecorder == null) {
                            applyMessage("tts finished, start next listening")
                            val restartRunnable = Runnable {
                                if (isListening) startRecording()
                            }
                            pendingRestart = restartRunnable
                            binding.btnRecord.postDelayed(restartRunnable, 800)
                        }
                        if (listeningMode == ListeningMode.WAKE_WORD && isListening && audioRecorder == null) {
                            applyMessage("tts finished, restart wake word detection")
                            val restartRunnable = Runnable {
                                if (isListening) enterWakeWordMode()
                            }
                            pendingRestart = restartRunnable
                            binding.btnRecord.postDelayed(restartRunnable, 1000)
                        }
                    }
                }
                override fun onAudioPlayError(message: String) {
                    isSystemSpeaking = false
                    applyMessage("callback audio play error: $message")
                    Log.e(TAG, "engine audio play error: $message")
                    abandonMicAudioFocus()
                    runOnUiThread {
                        pendingRestart?.let { binding.btnRecord.removeCallbacks(it) }
                        pendingRestart = null
                        if (listeningMode == ListeningMode.TOGGLE && isListening) {
                            binding.btnRecord.postDelayed({
                                if (isListening) startRecording()
                            }, 300)
                        }
                    }
                }
                override fun onMotionStart() {
                    applyMessage("callback motion play start")
                }
                override fun onMotionEnd() {
                    applyMessage("callback motion play end")
                }
            })
        } catch (e: Throwable) {
            applyMessage("挂载 ${type.id} 引擎失败: ${e.message?.take(50)}")
            Log.e(TAG, "restartEngine: attach 失败", e)
            showLoadingError("引擎挂载失败: ${e.message?.take(50)}")
            // 回退到 VideoAvatarEngine
            if (type.id != DigitalHumanEngineType.VIDEO_AVATAR.id) {
                applyMessage("回退到 VIDEO_AVATAR 引擎")
                showLoadingOverlay()
                restartEngine(DigitalHumanEngineType.VIDEO_AVATAR)
            }
            return
        }

        // 加载覆盖层必须在 attach() 之后添加，因为 attach() 会调用 removeAllViews()
        showLoadingOverlay()
        try {
            newEngine.init(modelUrl)
        } catch (e: Throwable) {
            applyMessage("初始化 ${type.id} 引擎失败: ${e.message?.take(50)}")
            Log.e(TAG, "restartEngine: init 失败", e)
            showLoadingError("引擎初始化失败: ${e.message?.take(50)}")
        }
    }

    private fun initOk() {
        Log.i(TAG, "init ok")
        applyMessage("init ok")

        // DUIX 初始化完成 → 创建推送 WebSocket 客户端（接收数字人播报和告警）
        initPushWebSocket()

        runOnUiThread {
            binding.btnRecord.isEnabled = true
            binding.btnRecord.visibility = View.VISIBLE
            binding.btnPlayPCM.isEnabled = true
            binding.btnPlayWAV.isEnabled = true
            applyPlayButtonsVisibility()
            binding.switchMute.isEnabled = true
            binding.btnStopPlay.isEnabled = true

            if (supportedMotions.isNotEmpty()) {
                binding.btnRandomMotion.visibility = View.VISIBLE
                binding.tvMotionTips.visibility = View.VISIBLE
                val motionAdapter = MotionAdapter(ArrayList(supportedMotions), object : MotionAdapter.Callback{
                    override fun onClick(name: String, now: Boolean) {
                        applyMessage("start [${name}] motion")
                        digitalHumanEngine?.setMotion(name, now)
                    }
                })
                binding.rvMotion.adapter = motionAdapter
            }
        }
    }


    // ========================================================================
    // 画中画（Picture-in-Picture）模式
    // ========================================================================

    /**
     * 进入画中画模式（API 26+）
     *
     * PiP 宽高比设为 9:16（竖屏数字人），在后台或打开其他应用时
     * 以悬浮小窗口保持数字人可见。
     */
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // 防止在 Activity 即将销毁时进入 PiP（IllegalStateException）
        if (isFinishing || isDestroyed) {
            applyMessage("PiP skipped: activity finishing/destroyed")
            return
        }
        // 防止重复进入 PiP（已在 PiP 模式中）
        if (isInPictureInPictureMode) {
            applyMessage("PiP skipped: already in PiP mode")
            return
        }
        try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(9, 16))
                .build()
            enterPictureInPictureMode(params)
            applyMessage("PiP entered")
        } catch (e: IllegalStateException) {
            applyMessage("PiP enter failed (IllegalState): ${e.message}")
        } catch (e: Exception) {
            applyMessage("PiP enter failed: ${e.message}")
        }
    }

    /**
     * 用户离开 Activity（按 Home 键或切换应用）时自动进入 PiP
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 如果在 PiP 模式或 Activity 正在销毁，不再重复进入
            if (!isFinishing && !isDestroyed && !isInPictureInPictureMode) {
                enterPipMode()
            }
        }
    }

    /**
     * PiP 模式状态变更回调
     *
     * 进入 PiP 时隐藏操作按钮，只保留 GLSurfaceView；
     * 退出 PiP（回到全屏）时恢复 UI 并通知渲染线程。
     */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            // 进入 PiP：隐藏操作按钮，只保留数字人
            applyMessage("📺 PiP mode entered")
            runOnUiThread {
                binding.btnRecord.visibility = View.GONE
                binding.btnPlayPCM.visibility = View.GONE
                binding.btnPlayWAV.visibility = View.GONE
                binding.btnRandomMotion.visibility = View.GONE
                binding.btnStopPlay.visibility = View.GONE
                binding.btnSettings.visibility = View.GONE
                binding.switchMute.visibility = View.GONE
                binding.rvMotion.visibility = View.GONE
                binding.tvMotionTips.visibility = View.GONE
            }
        } else {
            // 退出 PiP（回到全屏）：恢复 UI
            applyMessage("📺 PiP mode exited")
            runOnUiThread {
                binding.btnRecord.visibility = View.VISIBLE
                applyPlayButtonsVisibility()
                binding.btnRandomMotion.visibility = if (supportedMotions.isNotEmpty())
                    View.VISIBLE else View.GONE
                binding.btnStopPlay.visibility = View.VISIBLE
                binding.btnSettings.visibility = View.VISIBLE
                binding.switchMute.visibility = View.VISIBLE
                if (supportedMotions.isNotEmpty()) {
                    binding.rvMotion.visibility = View.VISIBLE
                    binding.tvMotionTips.visibility = View.VISIBLE
                }
            }
            // PiP 退出后触发重新渲染
            digitalHumanEngine?.requestRender()
        }
    }

    override fun onDestroy() {
        // 先清除状态，防止 super.onDestroy() 处理消息队列时 postDelayed 触发录音
        isListening = false
        isSystemSpeaking = false
        pendingRestart?.let { binding.btnRecord.removeCallbacks(it) }
        pendingRestart = null
        realtimeManager.onSessionComplete = null
        stopWakeWordDetector()

        super.onDestroy()

        isStreamingMode = false
        loadingTimeoutRunnable?.let { binding.avatarContainer.removeCallbacks(it) }
        loadingTimeoutRunnable = null
        audioRecorder?.release()
        audioRecorder = null
        realtimeManager.release()
        digitalHumanEngine?.release()
        // 释放推送 WebSocket 客户端
        pushWsClient?.release()
        pushWsClient = null
        // 释放 Android TTS
        androidTts?.stop()
        androidTts?.shutdown()
        androidTts = null
    }

    /**
     * 播放 PCM 文件
     * @param filePath 用户自选的 PCM 文件路径；为空则播放默认 assets 文件
     */
    private fun playPCMStream(filePath: String? = null){
        val thread = Thread {
            val inputStream: InputStream = if (filePath != null) {
                FileInputStream(filePath)
            } else {
                assets.open("pcm/2.pcm")
            }
            val engine = digitalHumanEngine
            engine?.startPush()
            val buffer = ByteArray(320)
            var length = 0
            while (inputStream.read(buffer).also { length = it } > 0){
                val data = buffer.copyOfRange(0, length)
                engine?.pushPcm(data)
            }
            engine?.stopPush()
            inputStream.close()
        }
        thread.start()
    }

    /**
     * 播放 WAV 文件
     * @param filePath 用户自选的 WAV 文件路径；为空则使用默认 assets 文件
     */
    private fun playWAVFile(filePath: String? = null){
        val thread = Thread {
            val wavFile: File
            if (filePath != null) {
                wavFile = File(filePath)
            } else {
                val wavName = "1.wav"
                wavFile = File(mContext.externalCacheDir, wavName)
                if (!wavFile.exists()){
                    // copy assets -> sd card
                    val inputStream = assets.open("wav/$wavName")
                    if (!mContext.externalCacheDir!!.exists()){
                        mContext.externalCacheDir!!.mkdirs()
                    }
                    val out = FileOutputStream(wavFile)
                    val buffer = ByteArray(1024)
                    var length = 0
                    while ((inputStream.read(buffer).also { length = it }) > 0) {
                        out.write(buffer, 0, length)
                    }
                    out.close()
                    inputStream.close()
                }
            }
            digitalHumanEngine?.playAudio(wavFile.absolutePath)
        }
        thread.start()
    }

    // ===== 加载状态指示 =====

    /** 加载覆盖层的引用，用于启动时显示"正在加载数字人..." */
    private var loadingOverlay: android.widget.TextView? = null

    /** 在引擎初始化前显示加载提示 */
    private fun showLoadingOverlay() {
        runOnUiThread {
            val backendUrl = ListeningSettingsDialog.getSavedBackendUrl(this)
                .ifBlank { AppBuildConfig.BACKEND_BASE_URL }
            val tv = android.widget.TextView(mContext).apply {
                text = "正在连接后端...\n$backendUrl"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(android.graphics.Color.parseColor("#AA1A1A2E"))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            binding.avatarContainer.addView(tv)
            loadingOverlay = tv

            // 25秒超时：如果引擎仍未就绪，提示用户检查后端地址
            loadingTimeoutRunnable?.let { binding.avatarContainer.removeCallbacks(it) }
            val timeoutRunnable = Runnable {
                loadingOverlay?.let { overlay ->
                    overlay.text = "连接后端超时\n请检查后端地址是否正确\n或切换 DUIX 3D 引擎使用"
                }
            }
            loadingTimeoutRunnable = timeoutRunnable
            binding.avatarContainer.postDelayed(timeoutRunnable, 25000)
        }
    }

    /** 引擎加载成功后移除加载提示 */
    private fun removeLoadingOverlay() {
        runOnUiThread {
            loadingTimeoutRunnable?.let { binding.avatarContainer.removeCallbacks(it) }
            loadingTimeoutRunnable = null
            loadingOverlay?.let { v ->
                binding.avatarContainer.removeView(v)
                loadingOverlay = null
            }
        }
    }

    /** 引擎加载失败时显示错误 */
    private fun showLoadingError(errorText: String) {
        runOnUiThread {
            loadingTimeoutRunnable?.let { binding.avatarContainer.removeCallbacks(it) }
            loadingTimeoutRunnable = null
            loadingOverlay?.let { tv ->
                tv.text = errorText
                tv.postDelayed({
                    removeLoadingOverlay()
                }, 8000)
            }
        }
    }

    override fun permissionsGet(get: Boolean, code: Int) {
        super.permissionsGet(get, code)
        if (get){
            when (listeningMode) {
                ListeningMode.PUSH_TO_TALK -> {
                    // 按住说话模式：权限已获取，但不自动开始录音。
                    // 系统权限弹窗会吃掉 ACTION_UP 事件，用户需要再次按下按钮才能开始录音
                }
                ListeningMode.TOGGLE -> {
                    // 持续监听模式：权限已获取，进入监听
                    enterListeningMode()
                }
                ListeningMode.WAKE_WORD -> {
                    // 唤醒词模式：权限已获取，进入唤醒词监听
                    enterWakeWordMode()
                }
            }
        } else {
            Toast.makeText(mContext, R.string.need_permission_continue, Toast.LENGTH_SHORT).show()
        }
    }
}
