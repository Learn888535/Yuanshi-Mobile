package com.yuanshi.avatar.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yuanshi.avatar.R
import com.yuanshi.avatar.databinding.ActivityMainBinding
import com.yuanshi.avatar.databinding.ItemLocalModelBinding
import com.yuanshi.avatar.service.ModelAssetInstaller
import com.yuanshi.avatar.ui.base.BaseActivity
import com.yuanshi.avatar.ui.call.CallActivity
import java.io.File

/**
 * 原世数字人 · 主页
 *
 * 自动扫描本地已有模型，用户点选即用。
 * 模型存放路径：{externalFilesDir}/duix/model/<模型名>
 */
class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: LocalModelAdapter

    /** 本地可用模型列表 */
    private val localModels = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupViews()

            // 首次启动：从 assets 安装模型
            // 注意：必须在后台线程执行，否则 132MB+ 的文件复制会阻塞主线程导致 ANR
            if (!ModelAssetInstaller.isInstalled(this)) {
                binding.tvEmptyHint.text = "正在安装模型文件，请稍候…"
                binding.tvEmptyHint.visibility = View.VISIBLE
                binding.rvModels.visibility = View.GONE
                binding.tvBaseConfigStatus.text = "安装中…"
                // 在后台线程安装模型
                Thread {
                    try {
                        val success = ModelAssetInstaller.installFromAssets(this)
                        runOnUiThread {
                            if (success) {
                                binding.tvEmptyHint.text = "安装完成"
                            } else {
                                binding.tvEmptyHint.text = "模型安装失败，请重新启动应用"
                            }
                            // 安装完成后扫描模型（无论成功失败）
                            scanLocalModels()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "后台安装模型失败", e)
                        runOnUiThread {
                            binding.tvEmptyHint.text = "安装出错: ${e.message}"
                            binding.tvEmptyHint.visibility = View.VISIBLE
                        }
                    }
                }.start()
            } else {
                scanLocalModels()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MainActivity onCreate 崩溃", e)
            // 出错了也显示一个简单界面
            try {
                setContentView(TextView(this).apply {
                    text = "启动失败: ${e.message}\n\n请截图并联系开发者"
                    textSize = 16f
                    setPadding(32, 32, 32, 32)
                })
            } catch (_: Exception) {}
        }
    }

    private fun setupViews() {
        // RecyclerView 设置
        binding.rvModels.layoutManager = LinearLayoutManager(this)
        adapter = LocalModelAdapter(
            onItemClick = { modelName -> launchModel(modelName) },
            onItemDelete = { modelName -> confirmDeleteModel(modelName) }
        )
        binding.rvModels.adapter = adapter

        // 帮助提示点击
        binding.tvHelp.setOnClickListener {
            Toast.makeText(
                this,
                "请将模型文件夹放入:\n${getModelBaseDir().absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── 扫描本地模型 ──

    private fun scanLocalModels() {
        val modelDir = getModelBaseDir()
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }

        localModels.clear()

        val dirs = modelDir.listFiles { f -> f.isDirectory && f.name != "tmp" }
        if (dirs != null) {
            // 过滤：必须有对应标记文件 tmp/<name> 才算完整
            // 排除基础配置目录和临时目录
            for (d in dirs.sortedBy { it.name }) {
                if (d.name == "gj_dh_res" || d.name == "tmp") continue
                val tagFile = File(modelDir, "tmp/${d.name}")
                if (tagFile.exists()) {
                    localModels.add(d.name)
                }
            }
        }

        // 更新基础配置状态
        updateBaseConfigStatus()

        // 刷新列表
        if (localModels.isEmpty()) {
            binding.tvEmptyHint.visibility = View.VISIBLE
            binding.rvModels.visibility = View.GONE
        } else {
            binding.tvEmptyHint.visibility = View.GONE
            binding.rvModels.visibility = View.VISIBLE
            adapter.submitList(localModels.toList())
        }
    }

    private fun updateBaseConfigStatus() {
        val baseConfigDir = File(getModelBaseDir(), "gj_dh_res")
        val baseConfigTag = File(getModelBaseDir(), "tmp/gj_dh_res")
        val ok = baseConfigDir.exists() && baseConfigTag.exists()
        binding.tvBaseConfigStatus.text = if (ok) "✅ 就绪" else "❌ 未安装"
        binding.tvBaseConfigStatus.setTextColor(
            resources.getColor(if (ok) R.color.safe_green else R.color.error_red, theme)
        )
    }

    // ── 启动模型 ──

    private fun launchModel(modelName: String) {
        // 检查基础配置
        val baseConfigDir = File(getModelBaseDir(), "gj_dh_res")
        val baseConfigTag = File(getModelBaseDir(), "tmp/gj_dh_res")
        if (!baseConfigDir.exists() || !baseConfigTag.exists()) {
            Toast.makeText(
                this,
                "缺少基础配置 (gj_dh_res)，请先通过 PC 端放入模型文件",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // 检查模型完整性
        val modelDir = File(getModelBaseDir(), modelName)
        val modelTag = File(getModelBaseDir(), "tmp/$modelName")
        if (!modelDir.exists() || !modelTag.exists()) {
            Toast.makeText(this, "模型 [$modelName] 不完整，请重新放入", Toast.LENGTH_SHORT).show()
            return
        }

        // 跳转到播放页
        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra("modelUrl", modelName)
            putExtra("debug", binding.switchDebug.isChecked)
        }
        startActivity(intent)
    }

    // ── 模型删除 ──

    /** 确认是否删除模型 — 弹出确认对话框 */
    private fun confirmDeleteModel(modelName: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("删除模型")
            .setMessage("确定要删除模型「$modelName」吗？\n此操作不可恢复！")
            .setPositiveButton("删除") { _, _ ->
                deleteModel(modelName)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 执行模型删除 */
    private fun deleteModel(modelName: String) {
        val modelDir = File(getModelBaseDir(), modelName)
        val tagFile = File(getModelBaseDir(), "tmp/$modelName")

        // 删除标记文件
        var success = true
        if (tagFile.exists()) {
            success = tagFile.delete()
        }
        // 递归删除模型目录
        if (modelDir.exists()) {
            success = modelDir.deleteRecursively()
        }

        if (success) {
            Toast.makeText(this, "模型「$modelName」已删除", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "部分文件删除失败，请手动清理", Toast.LENGTH_LONG).show()
        }

        // 刷新列表
        scanLocalModels()
    }

    // ── 工具方法 ──

    /** 模型根目录：{externalFilesDir}/duix/model/ */
    private fun getModelBaseDir(): File {
        val duixDir = getExternalFilesDir("duix") ?: filesDir
        return File(duixDir, "model")
    }

    override fun onResume() {
        super.onResume()
        // 从 CallActivity 返回后重新扫描（可能通过 PC 新传了模型）
        // 注意：如果 onCreate 中 binding 初始化失败（抛异常被 catch），这里不能使用 binding
        if (::binding.isInitialized) {
            scanLocalModels()
        }
    }

    // ── RecyclerView 适配器 ──

    private class LocalModelAdapter(
        private val onItemClick: (String) -> Unit,
        private val onItemDelete: (String) -> Unit
    ) : RecyclerView.Adapter<LocalModelAdapter.ViewHolder>() {

        private var items: List<String> = emptyList()

        fun submitList(list: List<String>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemLocalModelBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        inner class ViewHolder(
            private val binding: ItemLocalModelBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(modelName: String) {
                binding.tvModelName.text = modelName
                binding.tvModelPath.text = "本地模型"

                // 点击整个卡片
                binding.root.setOnClickListener {
                    onItemClick(modelName)
                }

                // 点击播放按钮
                binding.tvPlayBtn.setOnClickListener {
                    onItemClick(modelName)
                }

                // 点击删除按钮
                binding.tvDeleteBtn.setOnClickListener {
                    onItemDelete(modelName)
                }
            }
        }
    }
}
