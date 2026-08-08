package com.ycode.app.model

import com.google.gson.annotations.SerializedName

data class ChatMessage(
    val id: String = System.nanoTime().toString(),
    val role: String,
    var content: String,
    val mode: String = "chat",
    var streaming: Boolean = false,
    var error: Boolean = false,
    val attachments: MutableList<ChatAttachment>? = mutableListOf(),
    var toolDetails: MutableList<ToolDetail>? = mutableListOf(),
    var liveLogs: MutableList<String>? = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis()
)

data class ToolDetail(
    val action: String,
    val path: String,
    var status: String,
    var detail: String = "",
    var elapsedSeconds: Long = 0
)

data class ChatAttachment(
    val name: String,
    val mimeType: String,
    val localPath: String,
    val size: Long,
    val image: Boolean
)

data class Conversation(
    val id: String,
    var topic: String,
    val messages: MutableList<ChatMessage>,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

data class Provider(
    val id: String,
    val name: String,
    val description: String,
    val baseUrl: String,
    val defaultModel: String,
    val badge: String,
    val color: String = "#146CFF"
)

data class ProviderConfig(
    var baseUrl: String = "",
    var apiKey: String = "",
    @SerializedName(value = "availableModels", alternate = ["models"])
    var availableModels: MutableList<String> = mutableListOf(),
    var selectedModels: MutableList<String> = mutableListOf(),
    var enabled: Boolean = false
)

data class CurrentModel(val providerId: String, val model: String)
data class ChatRules(var enabled: Boolean = false, var systemPrompt: String = "", var style: String = "", var constraints: String = "")
data class ToolEvent(val id: String, val name: String, val target: String, var status: String, var detail: String = "")
data class DirectoryGrant(val name: String, val uri: String, val flags: Int, val grantedAt: Long = System.currentTimeMillis())
data class TaskLog(val id: String = java.util.UUID.randomUUID().toString(), val timestamp: Long = System.currentTimeMillis(), val action: String, val path: String = "", val reason: String = "", val result: String = "info", val conversationId: String = "")
data class LocalModelUsage(
    val timestamp: Long = System.currentTimeMillis(),
    val providerId: String,
    val providerName: String,
    val model: String,
    val keyFingerprint: String,
    val inputChars: Int,
    val outputChars: Int,
    val durationMs: Long,
    val success: Boolean
)
