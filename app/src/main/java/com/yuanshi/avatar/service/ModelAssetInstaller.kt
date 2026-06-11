package com.yuanshi.avatar.service

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 模型资源安装器
 *
 * 将 APK assets 中打包的模型文件复制到外部存储的正确位置。
 * 首次启动时自动执行，复制完成后在 SharedPreferences 中标记。
 *
 * assets 目录结构：
 *   assets/duix/model/
 *     ├── gj_dh_res/        ← 基础配置
 *     │   └── ...
 *     ├── tmp/
 *     │   ├── gj_dh_res     ← 基础配置标记
 *     │   ├── Lily          ← 模型标记
 *     │   └── ...
 *     └── Lily/             ← 模型文件
 *         └── ...
 */
object ModelAssetInstaller {

    private const val TAG = "ModelAssetInstaller"
    private const val PREFS_NAME = "model_install"
    private const val KEY_INSTALLED = "assets_installed_version"

    /**
     * 是否需要安装（检查标记文件是否存在）
     */
    fun isInstalled(context: Context): Boolean {
        val duixDir = context.getExternalFilesDir("duix") ?: return false
        val tagFile = File(duixDir, "model/tmp/gj_dh_res")
        return tagFile.exists()
    }

    /**
     * 从 assets 复制模型到外部存储
     * @return true 表示安装成功，false 表示 assets 中没有模型
     */
    fun installFromAssets(context: Context): Boolean {
        val duixDir = context.getExternalFilesDir("duix") ?: run {
            Log.w(TAG, "getExternalFilesDir 返回 null")
            return false
        }
        val modelDir = File(duixDir, "model")

        // 检查 assets 中是否有模型
        if (!hasModelAssets(context)) {
            Log.i(TAG, "assets 中未找到模型，跳过安装")
            return false
        }

        Log.i(TAG, "开始从 assets 安装模型到: ${modelDir.absolutePath}")

        try {
            // 复制 gj_dh_res 基础配置
            copyAssetDirectory(context, "duix/model/gj_dh_res", File(modelDir, "gj_dh_res"))
            // 复制 tmp 标记文件
            copyAssetDirectory(context, "duix/model/tmp", File(modelDir, "tmp"))

            // 扫描并复制所有模型目录
            val assetModelPath = "duix/model"
            val entries = context.assets.list(assetModelPath) ?: emptyArray()
            for (entry in entries) {
                if (entry == "gj_dh_res" || entry == "tmp") continue
                // 检查是否是目录（通过尝试列出其内容）
                val subEntries = context.assets.list("$assetModelPath/$entry")
                if (subEntries != null && subEntries.isNotEmpty()) {
                    copyAssetDirectory(context, "$assetModelPath/$entry", File(modelDir, entry))
                }
            }

            Log.i(TAG, "模型安装完成")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "模型安装失败: ${e.message}", e)
            return false
        }
    }

    /** 检查 assets 中是否存在模型 */
    private fun hasModelAssets(context: Context): Boolean {
        return try {
            val entries = context.assets.list("duix/model") ?: emptyArray()
            entries.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 递归复制 assets 中的目录到目标路径
     */
    private fun copyAssetDirectory(context: Context, assetPath: String, destDir: File) {
        val entries = context.assets.list(assetPath) ?: return

        destDir.mkdirs()

        for (entry in entries) {
            val childAssetPath = "$assetPath/$entry"
            val childDestFile = File(destDir, entry)

            // 尝试列出下级内容 — 有内容说明是目录
            val subEntries = try {
                context.assets.list(childAssetPath)
            } catch (e: Exception) {
                null
            }

            if (subEntries != null && subEntries.isNotEmpty()) {
                // 是目录，递归复制
                copyAssetDirectory(context, childAssetPath, childDestFile)
            } else {
                // 是文件，复制
                copyAssetFile(context, childAssetPath, childDestFile)
            }
        }
    }

    /** 复制单个 asset 文件 */
    private fun copyAssetFile(context: Context, assetPath: String, destFile: File) {
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "复制文件失败: $assetPath → ${destFile.path}: ${e.message}")
        }
    }
}
