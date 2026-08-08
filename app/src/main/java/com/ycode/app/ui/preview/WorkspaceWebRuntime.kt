package com.ycode.app.ui.preview

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object WorkspaceWebRuntime {
    data class Entry(val path: String, val file: DocumentFile)

    fun root(context: Context, uri: Uri?): DocumentFile? = uri?.let { DocumentFile.fromTreeUri(context, it) }

    fun webEntries(root: DocumentFile): List<Entry> = buildList {
        walk(root, "", 0) { path, file -> if (file.isFile && file.name?.substringAfterLast('.', "")?.lowercase() in setOf("html", "htm", "php")) add(Entry(path, file)) }
    }.sortedWith(compareBy<Entry> { when (it.path.substringAfterLast('/').lowercase()) { "index.html", "index.htm" -> 0; else -> 1 } }.thenBy { it.path })

    fun layoutEntries(root: DocumentFile): List<Entry> = buildList {
        walk(root, "", 0) { path, file ->
            val normalized = path.replace('\\', '/').lowercase()
            if (file.isFile && normalized.endsWith(".xml") && (normalized.contains("/res/layout/") || normalized.startsWith("res/layout/"))) add(Entry(path, file))
        }
    }.sortedBy { it.path }

    fun resolve(root: DocumentFile, encodedPath: String): DocumentFile? {
        val clean = URLDecoder.decode(encodedPath.substringBefore('?').trimStart('/'), StandardCharsets.UTF_8.name())
        var current = root
        clean.split('/').filter { it.isNotBlank() && it != "." }.forEach { segment ->
            if (segment == "..") return null
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    fun mime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs", "cjs" -> "application/javascript"
        "json", "map" -> "application/json"
        "xml" -> "application/xml"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    fun walk(root: DocumentFile, prefix: String, depth: Int, visit: (String, DocumentFile) -> Unit) {
        if (depth > 12) return
        root.listFiles().forEach { file ->
            val path = if (prefix.isBlank()) file.name.orEmpty() else "$prefix/${file.name.orEmpty()}"
            visit(path, file)
            if (file.isDirectory) walk(file, path, depth + 1, visit)
        }
    }
}
