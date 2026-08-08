package com.ycode.app.network

import android.util.Base64
import com.ycode.app.model.ChatMessage
import java.io.File

object MessageContent {
    fun build(message: ChatMessage, prefix: String = ""): Any {
        val attachments = message.attachments.orEmpty()
        val textAttachments = attachments.filterNot { it.image }
        val images = attachments.filter { it.image }
        val text = buildString {
            append(prefix)
            append(message.content)
            textAttachments.forEach { attachment ->
                append("\n\n--- 附件源码：${attachment.name} ---\n")
                append(runCatching { File(attachment.localPath).readText() }.getOrElse { "[附件读取失败：${it.message}]" })
                append("\n--- 附件结束 ---")
            }
        }
        if (images.isEmpty()) return text
        return buildList<Map<String, Any?>> {
            add(mapOf("type" to "text", "text" to text.ifBlank { "请查看所附图片。" }))
            images.forEach { attachment ->
                val data = Base64.encodeToString(File(attachment.localPath).readBytes(), Base64.NO_WRAP)
                add(mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:${attachment.mimeType};base64,$data")))
            }
        }
    }
}
