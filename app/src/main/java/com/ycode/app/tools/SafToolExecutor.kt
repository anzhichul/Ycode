package com.ycode.app.tools

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.google.gson.JsonObject
import com.ycode.app.data.AppStore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class SafToolExecutor(private val context: Context, private val store: AppStore, private val sessionId: String) {
    private val resolver get() = context.contentResolver
    private val root get() = store.workspaceUri?.let { DocumentFile.fromTreeUri(context, it) }
    private val readCache = sessionReadCaches.getOrPut(sessionId) { mutableMapOf() }
    private val directoryCache = sessionDirectoryCaches.getOrPut(sessionId) { mutableMapOf() }
    private val fullReadLimit = 1024 * 1024

    fun workspaceContext(): String = buildString {
        append("主工作目录：workspace:/")
        store.additionalGrants().forEachIndexed { index, grant -> append("；额外目录 extra:/$index/ = ${grant.name}") }
        if (readCache.isNotEmpty()) append("；本会话已缓存 ${readCache.size} 个文件读取批次，文件未变化时会直接复用，禁止为了确认而重复读取")
        if (directoryCache.isNotEmpty()) append("；本会话已缓存 ${directoryCache.size} 个目录清单，目录未变化时禁止重复列出")
    }

    fun execute(name: String, args: JsonObject): Any {
        val target = args.get("path")?.asString ?: args.get("url")?.asString.orEmpty()
        return try {
            val result = when (name) {
                "list_directory" -> list(args.string("path"), args.int("depth", 2).coerceIn(1, 3))
                "read_file" -> read(args.string("path"), args.int("cursor", 0), args.int("batch_size", 65536))
                "write_file" -> write(args.string("path"), args.get("content")?.asString ?: error("content 必须提供"))
                "create_directory" -> mkdir(args.string("path"))
                "edit_file" -> edit(args.string("path"), args.string("old_text"), args.get("new_text")?.asString ?: error("new_text 必须提供"))
                "delete_path" -> delete(args.string("path"))
                "search_files" -> search(args.string("path"), args.string("query"), args.int("max_results", 50).coerceAtLeast(1))
                "http_request" -> http(args.string("url"), args.get("method")?.asString ?: "GET")
                "todo_write" -> mapOf("saved" to true, "items" to (args.get("items") ?: emptyList<Any>()))
                else -> error("不支持的工具：$name")
            }
            store.addTaskLog("tool_$name", target, "AI 执行 $name", "completed", sessionId)
            result
        } catch (error: Throwable) {
            store.addTaskLog("tool_$name", target, error.message.orEmpty(), "failed", sessionId)
            throw error
        }
    }

    private fun JsonObject.string(key: String): String = get(key)?.asString?.takeIf { it.isNotBlank() } ?: error("$key 必须提供")
    private fun JsonObject.int(key: String, fallback: Int): Int = get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: fallback
    private fun segments(path: String): List<String> {
        val normalized = path.replace('\\', '/')
        val relative = when {
            normalized.startsWith("workspace:/") -> normalized.removePrefix("workspace:/")
            normalized.startsWith("extra:/") -> normalized.removePrefix("extra:/").substringAfter('/', "")
            else -> normalized
        }
        return relative.split('/').filter { it.isNotBlank() && it != "." }.also { require(".." !in it) { "路径不能包含 .." } }
    }

    private fun rootFor(path: String): DocumentFile {
        if (!path.replace('\\', '/').startsWith("extra:/")) return root ?: error("请先选择工作目录")
        val index = path.replace('\\', '/').removePrefix("extra:/").substringBefore('/').toIntOrNull() ?: error("额外目录路径无效")
        val grant = store.additionalGrants().getOrNull(index) ?: error("额外目录授权不存在")
        return DocumentFile.fromTreeUri(context, android.net.Uri.parse(grant.uri)) ?: error("额外目录授权已失效")
    }
    private fun resolve(path: String, createFile: Boolean = false): DocumentFile {
        var current = rootFor(path)
        segments(path).forEachIndexed { index, name ->
            var child = current.listFiles().firstOrNull { it.name == name }
            if (child == null && createFile && index == segments(path).lastIndex) child = createNamedFile(current, name)
            current = child ?: error("路径不存在：$name")
        }
        return current
    }
    private fun parent(path: String): Pair<DocumentFile, String> {
        val normalized = path.replace('\\', '/').trimEnd('/')
        val parts = segments(normalized)
        require(parts.isNotEmpty()) { "文件路径无效" }
        val prefix = if (normalized.startsWith("extra:/")) "extra:/${normalized.removePrefix("extra:/").substringBefore('/')}/" else "workspace:/"
        val parentPath = prefix + parts.dropLast(1).joinToString("/")
        return resolve(parentPath) to parts.last()
    }
    private fun list(path: String, depth: Int): Map<String, Any> {
        val cacheKey = "$path|$depth"
        directoryCache[cacheKey]?.let { return mapOf("entries" to it, "depth" to depth, "cached" to true, "message" to "目录树已在本会话读取且未发生写入，请使用缓存结果") }
        val dir = resolve(path); require(dir.isDirectory) { "不是目录" }
        val base = path.replace('\\', '/').trimEnd('/')
        val entries = mutableListOf<Map<String, Any>>()
        fun walk(current: DocumentFile, currentPath: String, level: Int) {
            if (entries.size >= 1000) return
            current.listFiles().forEach { child ->
                if (entries.size >= 1000) return@forEach
                val childPath = "$currentPath/${child.name.orEmpty()}"
                entries += mapOf("name" to child.name.orEmpty(), "path" to childPath, "type" to if (child.isDirectory) "directory" else "file", "level" to level)
                if (child.isDirectory && level < depth) walk(child, childPath, level + 1)
            }
        }
        walk(dir, base, 1)
        directoryCache[cacheKey] = entries
        return mapOf("entries" to entries, "depth" to depth, "truncated" to (entries.size >= 1000), "cached" to false)
    }
    private fun read(path: String, cursor: Int, batchSize: Int): Map<String, Any> {
        val file = resolve(path)
        require(file.isFile) { "不是文件" }
        val total = file.length().coerceAtLeast(0)
        val completeRead = total <= fullReadLimit
        val start = if (completeRead) 0L else cursor.coerceAtLeast(0).toLong().coerceAtMost(total)
        val cacheKey = "$path:${file.lastModified()}:$total:$start"
        readCache[cacheKey]?.let { cached ->
            return (cached - "content") + mapOf("cached" to true, "message" to "该文件内容已存在于本会话上下文，未再次访问磁盘或重复返回源码；请使用先前结果继续")
        }

        val bytes = resolver.openInputStream(file.uri)?.use { stream ->
            var remaining = start
            while (remaining > 0) {
                val skipped = stream.skip(remaining)
                if (skipped <= 0) break
                remaining -= skipped
            }
            if (completeRead) stream.readBytes()
            else {
                val buffer = ByteArray(batchSize.coerceIn(1024, 65536))
                val read = stream.read(buffer).coerceAtLeast(0)
                buffer.copyOf(read)
            }
        } ?: error("无法读取文件")
        val end = start + bytes.size
        val result = mapOf<String, Any>(
            "content" to String(bytes, StandardCharsets.UTF_8),
            "cursor" to start,
            "nextCursor" to end,
            "hasMore" to (!completeRead && end < total),
            "complete" to (completeRead || end >= total),
            "bytesRead" to bytes.size,
            "totalBytes" to total,
            "readMode" to if (completeRead) "full" else "batch",
            "message" to if (completeRead) "文件已一次性完整读取，请直接继续当前任务，不要重复读取" else "已读取当前批次"
        )
        readCache.keys.removeAll { it.startsWith("$path:") && it != cacheKey }
        readCache[cacheKey] = result
        return result
    }
    private fun write(path: String, content: String): Map<String, Any> {
        val bytes = content.toByteArray()
        require(bytes.size <= 2 * 1024 * 1024) { "写入内容超过 2 MB" }
        val (directory, name) = parent(path)
        val legacy = directory.findFile("$name.txt")?.takeIf { name.substringAfterLast('.', "").lowercase() in SOURCE_EXTENSIONS }
        if (directory.findFile(name) == null && legacy != null && !legacy.renameTo(name)) error("无法将 ${legacy.name} 修正为 $name")
        val file = directory.findFile(name) ?: createNamedFile(directory, name)
        resolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) } ?: error("无法写入文件")
        readCache.keys.removeAll { it.startsWith("$path:") }
        invalidateDirectories(path)
        return mapOf("path" to path, "actualName" to (file.name ?: name), "bytes" to bytes.size)
    }

    private fun createNamedFile(directory: DocumentFile, name: String): DocumentFile {
        val created = directory.createFile(mimeType(name), name) ?: error("无法创建文件")
        if (created.name != name && !created.renameTo(name)) error("系统创建了错误文件名 ${created.name}，且无法修正为 $name")
        return directory.findFile(name) ?: created
    }

    private fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs", "cjs" -> "application/javascript"
        "json", "jsonl" -> "application/json"
        "xml" -> "application/xml"
        "md", "markdown" -> "text/markdown"
        "csv" -> "text/csv"
        "yaml", "yml" -> "application/yaml"
        "txt", "log" -> "text/plain"
        else -> "application/octet-stream"
    }
    private fun mkdir(path: String): Map<String, Any> { var current = rootFor(path); segments(path).forEach { name -> current = current.findFile(name) ?: current.createDirectory(name) ?: error("无法创建目录：$name"); require(current.isDirectory) { "$name 不是目录" } }; invalidateDirectories(path); return mapOf("path" to path, "created" to true) }
    private fun edit(path: String, old: String, new: String): Map<String, Any> { require(old.isNotEmpty()) { "old_text 不能为空" }; val file = resolve(path); require(file.length() <= 2 * 1024 * 1024) { "文件超过 2 MB，拒绝整体编辑" }; val content = resolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() } ?: error("无法读取文件"); require(content.split(old).size - 1 == 1) { "old_text 必须唯一匹配" }; return write(path, content.replace(old, new)) }
    private fun delete(path: String): Map<String, Any> {
        require(segments(path).isNotEmpty()) { "禁止删除工作目录根目录" }
        val target = resolve(path)
        val type = if (target.isDirectory) "directory" else "file"
        var deletedFiles = 0
        var deletedDirectories = 0
        fun deleteRecursively(item: DocumentFile) {
            val directory = item.isDirectory
            if (directory) item.listFiles().forEach(::deleteRecursively)
            require(item.delete()) { "系统拒绝删除：${item.name ?: path}" }
            if (directory) deletedDirectories++ else deletedFiles++
        }
        deleteRecursively(target)
        readCache.keys.removeAll { it.startsWith("$path:") || it.startsWith(path.trimEnd('/') + "/") }
        invalidateDirectories(path)
        return mapOf("path" to path, "type" to type, "deleted" to true, "deletedFiles" to deletedFiles, "deletedDirectories" to deletedDirectories)
    }
    private fun search(path: String, query: String, max: Int): List<Map<String, String>> { val results = mutableListOf<Map<String, String>>(); var files = 0; fun walk(dir: DocumentFile, prefix: String, depth: Int) { if (results.size >= max) return; dir.listFiles().forEach { item -> if (results.size >= max) return@forEach; val target = "$prefix/${item.name}"; if (item.name?.contains(query, true) == true) results += mapOf("path" to target, "match" to "name"); if (item.isDirectory) walk(item, target, depth + 1) else { files++; runCatching { resolver.openInputStream(item.uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()?.takeIf { it.contains(query, true) }?.let { results += mapOf("path" to target, "match" to "content") } } } }; walk(resolve(path), path.trimEnd('/'), 0); return results.take(max) }
    private fun http(url: String, method: String): Map<String, Any> { val uri = URI(url); require(uri.scheme in listOf("http", "https")) { "URL 必须使用 HTTP/HTTPS" }; require(method.uppercase() in listOf("GET", "HEAD")) { "只允许 GET/HEAD" }; InetAddress.getAllByName(uri.host).forEach { require(!it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isSiteLocalAddress && !it.isLinkLocalAddress) { "禁止访问本机或局域网" } }; val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(); val request = Request.Builder().url(url).method(method.uppercase(), null).build(); return client.newCall(request).execute().use { response -> val content = response.body?.string().orEmpty(); mapOf("statusCode" to response.code, "headers" to response.headers.toMultimap(), "content" to content.take(65536), "truncated" to (content.length > 65536), "bytesReceived" to content.length) } }

    private fun invalidateDirectories(path: String) {
        val normalized = path.replace('\\', '/')
        directoryCache.keys.removeAll { key ->
            val cached = key.substringBeforeLast('|', key)
            normalized.startsWith(cached.trimEnd('/') + "/") || cached.startsWith(normalized.trimEnd('/') + "/") || cached == normalized
        }
    }

    companion object {
        private val sessionReadCaches = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, Map<String, Any>>>()
        private val sessionDirectoryCaches = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, List<Map<String, Any>>>>()
        private val SOURCE_EXTENSIONS = setOf(
            "html", "htm", "css", "scss", "sass", "less", "js", "mjs", "cjs", "jsx", "ts", "tsx", "json", "jsonl", "xml",
            "yaml", "yml", "md", "markdown", "csv", "kt", "kts", "java", "py", "c", "h", "cc", "cpp", "cxx", "hpp", "cs",
            "go", "rs", "rb", "php", "swift", "dart", "lua", "r", "sql", "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd",
            "gradle", "properties", "toml", "ini", "conf", "env", "vue", "svelte"
        )
    }
}
