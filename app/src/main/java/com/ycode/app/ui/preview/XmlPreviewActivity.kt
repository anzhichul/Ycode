package com.ycode.app.ui.preview

import android.net.Uri
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ycode.app.YcodeApp
import com.ycode.app.databinding.ActivityXmlPreviewBinding

class XmlPreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityXmlPreviewBinding
    private val store get() = (application as YcodeApp).store
    private var entries = emptyList<WorkspaceWebRuntime.Entry>()
    private var currentPath = ""
    private var fullScreen = false
    private var logsVisible = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        binding = ActivityXmlPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.close.setOnClickListener { finish() }
        binding.entry.setOnClickListener { chooseEntry() }
        binding.refresh.setOnClickListener { load() }
        binding.logsButton.setOnClickListener { logsVisible = !logsVisible; binding.logPanel.visibility = if (logsVisible) android.view.View.VISIBLE else android.view.View.GONE }
        binding.maximize.setOnClickListener { toggleSize() }
        val root = WorkspaceWebRuntime.root(this, store.workspaceUri) ?: return fail("请先选择工作目录")
        entries = WorkspaceWebRuntime.layoutEntries(root)
        if (entries.isEmpty()) return fail("未找到 res/layout 目录中的 XML 布局")
        currentPath = entries.firstOrNull { it.path == intent.getStringExtra(EXTRA_PATH) }?.path ?: entries.first().path
        load()
    }

    private fun chooseEntry() = MaterialAlertDialogBuilder(this).setTitle("选择 XML 布局").setItems(entries.map { it.path }.toTypedArray()) { _, index -> currentPath = entries[index].path; load() }.show()

    private fun load() {
        val entry = entries.firstOrNull { it.path == currentPath } ?: return
        binding.entry.text = currentPath.substringAfterLast('/') + " ⌄"
        binding.canvas.removeAllViews()
        val renderer = AndroidXmlRenderer(this)
        runCatching { contentResolver.openInputStream(entry.file.uri)?.use(renderer::render) ?: error("无法读取 XML") }
            .onSuccess { view -> binding.canvas.addView(view); binding.logs.text = (renderer.logs.ifEmpty { listOf("布局解析完成，没有警告") }).joinToString("\n"); binding.logCount.text = renderer.logs.size.toString() }
            .onFailure { error -> binding.logs.text = "XML 解析失败：${error.message}"; binding.logCount.text = "1"; logsVisible = true; binding.logPanel.visibility = android.view.View.VISIBLE }
    }

    private fun toggleSize() {
        fullScreen = !fullScreen
        val margin = if (fullScreen) 0 else dp(12)
        (binding.previewWindow.layoutParams as FrameLayout.LayoutParams).apply { setMargins(margin, margin, margin, margin); binding.previewWindow.layoutParams = this }
        binding.maximize.text = if (fullScreen) "还原" else "全屏"
    }

    private fun fail(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { const val EXTRA_PATH = "path" }
}
