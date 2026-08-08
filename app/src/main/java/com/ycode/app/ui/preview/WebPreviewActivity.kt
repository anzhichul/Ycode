package com.ycode.app.ui.preview

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ycode.app.R
import com.ycode.app.YcodeApp
import com.ycode.app.databinding.ActivityWebPreviewBinding
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WebPreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWebPreviewBinding
    private val store get() = (application as YcodeApp).store
    private var entries = emptyList<WorkspaceWebRuntime.Entry>()
    private var currentPath = ""
    private var fullScreen = false
    private var logsVisible = false
    private val createZip = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(::writeZip) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        binding = ActivityWebPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.webView.setBackgroundColor(Color.WHITE)
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return null
                if (url.host != HOST) return null
                val root = WorkspaceWebRuntime.root(this@WebPreviewActivity, store.workspaceUri) ?: return missing("工作目录授权已失效")
                val file = WorkspaceWebRuntime.resolve(root, url.encodedPath.orEmpty()) ?: return missing("资源不存在：${url.path}")
                if (!file.isFile) return missing("不是文件：${url.path}")
                val stream = contentResolver.openInputStream(file.uri) ?: return missing("资源无法读取：${url.path}")
                return WebResourceResponse(WorkspaceWebRuntime.mime(url.path.orEmpty()), if (WorkspaceWebRuntime.mime(url.path.orEmpty()).startsWith("text/") || WorkspaceWebRuntime.mime(url.path.orEmpty()) in setOf("application/javascript", "application/json", "application/xml")) "UTF-8" else null, stream)
            }
        }
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val line = "${message.messageLevel()}  ${message.message()}  (${message.sourceId().substringAfterLast('/')}:${message.lineNumber()})"
                binding.logs.append(if (binding.logs.text.isEmpty()) line else "\n$line")
                binding.logCount.text = binding.logs.lineCount.coerceAtLeast(1).toString()
                return true
            }
        }
        binding.close.setOnClickListener { finish() }
        binding.entry.setOnClickListener { chooseEntry() }
        binding.refresh.setOnClickListener { binding.logs.text = ""; binding.logCount.text = "0"; load(currentPath) }
        binding.logsButton.setOnClickListener { logsVisible = !logsVisible; binding.logPanel.visibility = if (logsVisible) android.view.View.VISIBLE else android.view.View.GONE }
        binding.maximize.setOnClickListener { toggleSize() }
        binding.pack.setOnClickListener { createZip.launch("${store.workspaceGrant?.name ?: "ycode-project"}.zip") }
        refreshEntries(intent.getStringExtra(EXTRA_PATH))
    }

    private fun refreshEntries(preferred: String?) {
        val root = WorkspaceWebRuntime.root(this, store.workspaceUri) ?: return fail("请先选择工作目录")
        entries = WorkspaceWebRuntime.webEntries(root)
        val html = entries.filter { it.path.substringAfterLast('.', "").lowercase() in setOf("html", "htm") }
        if (html.isEmpty()) {
            if (entries.any { it.path.endsWith(".php", true) }) fail("检测到 PHP 文件。PHP 需要服务器和解释器，当前网页运行器不能直接运行；请先生成静态 HTML 入口。")
            else fail("工作目录中没有 HTML 入口文件")
            return
        }
        currentPath = html.firstOrNull { it.path == preferred }?.path ?: html.first().path
        binding.entry.text = currentPath.substringAfterLast('/') + " ⌄"
        load(currentPath)
    }

    private fun chooseEntry() {
        val html = entries.filter { it.path.substringAfterLast('.', "").lowercase() in setOf("html", "htm") }
        MaterialAlertDialogBuilder(this).setTitle("选择网页入口").setItems(html.map { it.path }.toTypedArray()) { _, index ->
            currentPath = html[index].path
            binding.entry.text = currentPath.substringAfterLast('/') + " ⌄"
            binding.logs.text = ""; binding.logCount.text = "0"
            load(currentPath)
        }.show()
    }

    private fun load(path: String) {
        if (path.isBlank()) return
        binding.webView.loadUrl("https://$HOST/${path.split('/').joinToString("/") { Uri.encode(it) }}")
    }

    private fun toggleSize() {
        fullScreen = !fullScreen
        val margin = if (fullScreen) 0 else dp(12)
        (binding.previewWindow.layoutParams as FrameLayout.LayoutParams).apply { setMargins(margin, margin, margin, margin); binding.previewWindow.layoutParams = this }
        binding.maximize.text = if (fullScreen) "还原" else "全屏"
    }

    private fun writeZip(uri: Uri) {
        val root = WorkspaceWebRuntime.root(this, store.workspaceUri) ?: return fail("工作目录授权已失效")
        runCatching {
            contentResolver.openOutputStream(uri, "wt")?.use { output ->
                ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                    WorkspaceWebRuntime.walk(root, "", 0) { path, file ->
                        if (file.isFile) {
                            zip.putNextEntry(ZipEntry(path))
                            contentResolver.openInputStream(file.uri)?.use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            } ?: error("无法创建压缩包")
        }.onSuccess { toast("项目已打包为 ZIP") }.onFailure { fail(it.message ?: "打包失败") }
    }

    private fun missing(text: String) = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(text.toByteArray()))
    private fun fail(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() { binding.webView.stopLoading(); binding.webView.destroy(); super.onDestroy() }

    companion object {
        const val EXTRA_PATH = "path"
        private const val HOST = "workspace.ycode.local"
    }
}
