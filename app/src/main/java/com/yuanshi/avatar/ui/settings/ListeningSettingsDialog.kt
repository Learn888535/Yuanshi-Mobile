package com.yuanshi.avatar.ui.settings

import com.yuanshi.avatar.engine.DigitalHumanEngineType
import com.yuanshi.avatar.service.ListeningMode
import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import com.yuanshi.avatar.R
import java.io.File

/**
 * VAD 灵敏度级别
 */
enum class VadSensitivity {
    LOW,      // 低灵敏度：减少误触发，适合安静环境
    NORMAL,   // 默认
    HIGH      // 高灵敏度：更快响应，适合嘈杂环境
}

/**
 * 设置对话框
 *
 * 包含：
 * 1. 录音模式选择（按住说话 / 持续监听 / 唤醒词）
 * 2. 数字人引擎选择（3D / 视频）
 * 3. DUIX 3D 人物模型选择（仅 DUIX_3D 引擎时显示）
 * 4. VAD 灵敏度设置
 * 5. 后端地址配置（运行时修改，无需重新编译 APK）
 *
 * 所有设置保存到 SharedPreferences，App 重启后恢复。
 */
class ListeningSettingsDialog(
    private val context: Context,
    private val currentMode: ListeningMode,
    private val currentBackendUrl: String,
    private val currentVadSensitivity: VadSensitivity = VadSensitivity.NORMAL,
    private val currentEngineType: DigitalHumanEngineType = DigitalHumanEngineType.DUIX_3D,
    private val currentBackendProvider: String = "musetalk",
    /** 可用 DUIX 3D 模型列表（仅引擎为 DUIX_3D 时有效） */
    private val availableModels: List<String> = emptyList(),
    /** 当前选中的 DUIX 3D 模型名 */
    private val currentModelName: String = "",
    private val onModeSelected: (ListeningMode) -> Unit,
    private val onBackendUrlChanged: ((String) -> Unit)? = null,
    private val onVadSensitivityChanged: ((VadSensitivity) -> Unit)? = null,
    private val onShowPlayButtonsChanged: ((Boolean) -> Unit)? = null,
    private val onEngineTypeChanged: ((DigitalHumanEngineType) -> Unit)? = null,
    private val onBackendProviderChanged: ((String) -> Unit)? = null,
    private val onModelChanged: ((String) -> Unit)? = null
) {

    companion object {
        private const val PREFS_NAME = "duix_settings"
        private const val KEY_LISTENING_MODE = "listening_mode"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_VAD_SENSITIVITY = "vad_sensitivity"
        private const val KEY_SHOW_PLAY_BUTTONS = "show_play_buttons"
        private const val KEY_ENGINE_TYPE = "avatar_engine_type"
        private const val KEY_BACKEND_PROVIDER = "avatar_backend_provider"
        private const val KEY_MODEL_NAME = "model_url"

        /**
         * 扫描本地可用的 DUIX 3D 模型列表
         * 从 {externalFilesDir}/duix/model/ 下扫描有完整标记的子目录
         */
        fun getAvailableModels(context: Context): List<String> {
            val duixDir = context.getExternalFilesDir("duix") ?: return emptyList()
            val modelDir = File(duixDir, "model")
            if (!modelDir.exists()) return emptyList()

            val dirs = modelDir.listFiles { f -> f.isDirectory && f.name != "tmp" } ?: return emptyList()
            return dirs
                .filter { d ->
                    d.name != "gj_dh_res" &&
                    File(modelDir, "tmp/${d.name}").exists()
                }
                .sortedBy { it.name }
                .map { it.name }
        }

        /** 从 SharedPreferences 读取保存的 DUIX 3D 模型名 */
        fun getSavedModelName(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_MODEL_NAME, "") ?: ""
        }

        /** 保存 DUIX 3D 模型名到 SharedPreferences */
        fun saveModelName(context: Context, name: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_MODEL_NAME, name).apply()
        }

        /** 从 SharedPreferences 中读取保存的录音模式 */
        fun getSavedMode(context: Context): ListeningMode {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val ordinal = prefs.getInt(KEY_LISTENING_MODE, ListeningMode.TOGGLE.ordinal)
            return try {
                ListeningMode.values().first { it.ordinal == ordinal }
            } catch (_: Exception) {
                ListeningMode.TOGGLE
            }
        }

        /** 从 SharedPreferences 读取保存的后端地址，为空时使用 BuildConfig 的编译值 */
        fun getSavedBackendUrl(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_BACKEND_URL, "") ?: ""
        }

        /** 保存后端地址到 SharedPreferences */
        fun saveBackendUrl(context: Context, url: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_BACKEND_URL, url).apply()
        }

        /** 从 SharedPreferences 读取保存的 VAD 灵敏度 */
        fun getSavedVadSensitivity(context: Context): VadSensitivity {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val ordinal = prefs.getInt(KEY_VAD_SENSITIVITY, VadSensitivity.NORMAL.ordinal)
            return try {
                VadSensitivity.values().first { it.ordinal == ordinal }
            } catch (_: Exception) {
                VadSensitivity.NORMAL
            }
        }

        /** 从 SharedPreferences 读取是否显示 WAV/PCM 播放按钮 */
        fun getSavedShowPlayButtons(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_SHOW_PLAY_BUTTONS, false)
        }

        /** 从 SharedPreferences 读取保存的数字人引擎类型 */
        fun getSavedEngineType(context: Context): DigitalHumanEngineType {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // 默认为 DUIX_3D
            val id = prefs.getString(KEY_ENGINE_TYPE, DigitalHumanEngineType.DUIX_3D.id)
                ?: DigitalHumanEngineType.DUIX_3D.id
            return DigitalHumanEngineType.fromId(id)
        }

        /** 从 SharedPreferences 读取保存的后端引擎模式（musetalk / wav2lip / mock） */
        fun getSavedBackendProvider(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_BACKEND_PROVIDER, "musetalk") ?: "musetalk"
        }

        /** 保存后端引擎模式到 SharedPreferences */
        fun saveBackendProvider(context: Context, mode: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_BACKEND_PROVIDER, mode).apply()
        }
    }

    fun show() {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_settings, null)

        // 录音模式 RadioGroup
        val rgListeningMode = view.findViewById<RadioGroup>(R.id.rgListeningMode)
        val modeValues = listOf(
            R.id.rbPushToTalk to ListeningMode.PUSH_TO_TALK,
            R.id.rbToggle to ListeningMode.TOGGLE,
            R.id.rbWakeWord to ListeningMode.WAKE_WORD
        )
        for ((id, mode) in modeValues) {
            if (mode == currentMode) {
                rgListeningMode.check(id)
                break
            }
        }

        // VAD 灵敏度 RadioGroup
        val rgVad = view.findViewById<RadioGroup>(R.id.rgVadSensitivity)
        val vadValues = listOf(
            R.id.rbVadLow to VadSensitivity.LOW,
            R.id.rbVadNormal to VadSensitivity.NORMAL,
            R.id.rbVadHigh to VadSensitivity.HIGH
        )
        for ((id, sens) in vadValues) {
            if (sens == currentVadSensitivity) {
                rgVad.check(id)
                break
            }
        }

        // 数字人引擎 RadioGroup
        val rgEngine = view.findViewById<RadioGroup>(R.id.rgAvatarEngine)
        val engineValues = listOf(
            R.id.rbEngineDuix3d to DigitalHumanEngineType.DUIX_3D,
            R.id.rbEngineVideoAvatar to DigitalHumanEngineType.VIDEO_AVATAR,
            R.id.rbEngineWebrtcAvatar to DigitalHumanEngineType.WEBRTC_AVATAR
        )
        for ((id, type) in engineValues) {
            if (type == currentEngineType) {
                rgEngine.check(id)
                break
            }
        }

        // 后端数字人引擎 RadioGroup
        val llBackendProvider = view.findViewById<android.widget.LinearLayout>(R.id.llBackendProvider)
        val rgBackendProvider = view.findViewById<RadioGroup>(R.id.rgBackendProvider)
        val providerValues = listOf(
            R.id.rbProviderMusetalk to "musetalk",
            R.id.rbProviderWav2lip to "wav2lip",
            R.id.rbProviderMock to "mock"
        )
        for ((id, mode) in providerValues) {
            if (mode == currentBackendProvider) {
                rgBackendProvider.check(id)
                break
            }
        }

        // DUIX 3D 人物模型（动态填充）
        val llDuixModel = view.findViewById<android.widget.LinearLayout>(R.id.llDuixModel)
        val rgDuixModel = view.findViewById<RadioGroup>(R.id.rgDuixModel)
        if (availableModels.isNotEmpty()) {
            rgDuixModel.removeAllViews()
            for (model in availableModels) {
                val rb = RadioButton(context).apply {
                    text = model
                    id = View.generateViewId()
                    if (model == currentModelName) {
                        isChecked = true
                    }
                    setPadding(0, 12, 0, 12)
                    textSize = 14f
                }
                rgDuixModel.addView(rb)
            }
        }

        // 根据引擎选择显示/隐藏后端提供者区域和 DUIX 模型区域
        rgEngine.setOnCheckedChangeListener { _, checkedId ->
            llBackendProvider.visibility = when (checkedId) {
                R.id.rbEngineVideoAvatar -> android.view.View.VISIBLE
                else -> android.view.View.GONE
            }
            llDuixModel.visibility = when (checkedId) {
                R.id.rbEngineDuix3d -> if (availableModels.isNotEmpty()) android.view.View.VISIBLE
                                       else android.view.View.GONE
                else -> android.view.View.GONE
            }
        }
        // 初始状态
        llBackendProvider.visibility = if (currentEngineType == DigitalHumanEngineType.VIDEO_AVATAR)
            android.view.View.VISIBLE else android.view.View.GONE
        llDuixModel.visibility = if (currentEngineType == DigitalHumanEngineType.DUIX_3D && availableModels.isNotEmpty())
            android.view.View.VISIBLE else android.view.View.GONE

        // 后端地址输入框
        val etBackendUrl = view.findViewById<EditText>(R.id.etBackendUrl)
        etBackendUrl.setText(currentBackendUrl)
        etBackendUrl.setSelection(currentBackendUrl.length)

        // 显示 WAV/PCM 播放按钮
        val cbShowPlayButtons = view.findViewById<android.widget.CheckBox>(R.id.cbShowPlayButtons)
        cbShowPlayButtons.isChecked = getSavedShowPlayButtons(context)

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.settings)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                // 保存录音模式
                val checkedId = rgListeningMode.checkedRadioButtonId
                val selectedMode = when (checkedId) {
                    R.id.rbPushToTalk -> ListeningMode.PUSH_TO_TALK
                    R.id.rbWakeWord -> ListeningMode.WAKE_WORD
                    else -> ListeningMode.TOGGLE
                }
                prefs.edit().putInt(KEY_LISTENING_MODE, selectedMode.ordinal).apply()

                // 保存 VAD 灵敏度
                val vadCheckedId = rgVad.checkedRadioButtonId
                val selectedVad = when (vadCheckedId) {
                    R.id.rbVadLow -> VadSensitivity.LOW
                    R.id.rbVadHigh -> VadSensitivity.HIGH
                    else -> VadSensitivity.NORMAL
                }
                prefs.edit().putInt(KEY_VAD_SENSITIVITY, selectedVad.ordinal).apply()

                // 保存数字人引擎
                val engineCheckedId = rgEngine.checkedRadioButtonId
                val selectedEngine = when (engineCheckedId) {
                    R.id.rbEngineVideoAvatar -> DigitalHumanEngineType.VIDEO_AVATAR
                    R.id.rbEngineWebrtcAvatar -> DigitalHumanEngineType.WEBRTC_AVATAR
                    else -> DigitalHumanEngineType.DUIX_3D
                }
                val oldEngineType = prefs.getString(KEY_ENGINE_TYPE, DigitalHumanEngineType.DUIX_3D.id)
                val engineChanged = selectedEngine.id != oldEngineType
                if (engineChanged) {
                    prefs.edit().putString(KEY_ENGINE_TYPE, selectedEngine.id).apply()
                    onEngineTypeChanged?.invoke(selectedEngine)
                }

                // 保存 DUIX 3D 人物模型（仅 DUIX_3D 引擎时有效）
                val modelCheckedId = rgDuixModel.checkedRadioButtonId
                if (modelCheckedId != -1 && availableModels.isNotEmpty()) {
                    val checkedRb = rgDuixModel.findViewById<RadioButton>(modelCheckedId)
                    val selectedModel = checkedRb?.text?.toString() ?: ""
                    val oldModel = prefs.getString(KEY_MODEL_NAME, "") ?: ""
                    if (selectedModel.isNotBlank() && selectedModel != oldModel) {
                        prefs.edit().putString(KEY_MODEL_NAME, selectedModel).apply()
                        // 仅在引擎没变时触发模型切换回调（引擎变了的话，restartEngine 会读取新模型）
                        if (!engineChanged) {
                            onModelChanged?.invoke(selectedModel)
                        }
                    }
                }

                // 保存后端数字人引擎模式
                val providerCheckedId = rgBackendProvider.checkedRadioButtonId
                val selectedProvider = when (providerCheckedId) {
                    R.id.rbProviderWav2lip -> "wav2lip"
                    R.id.rbProviderMock -> "mock"
                    else -> "musetalk"
                }
                val oldProvider = prefs.getString(KEY_BACKEND_PROVIDER, "musetalk")
                if (selectedProvider != oldProvider) {
                    prefs.edit().putString(KEY_BACKEND_PROVIDER, selectedProvider).apply()
                    onBackendProviderChanged?.invoke(selectedProvider)
                }

                // 保存后端地址
                val newUrl = etBackendUrl.text.toString().trim()
                val oldUrl = prefs.getString(KEY_BACKEND_URL, "") ?: ""
                if (newUrl != oldUrl) {
                    saveBackendUrl(context, newUrl)
                    onBackendUrlChanged?.invoke(newUrl)
                }

                // 保存显示播放按钮设置
                val showPlayButtons = cbShowPlayButtons.isChecked
                prefs.edit().putBoolean(KEY_SHOW_PLAY_BUTTONS, showPlayButtons).apply()

                onModeSelected(selectedMode)
                onVadSensitivityChanged?.invoke(selectedVad)
                onShowPlayButtonsChanged?.invoke(showPlayButtons)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
