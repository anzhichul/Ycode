package com.ycode.app.ui.chat

import android.content.Intent
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ycode.app.R
import com.ycode.app.YcodeApp
import com.ycode.app.data.AppStore
import com.ycode.app.databinding.FragmentChatBinding
import com.ycode.app.model.ChatMessage
import com.ycode.app.model.ChatAttachment
import com.ycode.app.model.Conversation
import com.ycode.app.model.ToolDetail
import com.ycode.app.network.ModelApiClient
import com.ycode.app.service.TaskForegroundService
import com.ycode.app.ui.workspace.WorkspaceActivity
import com.ycode.app.ui.preview.WebPreviewActivity
import com.ycode.app.ui.preview.XmlPreviewActivity
import com.ycode.app.ui.preview.WorkspaceWebRuntime
import com.ycode.app.tools.SafToolExecutor
import com.ycode.app.tools.TaskAgent
import com.ycode.app.remote.RemoteToolExecutor
import okhttp3.Call
import java.util.UUID
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChatFragment : Fragment() {
    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val adapter = MessageAdapter { message, action -> showToolDetails(message, action) }
    private var mode = "task"
    private var call: Call? = null
    private var taskHandle: TaskAgent.Handle? = null
    private var sending = false
    private var runGeneration = 0L
    private var pendingPreviewType: String? = null
    private lateinit var appContext: Context
    private lateinit var store: AppStore
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scrollPending = false
    private val pendingAttachments = mutableListOf<ChatAttachment>()
    private var pendingDirectoryRequest: PendingDirectoryRequest? = null
    private var pendingSendAfterWorkspaceGrant = false
    private var conversation = Conversation(UUID.randomUUID().toString(), "新对话", mutableListOf())
    private val pickAttachments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach(::addAttachment)
        renderAttachments()
        updateSendButton()
    }
    private val pickAgentDirectory = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val pending = pendingDirectoryRequest ?: return@registerForActivityResult
        pendingDirectoryRequest = null
        pending.result = if (uri == null) kotlin.Result.failure(CancellationException("用户取消了目录授权")) else runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val grantedFlags = runCatching { appContext.contentResolver.takePersistableUriPermission(uri, flags); flags }.getOrElse {
                val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
                appContext.contentResolver.takePersistableUriPermission(uri, read)
                read
            }
            val grants = store.additionalGrants()
            val existing = grants.indexOfFirst { it.uri == uri.toString() }
            val index = if (existing >= 0) existing else {
                val name = DocumentFile.fromTreeUri(appContext, uri)?.name?.takeIf(String::isNotBlank)?.let(Uri::decode) ?: "授权目录"
                grants += com.ycode.app.model.DirectoryGrant(name, uri.toString(), grantedFlags)
                store.saveAdditionalGrants(grants)
                grants.lastIndex
            }
            val grant = grants[index]
            store.addTaskLog("additional_directory_granted", "extra:/$index/", pending.purpose, "saved", pending.conversationId)
            mapOf("mounted" to true, "path" to "extra:/$index/", "name" to grant.name, "writable" to (grant.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0), "message" to "目录已授权，请只使用返回的 path 继续任务")
        }
        pending.latch.countDown()
    }
    private val pickInitialWorkspace = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            appContext.contentResolver.takePersistableUriPermission(uri, flags)
            store.workspaceGrant = com.ycode.app.model.DirectoryGrant(
                DocumentFile.fromTreeUri(appContext, uri)?.name?.let(Uri::decode) ?: "工作目录",
                uri.toString(), flags
            )
        }.onFailure { toast(it.message ?: "目录授权失败") }
        if (pendingSendAfterWorkspaceGrant) {
            pendingSendAfterWorkspaceGrant = false
            if (uri != null) send()
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        appContext = context.applicationContext
        store = (appContext as YcodeApp).store
    }

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View { _binding = FragmentChatBinding.inflate(inflater, parent, false); return binding.root }
    override fun onViewCreated(view: View, state: Bundle?) {
        store.conversations().firstOrNull { it.id == store.activeConversationId }?.let { conversation = it }
        binding.messages.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.messages.adapter = adapter
        adapter.replace(conversation.messages)
        binding.messages.itemAnimator = null
        binding.send.setOnClickListener { if (sending) stop() else send() }
        binding.attach.setOnClickListener { if (!sending) pickAttachments.launch(arrayOf("image/*", "text/*", "application/json", "application/xml", "application/javascript")) }
        binding.input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = updateSendButton()
            override fun afterTextChanged(value: Editable?) = Unit
        })
        binding.chatMode.setOnClickListener { selectMode("chat") }
        binding.taskMode.setOnClickListener { selectMode("task") }
        binding.modelChip.setOnClickListener { chooseModel() }
        binding.historyButton.setOnClickListener { refreshHistoryDrawer(); binding.drawerLayout.openDrawer(GravityCompat.START) }
        binding.closeDrawer.setOnClickListener { binding.drawerLayout.closeDrawer(GravityCompat.START) }
        binding.newChat.setOnClickListener { openConversation(Conversation(UUID.randomUUID().toString(), "新对话", mutableListOf())) }
        binding.rulesButton.setOnClickListener { editRules() }
        binding.workspaceButton.setOnClickListener { startActivity(Intent(requireContext(), WorkspaceActivity::class.java).putExtra(WorkspaceActivity.EXTRA_CONVERSATION_ID, conversation.id)) }
        binding.runButton.setOnClickListener { openPreview() }
        store.activateConversation(conversation.id)
        selectMode("task")
        updateModel()
        updateEmptyState()
        binding.topic.text = conversation.topic
        refreshRunButton()
    }

    private fun selectMode(value: String) {
        if (sending) return
        mode = value
        binding.chatMode.setBackgroundResource(if (value == "chat") R.drawable.bg_blue_pill else android.R.color.transparent)
        binding.taskMode.setBackgroundResource(if (value == "task") R.drawable.bg_blue_pill else android.R.color.transparent)
        binding.status.text = if (value == "task") "任务模式 · 可调用本机工具" else "聊天模式"
        binding.input.hint = if (value == "task") "描述目标和期望结果..." else "发送消息给 Ycode..."
        binding.ycodeIcon.visibility = if (value == "chat") View.VISIBLE else View.GONE
        binding.workspaceButton.visibility = if (value == "task") View.VISIBLE else View.GONE
        refreshRunButton()
        updateSendButton()
    }

    private fun send() {
        val text = binding.input.text.toString().trim(); if (text.isBlank() && pendingAttachments.isEmpty()) return
        if (mode == "task" && store.workspaceUri == null) {
            pendingSendAfterWorkspaceGrant = true
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("允许 AI 访问文件")
                .setMessage("任务需要读取或修改手机文件。请选择内部存储根目录或本次任务所需目录；授权会由 Android 系统管理并保存在本机，之后 AI 可按需继续请求其他目录。")
                .setNegativeButton("取消") { _, _ -> pendingSendAfterWorkspaceGrant = false }
                .setPositiveButton("选择目录") { _, _ -> pickInitialWorkspace.launch(null) }
                .show()
            return
        }
        val selected = store.currentModel() ?: return toast("请先在模型页启用模型")
        val (provider, config) = selected; val model = config.selectedModels.firstOrNull() ?: return
        val requestStartedAt = System.currentTimeMillis()
        val user = ChatMessage(role = "user", content = text, mode = mode, attachments = pendingAttachments.toMutableList())
        conversation.messages.add(user); adapter.append(user); binding.input.text.clear(); pendingAttachments.clear(); renderAttachments(); updateEmptyState()
        if (conversation.topic == "新对话") conversation.topic = text.replace("\n", " ").take(24).ifBlank { user.attachments.orEmpty().firstOrNull()?.name?.take(24) ?: "附件对话" }
        binding.topic.text = conversation.topic
        val rules = store.rules()
        val outgoing = mutableListOf<ChatMessage>()
        if (rules.enabled && rules.systemPrompt.isNotBlank()) outgoing += ChatMessage(role = "system", content = rules.systemPrompt)
        outgoing += conversation.messages.filter { it.role == "user" || it.role == "assistant" }.map { if (it.role == "user") it.copy(content = "[本轮模式：${if (it.mode == "task") "任务模式" else "普通聊天"}]\n${it.content}") else it }
        val assistant = ChatMessage(role = "assistant", content = if (mode == "task") "正在处理任务…" else "正在思考…", mode = mode, streaming = true)
        conversation.messages.add(assistant)
        adapter.append(assistant)
        binding.taskPlan.visibility = View.GONE
        scrollToLatest()
        setSending(true)
        TaskForegroundService.start(appContext)
        if (mode == "task") {
            val generation = ++runGeneration
            val activeConversation = conversation
            assistant.liveLogs = mutableListOf()
            taskHandle = TaskAgent.run(conversation.id, config, model, outgoing, { event ->
                if (generation != runGeneration || activeConversation !== conversation) return@run
                if (event is TaskAgent.Event.Plan) {
                    val active = event.items.firstOrNull { it.status == "in_progress" }?.content
                    runOnUiThreadIfVisible {
                        active?.let { appendLiveLog(assistant, "PLAN     $it") }
                        if (assistant.toolDetails.orEmpty().isEmpty() && active != null) assistant.content = "好的，我正在处理这个任务。\n当前步骤：$active"
                        renderTaskPlan(event.items)
                        adapter.update(assistant)
                    }
                    return@run
                }
                if (event is TaskAgent.Event.Status) {
                    runOnUiThreadIfVisible {
                        appendLiveLog(assistant, "THINK    ${event.text}")
                        binding.status.text = event.text
                        adapter.update(assistant); scrollToLatest()
                    }
                    return@run
                }
                if (event is TaskAgent.Event.Preview) {
                    pendingPreviewType = event.type
                    runOnUiThreadIfVisible { launchPreview(event.type) }
                    return@run
                }
                val current = event as TaskAgent.Event.Tool
                runOnUiThreadIfVisible {
                    val details = assistant.toolDetails ?: mutableListOf<ToolDetail>().also { assistant.toolDetails = it }
                    val existing = details.lastOrNull { it.action == current.label && it.path == current.path && it.status == "running" }
                    if (existing != null) {
                        existing.status = current.status
                        existing.detail = current.detail
                        existing.elapsedSeconds = maxOf(existing.elapsedSeconds, current.elapsedSeconds)
                    } else details += ToolDetail(current.label, current.path, current.status, current.detail, current.elapsedSeconds)
                    val logAction = when (current.status) { "completed" -> "DONE "; "failed" -> "FAIL "; else -> if (current.elapsedSeconds > 0) "WAIT " else "START" }
                    appendLiveLog(assistant, "$logAction    ${current.label} ${current.path.ifBlank { current.detail }}${current.elapsedSeconds.takeIf { it > 0 }?.let { " · ${it}s" }.orEmpty()}")
                    assistant.content = taskNarrative(details, current)
                    if (current.status == "completed" && current.label in setOf("写入文件", "编辑文件", "删除路径", "创建目录")) refreshRunButton()
                    binding.status.text = assistant.content
                    adapter.update(assistant)
                    updateEmptyState()
                    scrollToLatest()
                    store.saveConversation(conversation)
                }
            }, SafToolExecutor(appContext, store, conversation.id), RemoteToolExecutor(appContext), { purpose -> requestAgentDirectoryAccess(conversation.id, purpose) }, { result ->
                if (generation != runGeneration || activeConversation !== conversation) return@run
                taskHandle = null
                result.onSuccess { task ->
                    completeTask(task, assistant)
                    store.recordUsage(provider, config, model, outgoing.sumOf { it.content.length }, assistant.content.length, System.currentTimeMillis() - requestStartedAt, true)
                }.onFailure {
                    store.recordUsage(provider, config, model, outgoing.sumOf { it.content.length }, assistant.content.length, System.currentTimeMillis() - requestStartedAt, false)
                    if (it !is CancellationException) setMessageError(assistant, it.message ?: "任务执行失败")
                    finishSending()
                }
            })
            return
        }
        streamAnswer(provider, config, model, outgoing, assistant, requestStartedAt)
    }

    private fun streamAnswer(provider: com.ycode.app.model.Provider, config: com.ycode.app.model.ProviderConfig, model: String, outgoing: List<ChatMessage>, assistant: ChatMessage, startedAt: Long) {
        var receivedContent = false
        call = ModelApiClient.stream(config, model, outgoing, { delta ->
            if (!receivedContent) { assistant.content = ""; receivedContent = true }
            assistant.content += delta
            runOnUiThreadIfVisible { adapter.update(assistant); scrollToLatest() }
        }, { result ->
            assistant.streaming = false
            call = null
            result.exceptionOrNull()?.let { setMessageError(assistant, it.message ?: "请求失败") }
            store.recordUsage(provider, config, model, outgoing.sumOf { it.content.length }, assistant.content.length, System.currentTimeMillis() - startedAt, result.isSuccess)
            store.saveConversation(conversation)
            finishSending()
            runOnUiThreadIfVisible { adapter.update(assistant) }
        })
    }

    private fun completeTask(task: TaskAgent.Result, assistant: ChatMessage) {
        val finalContent = task.messages.asReversed().firstNotNullOfOrNull { message ->
            if (message["role"] == "assistant") (message["content"] as? String)?.trim()?.takeIf(String::isNotBlank) else null
        }
        assistant.content = finalContent ?: buildString {
            val completed = assistant.toolDetails.orEmpty().count { it.status == "completed" }
            val failed = assistant.toolDetails.orEmpty().count { it.status == "failed" }
            append("任务处理完成")
            if (completed > 0) append("，已完成 $completed 项操作")
            if (failed > 0) append("，$failed 项操作失败，请展开文件操作查看详情") else append("。")
        }
        assistant.streaming = false
        assistant.liveLogs?.clear()
        call = null
        store.saveConversation(conversation)
        finishSending()
        runOnUiThreadIfVisible { adapter.update(assistant); scrollToLatest() }
    }

    private fun requestAgentDirectoryAccess(conversationId: String, purpose: String): kotlin.Result<Any> {
        if (pendingDirectoryRequest != null) return kotlin.Result.failure(IllegalStateException("已有目录授权请求正在等待用户处理"))
        val pending = PendingDirectoryRequest(conversationId, purpose)
        pendingDirectoryRequest = pending
        runOnUiThreadIfVisible {
            binding.status.text = "需要你选择并授权目录"
            pickAgentDirectory.launch(null)
        }
        if (!pending.latch.await(2, TimeUnit.MINUTES)) {
            if (pendingDirectoryRequest === pending) pendingDirectoryRequest = null
            return kotlin.Result.failure(IllegalStateException("等待目录授权超过 2 分钟"))
        }
        return pending.result ?: kotlin.Result.failure(IllegalStateException("目录授权没有返回结果"))
    }

    private fun appendLiveLog(message: ChatMessage, text: String) {
        val logs = message.liveLogs ?: mutableListOf<String>().also { message.liveLogs = it }
        val elapsed = ((System.currentTimeMillis() - message.createdAt) / 1000).coerceAtLeast(0)
        val line = "[%02d:%02d] %s".format(elapsed / 60, elapsed % 60, text)
        if (logs.lastOrNull() != line) logs += line
        while (logs.size > 3) logs.removeAt(0)
    }

    private fun taskNarrative(details: List<ToolDetail>, current: TaskAgent.Event.Tool): String {
        val writes = details.count { it.action == "写入文件" && it.status == "completed" }
        val created = details.count { it.action == "创建目录" && it.status == "completed" }
        val reads = details.count { it.action == "读取文件" && it.status == "completed" }
        return when (current.status) {
            "running" -> when (current.label) {
                "写入文件" -> "已准备项目结构${created.takeIf { it > 0 }?.let { "，创建 $it 个目录" }.orEmpty()}。\n正在写入 `${current.path}`：${current.detail}"
                "创建目录" -> "正在创建项目目录 `${current.path}`，完成后会继续逐个写入页面文件。"
                "读取文件" -> "正在读取 `${current.path}`，用于理解现有结构和内容。"
                "编辑文件" -> "正在修改 `${current.path}`：${current.detail}"
                else -> "正在${current.label} `${current.path}`${current.detail.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}"
            }
            "failed" -> "`${current.path}` 操作失败：${current.detail}\n正在调整方案并继续处理。"
            else -> when (current.label) {
                "写入文件" -> "已写入 `$writes` 个文件，刚刚完成 `${current.path}`。\n正在继续处理下一个页面或资源文件。"
                "创建目录" -> "已创建目录 `${current.path}`，现在开始写入其中的页面和资源。"
                "读取文件" -> "已读取 `$reads` 个文件，刚刚完成 `${current.path}`，正在分析并准备修改。"
                else -> "已完成${current.label} `${current.path}`，正在继续下一步。"
            }
        }
    }

    private fun showImmediateError(text: String) {
        val assistant = ChatMessage(role = "assistant", content = text, mode = mode, error = true)
        conversation.messages.add(assistant)
        adapter.append(assistant)
        store.saveConversation(conversation)
        scrollToLatest()
    }

    private fun renderTaskPlan(items: List<TaskAgent.PlanItem>) {
        if (_binding == null) return
        val finished = items.isNotEmpty() && items.all { it.status == "completed" }
        binding.taskPlan.visibility = if (items.isEmpty() || finished) View.GONE else View.VISIBLE
        binding.taskPlanCount.text = "${items.count { it.status == "completed" }} / ${items.size}"
        binding.taskPlanItems.removeAllViews()
        items.take(6).forEach { item ->
            binding.taskPlanItems.addView(TextView(requireContext()).apply {
                val marker = when (item.status) {
                    "completed" -> "✓"
                    "in_progress" -> "●"
                    "failed" -> "×"
                    else -> "○"
                }
                text = "$marker  ${item.content}"
                setTextColor(context.getColor(when (item.status) {
                    "completed" -> R.color.green
                    "in_progress" -> R.color.blue
                    "failed" -> R.color.red
                    else -> R.color.muted
                }))
                textSize = 10f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, dp(2))
            })
        }
    }

    private fun toolSummary(details: List<ToolDetail>): String {
        val completed = details.filter { it.status == "completed" }.groupingBy { it.action }.eachCount().map { (action, count) -> "$action $count · 点击查看" }
        val current = details.lastOrNull { it.status == "running" }?.let {
            "进行中：${it.action} · ${it.path.substringAfterLast('/').substringAfterLast('\\')}${it.elapsedSeconds.takeIf { value -> value > 0 }?.let { seconds -> " · ${seconds}秒" }.orEmpty()}\n说明：${it.detail}"
        }
        val failed = details.lastOrNull { it.status == "failed" }?.let { "失败：${it.action} · ${it.path}\n说明：${it.detail}" }
        return (completed + listOfNotNull(current, failed)).joinToString("\n").ifBlank { "正在准备文件操作…" }
    }

    private fun showToolDetails(message: ChatMessage, action: String) {
        val details = message.toolDetails.orEmpty().filter { it.action == action }
        if (details.isEmpty() || !isAdded) return
        val text = details.mapIndexed { index, item ->
            val status = when (item.status) { "completed" -> "完成"; "failed" -> "失败"; else -> "进行中" }
            "${index + 1}. ${item.action} · $status${item.elapsedSeconds.takeIf { it > 0 }?.let { " · ${it}秒" }.orEmpty()}\n${item.path.ifBlank { "无文件路径" }}\n${item.detail.ifBlank { "无补充说明" }}"
        }.joinToString("\n\n")
        MaterialAlertDialogBuilder(requireContext()).setTitle("$action · ${details.size} 项").setMessage(text).setPositiveButton("关闭", null).show()
    }

    private fun setMessageError(message: ChatMessage, text: String) {
        message.streaming = false
        message.error = true
        message.content = text
        store.saveConversation(conversation)
        runOnUiThreadIfVisible { adapter.update(message); scrollToLatest() }
    }

    private fun scrollToLatest() {
        if (adapter.itemCount == 0 || _binding == null || scrollPending) return
        scrollPending = true
        binding.messages.post {
            scrollPending = false
            if (_binding == null || adapter.itemCount == 0) return@post
            val last = adapter.itemCount - 1
            val manager = binding.messages.layoutManager as? LinearLayoutManager ?: return@post
            val lastView = manager.findViewByPosition(last)
            if (lastView == null) binding.messages.scrollToPosition(last)
            else binding.messages.scrollBy(0, lastView.bottom - (binding.messages.height - binding.messages.paddingBottom))
        }
    }

    private fun stop() { runGeneration++; call?.cancel(); call = null; taskHandle?.cancel(); taskHandle = null; conversation.messages.lastOrNull()?.streaming = false; adapter.updateLast(); finishSending(); store.saveConversation(conversation) }
    private fun finishSending() {
        sending = false
        TaskForegroundService.stop(appContext)
        runOnUiThreadIfVisible { setSending(false) }
    }

    private fun runOnUiThreadIfVisible(action: () -> Unit) {
        mainHandler.post { if (_binding != null && isAdded) action() }
    }
    private fun setSending(value: Boolean) {
        sending = value
        binding.status.text = if (value) "Ycode 正在处理..." else if (mode == "task") "任务模式 · 可调用本机工具" else "聊天模式"
        updateSendButton()
    }

    private fun updateSendButton() {
        val running = sending
        val hasInput = binding.input.text?.toString()?.trim()?.isNotEmpty() == true || pendingAttachments.isNotEmpty()
        when {
            running -> {
                binding.send.isEnabled = true
                binding.send.text = "■"
                binding.send.contentDescription = "停止生成"
                binding.send.setTextColor(requireContext().getColor(R.color.white))
                binding.send.setBackgroundResource(R.drawable.bg_stop)
                binding.send.elevation = 0f
            }
            hasInput -> {
                binding.send.isEnabled = true
                binding.send.text = "↑"
                binding.send.contentDescription = if (mode == "task") "发送任务" else "发送消息"
                binding.send.setTextColor(requireContext().getColor(R.color.white))
                binding.send.setBackgroundResource(if (mode == "task") R.drawable.bg_send_task else R.drawable.bg_send)
                binding.send.elevation = 0f
            }
            else -> {
                binding.send.isEnabled = false
                binding.send.text = "↑"
                binding.send.contentDescription = "输入内容后发送"
                binding.send.setTextColor(android.graphics.Color.parseColor("#9AA5B4"))
                binding.send.setBackgroundResource(R.drawable.bg_send_disabled)
                binding.send.elevation = 0f
            }
        }
    }

    private fun addAttachment(uri: Uri) {
        if (pendingAttachments.size >= 6) return toast("一次最多添加 6 个附件")
        val metadata = queryAttachment(uri)
        val extension = metadata.first.substringAfterLast('.', "").lowercase()
        val mime = contentResolverType(uri)
        val image = mime.startsWith("image/") && extension !in setOf("svg", "svgz")
        val text = mime.startsWith("text/") || extension in TEXT_EXTENSIONS || mime in setOf("application/json", "application/xml", "application/javascript")
        if (!image && !text) return toast("不支持 ${metadata.first}，只能发送图片、文本和源码文件")
        val maxSize = if (image) 10L * 1024 * 1024 else 1024L * 1024
        if (metadata.second > maxSize) return toast(if (image) "单张图片不能超过 10 MB" else "文本或源码文件不能超过 1 MB")
        val directory = File(appContext.filesDir, "chat-attachments").apply { mkdirs() }
        val target = File(directory, "${UUID.randomUUID()}-${metadata.first.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        val copied = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use(input::copyTo) } ?: error("无法读取文件")
        }
        if (copied.isFailure) return toast(copied.exceptionOrNull()?.message ?: "附件读取失败")
        if (target.length() > maxSize) { target.delete(); return toast(if (image) "单张图片不能超过 10 MB" else "文本或源码文件不能超过 1 MB") }
        if (text && target.readBytes().take(4096).any { it == 0.toByte() }) { target.delete(); return toast("该文件包含二进制内容，不能作为源码发送") }
        pendingAttachments += ChatAttachment(metadata.first, if (image) mime else "text/plain", target.absolutePath, target.length(), image)
    }

    private fun queryAttachment(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        var size = -1L
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = if (cursor.isNull(it)) -1L else cursor.getLong(it) }
            }
        }
        return name to size
    }

    private fun contentResolverType(uri: Uri) = appContext.contentResolver.getType(uri)?.lowercase().orEmpty()

    private fun renderAttachments() {
        if (_binding == null) return
        binding.attachmentRow.removeAllViews()
        binding.attachmentScroll.visibility = if (pendingAttachments.isEmpty()) View.GONE else View.VISIBLE
        pendingAttachments.forEach { attachment ->
            binding.attachmentRow.addView(TextView(requireContext()).apply {
                text = "${if (attachment.image) "图片" else "文件"} · ${attachment.name}  ×"
                setTextColor(context.getColor(R.color.blue))
                textSize = 10f
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.bg_blue_pill)
                setPadding(dp(10), 0, dp(10), 0)
                setOnClickListener {
                    pendingAttachments.remove(attachment)
                    File(attachment.localPath).delete()
                    renderAttachments()
                    updateSendButton()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)).apply { marginEnd = dp(6) })
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "log", "csv", "json", "jsonl", "xml", "yaml", "yml", "toml", "ini", "conf", "env",
            "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "html", "htm", "css", "scss", "sass", "less", "vue", "svelte",
            "c", "h", "cc", "cpp", "cxx", "hpp", "cs", "go", "rs", "rb", "php", "swift", "dart", "lua", "r", "sql", "sh", "bash",
            "zsh", "fish", "ps1", "bat", "cmd", "gradle", "properties", "gitignore", "dockerfile"
        )
    }
    private data class PendingDirectoryRequest(val conversationId: String, val purpose: String, val latch: CountDownLatch = CountDownLatch(1), @Volatile var result: kotlin.Result<Any>? = null)
    private fun updateModel() { binding.modelChip.text = store.currentModel()?.let { "${it.first.name} · ${it.second.selectedModels.firstOrNull()} ⌄" } ?: "未选择模型 ⌄" }

    private fun chooseModel() {
        val enabled = store.enabledProviders(); if (enabled.isEmpty()) return toast("请先在模型页启用模型")
        val labels = enabled.flatMap { (p, c) -> c.selectedModels.map { p.name to it } }
        MaterialAlertDialogBuilder(requireContext()).setTitle("选择聊天模型").setItems(labels.map { "${it.first} · ${it.second}" }.toTypedArray()) { _, index ->
            val pair = labels[index]; val provider = enabled.first { it.first.name == pair.first }.first; store.selectModel(provider.id, pair.second); updateModel()
        }.show()
    }

    private fun refreshHistoryDrawer() {
        val history = store.conversations()
        binding.historyCount.text = if (history.isEmpty()) "暂无历史记录" else "${history.size} 条会话"
        binding.historyList.removeAllViews()
        if (history.isEmpty()) {
            binding.historyList.addView(TextView(requireContext()).apply {
                text = "还没有历史会话\n\n发送消息后，对话会自动保存在这里。"
                setTextColor(context.getColor(R.color.muted)); textSize = 12f; setPadding(dp(12), dp(24), dp(12), dp(18))
            })
            return
        }
        history.forEach { item ->
            binding.historyList.addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(11), dp(12), dp(11)); setBackgroundResource(if (item.id == conversation.id) R.drawable.bg_blue_pill else android.R.color.transparent)
                addView(TextView(context).apply { text = item.topic; setTextColor(context.getColor(R.color.text)); textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(TextView(context).apply { text = if (item.messages.any { it.mode == "task" }) "任务会话" else "普通聊天"; setTextColor(context.getColor(R.color.muted)); textSize = 10f; maxLines = 1; setPadding(0, dp(3), 0, 0) })
                setOnClickListener { openConversation(item) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(3) })
        }
    }

    private fun openConversation(item: Conversation) {
        if (sending) return toast("当前回答完成后再切换会话")
        conversation = item
        store.activateConversation(conversation.id)
        adapter.replace(conversation.messages)
        binding.topic.text = conversation.topic
        updateEmptyState()
        refreshRunButton()
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun editRules() {
        val current = store.rules()
        val input = android.widget.EditText(requireContext()).apply {
            setText(current.systemPrompt); hint = "输入每次聊天都要遵守的规则"; minLines = 5; gravity = android.view.Gravity.TOP; setPadding(40, 28, 40, 28)
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle("全局聊天规则").setView(input).setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
            store.saveRules(current.apply { systemPrompt = input.text.toString().trim(); enabled = systemPrompt.isNotBlank() })
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            toast("规则已保存")
        }.show()
    }

    private fun updateEmptyState() {
        val empty = adapter.itemCount == 0
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.messages.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun refreshRunButton() {
        val root = WorkspaceWebRuntime.root(appContext, store.workspaceUri)
        val available = runCatching {
            val web = root?.let(WorkspaceWebRuntime::webEntries)?.any { it.path.endsWith(".html", true) || it.path.endsWith(".htm", true) } == true
            val xml = root?.let(WorkspaceWebRuntime::layoutEntries)?.isNotEmpty() == true
            web to xml
        }.getOrDefault(false to false)
        binding.runButton.text = when { available.first && available.second -> "预览"; available.second -> "XML"; else -> "运行" }
        binding.runButton.visibility = if (mode == "task" && (available.first || available.second)) View.VISIBLE else View.GONE
    }

    private fun openPreview() = launchPreview("auto")

    private fun launchPreview(type: String) {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            pendingPreviewType = type
            return
        }
        val root = WorkspaceWebRuntime.root(appContext, store.workspaceUri) ?: return toast("请先选择工作目录")
        val hasWeb = WorkspaceWebRuntime.webEntries(root).any { it.path.endsWith(".html", true) || it.path.endsWith(".htm", true) }
        val hasXml = WorkspaceWebRuntime.layoutEntries(root).isNotEmpty()
        pendingPreviewType = null
        when {
            type == "web" && hasWeb -> startActivity(Intent(requireContext(), WebPreviewActivity::class.java))
            type == "xml" && hasXml -> startActivity(Intent(requireContext(), XmlPreviewActivity::class.java))
            type == "web" -> toast("AI 请求运行网页，但工作目录中没有 HTML 文件")
            type == "xml" -> toast("AI 请求 Android XML 可视化，但没有 res/layout XML")
            hasWeb && hasXml -> MaterialAlertDialogBuilder(requireContext()).setTitle("选择预览方式").setItems(arrayOf("运行网页", "Android XML 可视化")) { _, index ->
                startActivity(Intent(requireContext(), if (index == 0) WebPreviewActivity::class.java else XmlPreviewActivity::class.java))
            }.show()
            hasXml -> startActivity(Intent(requireContext(), XmlPreviewActivity::class.java))
            hasWeb -> startActivity(Intent(requireContext(), WebPreviewActivity::class.java))
            else -> toast("没有可预览的 HTML 或 res/layout XML")
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            refreshRunButton()
            pendingPreviewType?.let(::launchPreview)
        }
    }

    private fun toast(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    override fun onDestroyView() {
        pendingDirectoryRequest?.let {
            it.result = kotlin.Result.failure(CancellationException("目录授权页面已关闭"))
            it.latch.countDown()
        }
        pendingDirectoryRequest = null
        _binding = null
        super.onDestroyView()
    }
}
