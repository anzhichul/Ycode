package com.ycode.app.ui.workspace

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ycode.app.R
import com.ycode.app.YcodeApp
import com.ycode.app.databinding.ActivityWorkspaceBinding
import com.ycode.app.model.DirectoryGrant
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File

class WorkspaceActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWorkspaceBinding
    private val store get() = (application as YcodeApp).store
    private var selectingAdditional = false

    private val selectTree = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uri = data.data ?: return@registerForActivityResult
        val returnedFlags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val grant = persistGrant(uri, returnedFlags)
        if (selectingAdditional) addAdditionalGrant(grant) else setWorkspaceGrant(grant)
        selectingAdditional = false
        refresh()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        intent.getStringExtra(EXTRA_CONVERSATION_ID)?.takeIf(String::isNotBlank)?.let(store::activateConversation)
        binding = ActivityWorkspaceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.files.layoutManager = LinearLayoutManager(this)
        binding.back.setOnClickListener { finish() }
        binding.select.setOnClickListener { selectingAdditional = false; openTreePicker(store.workspaceGrant?.uri?.let(Uri::parse)) }
        binding.addAdditional.setOnClickListener { selectingAdditional = true; openTreePicker(null) }
        binding.clearWorkspace.setOnClickListener { confirmClearWorkspace() }
        binding.createTest.setOnClickListener { createTest() }
        binding.exportLogs.setOnClickListener { exportLogs() }
        binding.clearLogs.setOnClickListener { confirmClearLogs() }
        refresh()
    }

    private fun openTreePicker(initialUri: Uri?) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            if (android.os.Build.VERSION.SDK_INT >= 26 && initialUri != null) putExtra("android.provider.extra.INITIAL_URI", initialUri)
        }
        runCatching { selectTree.launch(intent) }.onFailure {
            toast("系统文件选择器无法打开：${it.message}")
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun persistGrant(uri: Uri, returnedFlags: Int): DirectoryGrant {
        val usableFlags = returnedFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        var persisted = false
        if (usableFlags != 0) {
            persisted = runCatching { contentResolver.takePersistableUriPermission(uri, usableFlags); true }.getOrDefault(false)
        }
        val name = readableDirectoryName(uri)
        val result = DirectoryGrant(name, uri.toString(), usableFlags)
        store.addTaskLog("directory_granted", "workspace:/", if (persisted) "系统目录授权已持久保存" else "系统仅提供当前会话授权", if (persisted) "saved" else "session")
        if (!persisted) toast("目录已选择，但系统未提供持久授权；重启后可能需要重新选择")
        return result
    }

    private fun setWorkspaceGrant(grant: DirectoryGrant) {
        store.workspaceGrant?.let(::releaseGrant)
        store.workspaceGrant = grant
        store.addTaskLog("workspace_changed", "workspace:/", grant.name, "saved")
    }

    private fun addAdditionalGrant(grant: DirectoryGrant) {
        val grants = store.additionalGrants()
        if (grants.none { it.uri == grant.uri }) grants.add(grant)
        store.saveAdditionalGrants(grants)
        store.addTaskLog("additional_directory_granted", grant.uri, grant.name, "saved")
    }

    private fun refresh() {
        if (store.workspaceGrant == null && store.workspaceUri != null) {
            val oldUri = store.workspaceUri!!
            val permission = contentResolver.persistedUriPermissions.firstOrNull { it.uri == oldUri }
            val flags = (if (permission?.isReadPermission == true) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or (if (permission?.isWritePermission == true) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            val name = readableDirectoryName(oldUri)
            store.workspaceGrant = DirectoryGrant(name, oldUri.toString(), flags)
        }
        var grant = store.workspaceGrant
        grant?.let { saved ->
            val readable = readableDirectoryName(Uri.parse(saved.uri))
            if (saved.name != readable) {
                grant = saved.copy(name = readable)
                store.workspaceGrant = grant
            }
        }
        val root = grant?.let { DocumentFile.fromTreeUri(this, Uri.parse(it.uri)) }
        val persisted = grant?.let { saved -> contentResolver.persistedUriPermissions.any { it.uri.toString() == saved.uri } } == true
        binding.scope.text = if (grant == null) "未设置工作目录\n文件工具需要先通过系统选择器授权" else "${grant.name}\nworkspace:/ · ${if (grant.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) "可读写" else "只读"}${if (persisted) " · 已持久授权" else " · 当前会话授权"}"
        binding.workspacePath.text = grant?.let { "${it.name}  ·  workspace:/" } ?: "尚未选择"
        binding.select.text = if (grant == null) "选择工作目录" else "重新选择目录"
        binding.clearWorkspace.visibility = if (grant == null) View.GONE else View.VISIBLE
        binding.createTest.isEnabled = root != null && grant.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
        renderFiles(root)
        renderAdditionalGrants()
        renderLogs()
    }

    private fun renderFiles(root: DocumentFile?) {
        val files = runCatching { root?.listFiles()?.take(1000)?.sortedWith(compareByDescending<DocumentFile> { it.isDirectory }.thenBy { it.name }) }.getOrNull() ?: emptyList()
        binding.files.adapter = object : RecyclerView.Adapter<Holder>() {
            override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(TextView(parent.context).apply { setPadding(28, 22, 28, 22); textSize = 14f })
            override fun getItemCount() = files.size
            override fun onBindViewHolder(holder: Holder, position: Int) {
                val file = files[position]
                holder.text.text = "${if (file.isDirectory) "▰" else "·"}  ${file.name}"
                holder.text.setOnClickListener { if (file.isFile) preview(file.uri) }
            }
        }
    }

    private fun renderAdditionalGrants() {
        val grants = store.additionalGrants().map { grant ->
            val readable = readableDirectoryName(Uri.parse(grant.uri))
            if (grant.name == readable) grant else grant.copy(name = readable)
        }
        if (grants != store.additionalGrants()) store.saveAdditionalGrants(grants)
        binding.additionalCount.text = "${grants.size} 个"
        binding.additionalList.removeAllViews()
        if (grants.isEmpty()) binding.additionalList.addView(infoText("暂无额外授权目录。AI 需要其他目录时，可在这里通过系统选择器添加。"))
        grants.forEachIndexed { index, grant ->
            binding.additionalList.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(8, 10, 8, 10)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply { text = grant.name; setTextColor(getColor(R.color.text)); textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
                    addView(TextView(context).apply { text = "extra:/$index/ · ${if (grant.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) "可读写" else "只读"}"; setTextColor(getColor(R.color.muted)); textSize = 10f; maxLines = 1 })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(context).apply {
                    text = "撤销"; setTextColor(getColor(R.color.red)); setPadding(16, 12, 16, 12)
                    setOnClickListener { revokeAdditional(grant) }
                })
            })
        }
    }

    private fun renderLogs() {
        val logs = store.conversationTaskLogs().take(50)
        binding.logList.removeAllViews()
        if (logs.isEmpty()) binding.logList.addView(infoText("暂无 AI 文件操作记录"))
        val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        logs.forEach { log ->
            binding.logList.addView(TextView(this).apply {
                text = "${actionName(log.action)}  ${formatter.format(Date(log.timestamp))}${if (log.path.isNotBlank()) "\n${log.path}" else ""}${if (log.reason.isNotBlank()) "\n${log.reason}" else ""}"
                setTextColor(getColor(if (log.result in listOf("failed", "denied")) R.color.red else R.color.text)); textSize = 12f; setPadding(10, 12, 10, 12); setTextIsSelectable(true)
            })
        }
        binding.clearLogs.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun infoText(value: String) = TextView(this).apply { text = value; setTextColor(getColor(R.color.muted)); textSize = 13f; setPadding(10, 16, 10, 16) }
    private fun readableDirectoryName(uri: Uri): String {
        DocumentFile.fromTreeUri(this, uri)?.name?.takeIf(String::isNotBlank)?.let { return Uri.decode(it) }
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull().orEmpty()
        return Uri.decode(documentId.substringAfterLast(':').substringAfterLast('/')).takeIf(String::isNotBlank) ?: "系统目录"
    }
    private fun actionName(action: String) = mapOf("workspace_changed" to "主目录已设置", "workspace_cleared" to "主目录已清除", "directory_granted" to "目录授权", "additional_directory_granted" to "额外目录授权", "access_revoked" to "目录授权撤销", "tool_list_directory" to "查看目录", "tool_read_file" to "读取文件", "tool_write_file" to "写入文件", "tool_create_directory" to "创建目录", "tool_edit_file" to "编辑文件", "tool_delete_path" to "删除路径", "tool_search_files" to "搜索文件", "tool_http_request" to "网络请求")[action] ?: action

    private fun preview(uri: Uri) = runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(65536) } ?: "" }
        .onSuccess { MaterialAlertDialogBuilder(this).setTitle("文件预览").setMessage(it).setPositiveButton("关闭", null).show(); store.addTaskLog("file_preview", uri.toString(), "用户预览文件", "completed") }
        .onFailure { toast(it.message ?: "读取失败") }

    private fun createTest() {
        val root = store.workspaceGrant?.let { DocumentFile.fromTreeUri(this, Uri.parse(it.uri)) } ?: return toast("请先选择目录")
        runCatching {
            val file = root.findFile("ycode-test.txt") ?: root.createFile("text/plain", "ycode-test.txt") ?: error("无法创建文件")
            contentResolver.openOutputStream(file.uri, "wt")!!.use { it.write("Ycode Android workspace test".toByteArray(StandardCharsets.UTF_8)) }
        }.onSuccess { store.addTaskLog("tool_write_file", "workspace:/ycode-test.txt", "目录权限自检", "completed"); refresh(); toast("已创建 ycode-test.txt") }
            .onFailure { store.addTaskLog("tool_write_file", "workspace:/ycode-test.txt", it.message.orEmpty(), "failed"); toast(it.message ?: "创建失败") }
    }

    private fun confirmClearWorkspace() = MaterialAlertDialogBuilder(this).setTitle("清除主目录授权？").setMessage("Ycode 将停止使用当前工作目录。额外授权目录不受影响。")
        .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ -> store.workspaceGrant?.let(::releaseGrant); store.workspaceGrant = null; store.addTaskLog("workspace_cleared", reason = "用户清除主工作目录", result = "cleared"); refresh() }.show()

    private fun revokeAdditional(grant: DirectoryGrant) { releaseGrant(grant); store.saveAdditionalGrants(store.additionalGrants().filterNot { it.uri == grant.uri }); store.addTaskLog("access_revoked", grant.uri, grant.name, "revoked"); refresh() }
    private fun releaseGrant(grant: DirectoryGrant) { if (grant.flags != 0 && !store.isGrantUsedByAnotherConversation(grant.uri)) runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(grant.uri), grant.flags) } }
    private fun exportLogs() {
        runCatching {
            val directory = File(cacheDir, "exports").apply { mkdirs() }
            directory.listFiles()?.filter { it.name.startsWith("ycode-ai-logs-") }?.forEach { it.delete() }
            val file = File(directory, "ycode-ai-logs-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json")
            file.writeText(store.taskLogsJson(), StandardCharsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_SUBJECT, "Ycode AI 文件访问日志")
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newRawUri("Ycode AI 文件访问日志", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "导出日志"))
        }.onFailure { toast("无法导出日志：${it.message ?: "系统没有可用的分享应用"}") }
    }
    private fun confirmClearLogs() = MaterialAlertDialogBuilder(this).setTitle("清空 AI 日志？").setMessage("清空后无法恢复，建议先导出留存。").setNegativeButton("取消", null).setPositiveButton("清空") { _, _ -> store.clearTaskLogs(); refresh() }.show()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

    companion object { const val EXTRA_CONVERSATION_ID = "conversation_id" }
}
