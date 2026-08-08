package com.ycode.app.tools

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ycode.app.model.ChatMessage
import com.ycode.app.model.ProviderConfig
import com.ycode.app.network.MessageContent
import com.ycode.app.remote.RemoteToolExecutor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

object TaskAgent {
    private val gson = Gson()
    private val executor = Executors.newCachedThreadPool()
    private val toolExecutor = Executors.newCachedThreadPool()
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    data class Result(val messages: List<Map<String, Any?>>, val attempts: Int, val successes: Int, val failures: Int)
    data class PlanItem(val content: String, val status: String, val priority: String = "medium")
    private data class SessionState(val messages: MutableList<Map<String, Any?>>, val seenUsers: MutableSet<String>)
    sealed interface Event {
        data class Plan(val items: List<PlanItem>) : Event
        data class Tool(val name: String, val label: String, val path: String, val status: String, val detail: String = "", val elapsedSeconds: Long = 0) : Event
        data class Status(val text: String) : Event
        data class Preview(val type: String) : Event
    }
    class Handle internal constructor(private val cancelled: AtomicBoolean) {
        fun cancel() { cancelled.set(true) }
    }

    fun run(sessionId: String, config: ProviderConfig, model: String, history: List<ChatMessage>, event: (Event) -> Unit, tools: SafToolExecutor, remoteTools: RemoteToolExecutor, requestDirectoryAccess: (String) -> kotlin.Result<Any>, done: (kotlin.Result<Result>) -> Unit): Handle {
        val cancelled = AtomicBoolean(false)
        executor.execute {
        runCatching {
            val stateKey = "$sessionId|${config.baseUrl}|$model"
            val state = sessions.getOrPut(stateKey) {
                SessionState(
                    mutableListOf(mapOf("role" to "system", "content" to SYSTEM_PROMPT + "\n" + tools.workspaceContext())),
                    mutableSetOf()
                )
            }
            val messages = state.messages
            val newUsers = history.filter { it.role == "user" && state.seenUsers.add(it.id) }
            newUsers.forEach {
                messages += mapOf("role" to "user", "content" to MessageContent.build(it, "[本轮模式：${if (it.mode == "task") "任务模式" else "普通聊天"}]\n"))
            }
            val explanationOnly = newUsers.lastOrNull()?.content?.let(::isExplanationOnly).orFalse()
            if (explanationOnly) messages += mapOf("role" to "system", "content" to "用户本轮只是在追问已有错误、结果或概念。直接解释原因和解决方法；禁止调用 todo_write 或任何文件工具。涉及代码时使用带语言名称的 Markdown 三反引号代码块。")
            var attempts = 0; var success = 0; var failures = 0
            val readAttempts = mutableMapOf<String, Int>()
            var planDeclared = false
            var latestPlan = emptyList<PlanItem>()
            while (!cancelled.get()) {
                val response = completionWithProgress(config, model, messages, cancelled, event)
                if (cancelled.get()) break
                val calls = response.getAsJsonArray("tool_calls") ?: JsonArray()
                if (calls.size() == 0) {
                    val content = response.get("content")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                    if (!explanationOnly && (!planDeclared || latestPlan.any { it.status in setOf("pending", "in_progress") })) {
                        if (content.isNotBlank()) messages += mapOf("role" to "assistant", "content" to content)
                        messages += mapOf("role" to "system", "content" to if (!planDeclared) "任务尚未制定计划。现在必须调用 todo_write 后继续执行。" else "任务计划仍有未完成步骤。禁止结束或只汇报准备做什么；请更新 todo_write 并继续调用工具完成交付。")
                        continue
                    }
                    content.takeIf { it.isNotBlank() }?.let { messages += mapOf("role" to "assistant", "content" to it) }
                    return@runCatching Result(messages, attempts, success, failures)
                }
                messages += mapOf("role" to "assistant", "content" to response.get("content")?.takeUnless { it.isJsonNull }?.asString, "tool_calls" to gson.fromJson(calls, Any::class.java))
                calls.forEach { node ->
                    if (cancelled.get()) return@forEach
                    val call = node.asJsonObject; val id = call.get("id")?.asString ?: error("工具调用缺少 ID"); val fn = call.getAsJsonObject("function"); val name = fn.get("name").asString
                    val args = gson.fromJson(fn.get("arguments").asString, JsonObject::class.java); val target = args.get("path")?.asString ?: args.get("url")?.asString ?: args.get("suggested_directory")?.asString.orEmpty()
                    attempts++
                    val reason = args.get("reason")?.asString.orEmpty().ifBlank { operationDetail(name, args) }
                    if (name != "todo_write") event(Event.Tool(name, label(name), target, "running", reason))
                    val readKey = if (name == "read_file") "$target:${args.get("cursor")?.asLong ?: 0L}" else ""
                    val repeatedRead = readKey.isNotEmpty() && (readAttempts[readKey] ?: 0) > 0
                    val explanationTool = explanationOnly
                    val missingPlan = !planDeclared && name != "todo_write"
                    val toolResult = when {
                        explanationTool -> kotlin.Result.failure<Any>(IllegalStateException("用户本轮只要求解释已有问题，禁止启动文件操作"))
                        missingPlan -> kotlin.Result.failure<Any>(IllegalStateException("执行文件操作前必须先调用 todo_write 制定分步计划"))
                        repeatedRead -> kotlin.Result.failure(IllegalStateException("拒绝重复读取：该路径和游标已经读取。请使用已有内容继续优化、edit_file 或 write_file"))
                        name == "open_preview" -> kotlin.Result.success(mapOf("opened" to true, "type" to (args.get("type")?.asString ?: "auto")))
                        name == "request_directory_access" -> requestDirectoryAccess(args.get("purpose")?.asString ?: "任务需要访问另一个目录")
                        else -> executeTool(tools, remoteTools, name, args) { seconds ->
                            event(Event.Tool(name, label(name), target, "running", reason, seconds))
                        }
                    }
                    if (name == "todo_write" && toolResult.isSuccess) {
                        val plan = parsePlan(args)
                        if (plan.isNotEmpty()) { planDeclared = true; latestPlan = plan; event(Event.Plan(plan)) }
                    }
                    if (readKey.isNotEmpty() && toolResult.isSuccess) readAttempts[readKey] = (readAttempts[readKey] ?: 0) + 1
                    if (toolResult.isSuccess) success++ else failures++
                    if (name != "todo_write") event(Event.Tool(
                        name,
                        label(name),
                        target,
                        if (toolResult.isSuccess) "completed" else "failed",
                        toolResult.fold({ resultDetail(name, reason, it) }, { it.message ?: "工具执行失败" })
                    ))
                    if (name == "open_preview" && toolResult.isSuccess) event(Event.Preview(args.get("type")?.asString ?: "auto"))
                    messages += mapOf("role" to "tool", "tool_call_id" to id, "content" to gson.toJson(toolResult.fold({ mapOf("ok" to true, "result" to it) }, { mapOf("ok" to false, "error" to it.message) })))
                }
            }
            throw CancellationException("任务已停止")
        }.also(done)
        }
        return Handle(cancelled)
    }

    private fun executeTool(tools: SafToolExecutor, remoteTools: RemoteToolExecutor, name: String, args: JsonObject, progress: (Long) -> Unit): kotlin.Result<Any> {
        val future = toolExecutor.submit<Any> {
            when {
                name in REMOTE_TOOL_NAMES -> remoteTools.execute(name, args)
                else -> tools.execute(name, args)
            }
        }
        val started = System.nanoTime()
        while (true) {
            try {
                return kotlin.Result.success(future.get(3, TimeUnit.SECONDS))
            } catch (_: TimeoutException) {
                val elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
                progress(elapsed)
                val timeout = if (name in REMOTE_TOOL_NAMES) 65 else 45
                if (elapsed >= timeout) {
                    future.cancel(true)
                    return kotlin.Result.failure(IOException("工具执行超过 $timeout 秒，已中止本次操作，请调整方案后重试"))
                }
            } catch (error: ExecutionException) {
                return kotlin.Result.failure(error.cause ?: error)
            } catch (error: Throwable) {
                return kotlin.Result.failure(error)
            }
        }
    }

    private fun completionWithProgress(config: ProviderConfig, model: String, messages: List<Map<String, Any?>>, cancelled: AtomicBoolean, event: (Event) -> Unit): JsonObject {
        val future = toolExecutor.submit<JsonObject> { completion(config, model, messages) }
        val started = System.nanoTime()
        while (!cancelled.get()) {
            try {
                return future.get(4, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                val seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
                event(Event.Status("模型正在分析并决定下一步 · 已等待 ${seconds} 秒"))
            } catch (error: ExecutionException) {
                throw error.cause ?: error
            }
        }
        future.cancel(true)
        throw CancellationException("任务已停止")
    }

    private fun operationDetail(name: String, args: JsonObject): String = when (name) {
        "write_file" -> "准备写入 ${args.get("content")?.asString?.toByteArray()?.size ?: 0} 字节；模型未提供说明"
        "edit_file" -> "准备替换指定内容；模型未提供说明"
        "delete_path" -> "准备删除该路径；模型未提供说明"
        "read_file" -> "读取文件内容用于分析"
        "request_directory_access" -> "请求用户授权新的系统目录"
        "open_preview" -> "启动用户请求的可视化预览"
        else -> "执行 ${label(name)}"
    }

    private fun resultDetail(name: String, reason: String, result: Any): String = when (name) {
        "write_file" -> "$reason；写入完成"
        "edit_file" -> "$reason；编辑完成"
        "delete_path" -> "$reason；删除完成"
        "read_file" -> "读取完成"
        "request_directory_access" -> "目录授权完成"
        "open_preview" -> "已请求启动可视化"
        else -> if (reason.isNotBlank()) reason else result.toString().take(300)
    }

    private fun parsePlan(args: JsonObject): List<PlanItem> = args.getAsJsonArray("items")?.mapNotNull { node ->
        runCatching {
            val item = node.asJsonObject
            PlanItem(
                content = (item.get("content") ?: item.get("title"))?.asString?.trim().orEmpty(),
                status = item.get("status")?.asString?.lowercase()?.takeIf { it in PLAN_STATUSES } ?: "pending",
                priority = item.get("priority")?.asString?.lowercase()?.takeIf { it in setOf("high", "medium", "low") } ?: "medium"
            )
        }.getOrNull()?.takeIf { it.content.isNotBlank() }
    }.orEmpty()

    private fun isExplanationOnly(value: String): Boolean {
        val text = value.trim().lowercase()
        val asksWhy = listOf("错误", "报错", "什么意思", "为什么", "怎么回事", "原因", "解释", "what error", "what does", "why").any(text::contains)
        val asksChange = listOf("修改", "改一下", "修复", "创建", "写入", "删除", "运行", "预览", "打开", "帮我做", "实现", "新增", "replace", "fix", "create", "write", "delete", "run").any(text::contains)
        return asksWhy && !asksChange
    }

    private fun Boolean?.orFalse() = this == true

    private fun completion(config: ProviderConfig, model: String, messages: List<Map<String, Any?>>): JsonObject {
        val payload = JsonObject().apply { addProperty("model", model); addProperty("stream", false); addProperty("temperature", .2); addProperty("tool_choice", "auto"); add("messages", gson.toJsonTree(messages)); add("tools", detailedDefinitions()) }
        val request = Request.Builder().url("${config.baseUrl.trimEnd('/')}/chat/completions").header("Authorization", "Bearer ${config.apiKey}").post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(request).execute().use { response -> if (!response.isSuccessful) throw IOException("任务模型请求失败 ${response.code}: ${response.body?.string()?.take(500)}"); gson.fromJson(response.body?.string(), JsonObject::class.java).getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message") }
    }
    private fun detailedDefinitions(): JsonArray = gson.fromJson("""[
        {"type":"function","function":{"name":"list_directory","description":"列出目录树，默认同时查看当前目录及子目录一层。用户要求查看目录内容时，不能只报告文件夹名称；必须根据返回的 level 和 path 分析子目录中的文件。","parameters":{"type":"object","properties":{"path":{"type":"string"},"depth":{"type":"integer","minimum":1,"maximum":3,"description":"递归层数，默认2；大型目录可用1"}},"required":["path"]}}},
        {"type":"function","function":{"name":"read_file","description":"读取文本文件。小于等于1MB时首次一次性返回全文，禁止重复读取。","parameters":{"type":"object","properties":{"path":{"type":"string"},"cursor":{"type":"integer"},"batch_size":{"type":"integer"},"reason":{"type":"string","description":"读取该文件的具体目的"}},"required":["path","reason"]}}},
        {"type":"function","function":{"name":"write_file","description":"创建或覆盖一个文件。每个文件必须单独调用，并解释写入内容和用途。","parameters":{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"},"reason":{"type":"string","description":"向用户解释这个文件写了什么、为什么写"}},"required":["path","content","reason"]}}},
        {"type":"function","function":{"name":"create_directory","description":"创建一个目录","parameters":{"type":"object","properties":{"path":{"type":"string"},"reason":{"type":"string"}},"required":["path"]}}},
        {"type":"function","function":{"name":"edit_file","description":"精确替换编辑一个文件。每个文件单独调用，并解释修改内容。","parameters":{"type":"object","properties":{"path":{"type":"string"},"old_text":{"type":"string"},"new_text":{"type":"string"},"reason":{"type":"string","description":"向用户解释修改了什么、为什么修改"}},"required":["path","old_text","new_text","reason"]}}},
        {"type":"function","function":{"name":"delete_path","description":"删除工作目录中的一个文件或目录。删除目录时会在一次调用中递归删除其全部内容，不要先列目录或逐个删除子文件。","parameters":{"type":"object","properties":{"path":{"type":"string"},"reason":{"type":"string","description":"向用户解释为什么删除该路径"}},"required":["path","reason"]}}},
        {"type":"function","function":{"name":"open_preview","description":"启动与聊天页右上角可视化按钮相同的运行预览。仅当用户明确要求运行、打开预览或可视化时，在文件完成后调用。","parameters":{"type":"object","properties":{"type":{"type":"string","enum":["auto","web","xml"],"description":"网页项目用 web，Android 布局用 xml，不确定时用 auto"},"reason":{"type":"string","description":"说明要启动哪种预览"}},"required":["type","reason"]}}},
        {"type":"function","function":{"name":"search_files","description":"搜索文件名和内容","parameters":{"type":"object","properties":{"path":{"type":"string"},"query":{"type":"string"},"max_results":{"type":"integer"}},"required":["path","query"]}}},
        {"type":"function","function":{"name":"request_directory_access","description":"当用户要求访问当前 workspace:/ 和已挂载 extra:/N/ 以外的系统目录时，弹出 Android 系统目录选择器请求用户授权。必须在尝试访问 down:/、download:/、downloads:/、绝对路径等不存在路径之前调用；用户选择后工具返回真实 extra:/N/ 挂载路径，再使用该路径继续任务。","parameters":{"type":"object","properties":{"purpose":{"type":"string","description":"向用户说明为什么需要该目录"},"suggested_directory":{"type":"string","description":"建议用户在系统选择器中选择的目录名称，例如 下载"}},"required":["purpose","suggested_directory"]}}},
        {"type":"function","function":{"name":"http_request","description":"真实访问公网 URL","parameters":{"type":"object","properties":{"url":{"type":"string"},"method":{"type":"string","enum":["GET","HEAD"]}},"required":["url"]}}},
        {"type":"function","function":{"name":"remote_connections","description":"列出用户在 Ycode 中保存的 SSH/SFTP/FTP 连接，不返回密码。没有聊天临时凭据时先调用此工具，禁止猜 connection_id。","parameters":{"type":"object","properties":{}}}},
        {"type":"function","function":{"name":"ssh_exec","description":"在已保存且允许执行命令的 SSH/SFTP 连接上执行非交互命令。","parameters":{"type":"object","properties":{"connection_id":{"type":"string"},"command":{"type":"string"},"timeout_seconds":{"type":"integer","minimum":1,"maximum":45}},"required":["connection_id","command"]}}},
        {"type":"function","function":{"name":"remote_list","description":"列出已保存 SFTP/FTP 连接的远程目录。","parameters":{"type":"object","properties":{"connection_id":{"type":"string"},"path":{"type":"string"}},"required":["connection_id","path"]}}},
        {"type":"function","function":{"name":"remote_read","description":"读取已保存 SFTP/FTP 连接的远程文本文件，最大512KB。","parameters":{"type":"object","properties":{"connection_id":{"type":"string"},"path":{"type":"string"}},"required":["connection_id","path"]}}},
        {"type":"function","function":{"name":"remote_write","description":"写入已保存 SFTP/FTP 连接的远程文本文件，连接必须允许写入。","parameters":{"type":"object","properties":{"connection_id":{"type":"string"},"path":{"type":"string"},"content":{"type":"string"}},"required":["connection_id","path","content"]}}},
        {"type":"function","function":{"name":"remote_mkdir","description":"在已保存 SFTP/FTP 连接创建远程目录，连接必须允许写入。","parameters":{"type":"object","properties":{"connection_id":{"type":"string"},"path":{"type":"string"}},"required":["connection_id","path"]}}},
        {"type":"function","function":{"name":"remote_move","description":"移动或重命名已保存 SFTP/FTP 连接的远程路径，连接必须允许写入。","parameters":{"type":"object","properties":{"connection_id":{"type":"string"},"source":{"type":"string"},"target":{"type":"string"}},"required":["connection_id","source","target"]}}},
        {"type":"function","function":{"name":"remote_delete","description":"递归删除已保存 SFTP/FTP 连接的远程路径，连接必须明确允许删除；禁止删除远程根目录。","parameters":{"type":"object","properties":{"connection_id":{"type":"string"},"path":{"type":"string"}},"required":["connection_id","path"]}}},
        {"type":"function","function":{"name":"direct_ssh_exec","description":"使用用户在当前聊天中直接提供的临时主机、端口、用户名、密码和主机指纹执行 SSH 命令；凭据不保存且不得在回复中复述。","parameters":{"type":"object","properties":{"protocol":{"type":"string","enum":["ssh","sftp"]},"host":{"type":"string"},"port":{"type":"integer"},"username":{"type":"string"},"password":{"type":"string"},"host_key_sha256":{"type":"string"},"command":{"type":"string"},"timeout_seconds":{"type":"integer","minimum":1,"maximum":45}},"required":["protocol","host","username","password","host_key_sha256","command"]}}},
        {"type":"function","function":{"name":"direct_remote","description":"使用用户在当前聊天中直接提供的临时凭据执行 SFTP/FTP 文件操作；凭据不保存。SFTP 必须提供 host_key_sha256，删除必须传 confirm_delete=true。","parameters":{"type":"object","properties":{"protocol":{"type":"string","enum":["sftp","ftp"]},"host":{"type":"string"},"port":{"type":"integer"},"username":{"type":"string"},"password":{"type":"string"},"host_key_sha256":{"type":"string","description":"SFTP 必填；FTP 不使用"},"action":{"type":"string","enum":["list","read","write","mkdir","move","delete"]},"path":{"type":"string"},"source":{"type":"string"},"target":{"type":"string"},"content":{"type":"string"},"confirm_delete":{"type":"boolean"}},"required":["protocol","host","username","password","action"]}}},
        {"type":"function","function":{"name":"todo_write","description":"创建或更新用户可见的任务计划。开始任务时必须首先调用。","parameters":{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"content":{"type":"string"},"status":{"type":"string","enum":["pending","in_progress","completed","failed"]},"priority":{"type":"string","enum":["high","medium","low"]}},"required":["content","status"]}}},"required":["items"]}}}
    ]""", JsonArray::class.java)
    private fun definitions(): JsonArray = gson.fromJson("""[{"type":"function","function":{"name":"list_directory","description":"列出工作目录","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}},{"type":"function","function":{"name":"read_file","description":"读取文本文件。小于等于1MB时首次调用一次性返回全文且hasMore=false，禁止再次读取；大文件仅在hasMore=true时原样使用nextCursor继续，禁止重复游标。","parameters":{"type":"object","properties":{"path":{"type":"string"},"cursor":{"type":"integer"},"batch_size":{"type":"integer"}},"required":["path"]}}},{"type":"function","function":{"name":"write_file","description":"创建或覆盖一个文件。创建项目时每个文件单独调用一次。","parameters":{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}}},{"type":"function","function":{"name":"create_directory","description":"创建一个目录。目录完成后必须继续写入计划中的文件，不能在此停止。","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}},{"type":"function","function":{"name":"edit_file","description":"精确替换编辑文件","parameters":{"type":"object","properties":{"path":{"type":"string"},"old_text":{"type":"string"},"new_text":{"type":"string"}},"required":["path","old_text","new_text"]}}},{"type":"function","function":{"name":"search_files","description":"搜索文件名和内容","parameters":{"type":"object","properties":{"path":{"type":"string"},"query":{"type":"string"},"max_results":{"type":"integer"}},"required":["path","query"]}}},{"type":"function","function":{"name":"http_request","description":"真实访问公网 URL","parameters":{"type":"object","properties":{"url":{"type":"string"},"method":{"type":"string","enum":["GET","HEAD"]}},"required":["url"]}}},{"type":"function","function":{"name":"todo_write","description":"创建或更新用户可见的任务计划。开始任务时必须首先调用；每完成一步都要更新状态。","parameters":{"type":"object","properties":{"items":{"type":"array","items":{"type":"object","properties":{"content":{"type":"string"},"status":{"type":"string","enum":["pending","in_progress","completed","failed"]},"priority":{"type":"string","enum":["high","medium","low"]}},"required":["content","status"]}}},"required":["items"]}}}]""", JsonArray::class.java)
    private fun label(name: String) = mapOf("list_directory" to "查看目录", "read_file" to "读取文件", "write_file" to "写入文件", "create_directory" to "创建目录", "edit_file" to "编辑文件", "delete_path" to "删除路径", "open_preview" to "启动可视化", "request_directory_access" to "申请目录权限", "search_files" to "搜索文件", "http_request" to "网络请求", "remote_connections" to "查看远程连接", "ssh_exec" to "执行 SSH 命令", "direct_ssh_exec" to "临时 SSH 连接", "remote_list" to "查看远程目录", "remote_read" to "读取远程文件", "remote_write" to "写入远程文件", "remote_mkdir" to "创建远程目录", "remote_move" to "移动远程路径", "remote_delete" to "删除远程路径", "direct_remote" to "临时远程连接", "todo_write" to "任务清单")[name] ?: name
    private fun letter(name: String) = mapOf("list_directory" to "L", "read_file" to "R", "write_file" to "W", "create_directory" to "D", "edit_file" to "M", "search_files" to "S", "http_request" to "H", "todo_write" to "T")[name] ?: "·"

    private val PLAN_STATUSES = setOf("pending", "in_progress", "completed", "failed")
    private val REMOTE_TOOL_NAMES = setOf("remote_connections", "ssh_exec", "remote_list", "remote_read", "remote_write", "remote_mkdir", "remote_move", "remote_delete", "direct_ssh_exec", "direct_remote")
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, SessionState>()
    private const val SYSTEM_PROMPT = """你是 Ycode Android 的本机任务代理。你必须持续推进用户最新任务直到交付可用结果，不能只描述准备做什么。
执行规则：
1. 每个新任务首先调用 todo_write 列出具体、可验证的步骤，并将第一步设为 in_progress。
1.1 计划必须严格遵守用户原始意图。用户只要求查看、读取、列出或分析时，禁止擅自加入修改、写入、删除等改变内容的步骤。
2. 开始每一步前更新 todo_write；完成后标记 completed，再推进下一步。任何时刻最多一个 in_progress。
3. 创建项目时先规划目录和文件，再逐个调用工具完成内容，不能在创建空目录后停止。
4. 每次只推进清单中的明确操作。工具成功后继续下一项；工具失败或超时后更新计划、调整方案并重试，不要无限等待。
5. 只有真实工具结果成功后才能声称操作已完成。任务结束前应确认所有计划项已交付。
6. 本会话保留之前所有工具结果。已读取的目录和文件结果仍在上下文中，除非用户说明文件在外部发生变化，否则禁止重新 list_directory 或 read_file。小于等于1MB的文件首次 read_file 会返回全文且 hasMore=false，此后禁止重复读取；大文件仅在 hasMore=true 时使用 nextCursor。
7. 调用 read_file、write_file、edit_file 或 delete_path 时必须提供具体 reason。写入前解释该文件要写什么和用途，写入后再继续下一个文件。
8. delete_path 可以删除授权工作目录中的文件或目录，但禁止删除 workspace:/ 或 extra:/ 根目录。删除目录会在一次工具调用中递归删除全部内容；用户要求删除整个目录时，禁止先 list_directory 或逐个删除子文件。
9. 如果用户明确要求“运行”“预览”“可视化”“打开看看”等，在相关文件全部写入并检查完成后必须调用 open_preview。网页使用 web，Android res/layout XML 使用 xml；用户未要求时禁止擅自启动。
10. open_preview 成功调用后才可以告诉用户预览已启动。最终回复简洁汇报完成内容、文件路径、失败项和当前状态。
11. 只能访问 workspace:/ 和 workspaceContext 明确列出的 extra:/N/。用户要求访问下载、文档、图片等未挂载系统目录时，必须先调用 request_directory_access；绝对禁止猜测 down:/、download:/、downloads:/、/sdcard 等路径。用户取消授权后不得继续尝试相似路径。
12. SSH/SFTP/FTP 是 Ycode 内置能力。用户在聊天中直接提供主机、端口、用户名和密码时使用 direct_ssh_exec 或 direct_remote，凭据不得复述；否则先调用 remote_connections，禁止猜 connection_id。"""
}
