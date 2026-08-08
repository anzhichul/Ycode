package com.ycode.app.data

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ycode.app.model.*

class AppStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("ycode.preferences", Context.MODE_PRIVATE)
    private val gson = Gson()

    var onboardingComplete: Boolean
        get() = prefs.getBoolean("onboarding_complete", false)
        set(value) = prefs.edit().putBoolean("onboarding_complete", value).apply()

    fun providerConfigs(): MutableMap<String, ProviderConfig> = runCatching {
        gson.fromJson<MutableMap<String, ProviderConfig>>(prefs.getString("provider_configs_json", "{}"), object : TypeToken<MutableMap<String, ProviderConfig>>() {}.type)
    }.getOrNull() ?: mutableMapOf()

    fun providerConfig(provider: Provider): ProviderConfig {
        val config = providerConfigs()[provider.id]
            ?: ProviderConfig(provider.baseUrl, "", provider.defaultModel.takeIf { it.isNotBlank() }?.let { mutableListOf(it) } ?: mutableListOf(), provider.defaultModel.takeIf { it.isNotBlank() }?.let { mutableListOf(it) } ?: mutableListOf())
        return config
    }

    fun saveProvider(providerId: String, config: ProviderConfig) {
        val configs = providerConfigs()
        configs[providerId] = config
        prefs.edit().putString("provider_configs_json", gson.toJson(configs)).apply()
    }

    fun enabledProviders(): List<Pair<Provider, ProviderConfig>> = ProviderCatalog.all.map { it to providerConfig(it) }.filter { it.second.enabled && it.second.selectedModels.isNotEmpty() }

    fun currentModel(): Pair<Provider, ProviderConfig>? {
        val current = runCatching { gson.fromJson(prefs.getString("current_model_json", ""), CurrentModel::class.java) }.getOrNull()
        val selected = enabledProviders().firstOrNull { current != null && it.first.id == current.providerId && it.second.selectedModels.contains(current.model) }
        if (selected != null && current != null) {
            val ordered = selected.second.selectedModels.toMutableList().apply { remove(current.model); add(0, current.model) }
            return selected.first to selected.second.copy(selectedModels = ordered)
        }
        return enabledProviders().firstOrNull()
    }

    fun selectModel(providerId: String, model: String) = prefs.edit().putString("current_model_json", gson.toJson(CurrentModel(providerId, model))).apply()

    fun localUsage(): MutableList<LocalModelUsage> = runCatching {
        gson.fromJson<MutableList<LocalModelUsage>>(prefs.getString("local_model_usage_json", "[]"), object : TypeToken<MutableList<LocalModelUsage>>() {}.type)
    }.getOrNull() ?: mutableListOf()

    fun recordUsage(provider: Provider, config: ProviderConfig, model: String, inputChars: Int, outputChars: Int, durationMs: Long, success: Boolean) {
        val fingerprint = runCatching {
            java.security.MessageDigest.getInstance("SHA-256").digest(config.apiKey.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
        }.getOrDefault("unknown")
        val values = localUsage()
        values.add(0, LocalModelUsage(providerId = provider.id, providerName = provider.name, model = model, keyFingerprint = fingerprint, inputChars = inputChars, outputChars = outputChars, durationMs = durationMs, success = success))
        prefs.edit().putString("local_model_usage_json", gson.toJson(values.take(2000))).apply()
    }

    fun clearLocalUsage() = prefs.edit().remove("local_model_usage_json").apply()

    fun conversations(): MutableList<Conversation> = runCatching {
        gson.fromJson<MutableList<Conversation>>(prefs.getString("chat_history_json", "[]"), object : TypeToken<MutableList<Conversation>>() {}.type)
    }.getOrNull() ?: mutableListOf()

    fun saveConversation(conversation: Conversation) {
        conversation.updatedAt = System.currentTimeMillis()
        val all = conversations().filterNot { it.id == conversation.id }.toMutableList()
        all.add(0, conversation)
        prefs.edit().putString("chat_history_json", gson.toJson(all.take(200))).apply()
    }

    fun rules(): ChatRules = runCatching { gson.fromJson(prefs.getString("chat_rules_json", ""), ChatRules::class.java) }.getOrNull() ?: ChatRules()
    fun saveRules(rules: ChatRules) = prefs.edit().putString("chat_rules_json", gson.toJson(rules)).apply()

    var activeConversationId: String
        get() = prefs.getString("active_conversation_id", "default") ?: "default"
        private set(value) = prefs.edit().putString("active_conversation_id", value).apply()

    fun activateConversation(conversationId: String) {
        activeConversationId = conversationId
        val grants = workspaceGrants()
        if (conversationId !in grants) {
            val legacy = runCatching { gson.fromJson(prefs.getString("workspace_grant_json", ""), DirectoryGrant::class.java) }.getOrNull()
                ?: prefs.getString("workspace_uri", null)?.let { DirectoryGrant("已迁移的工作目录", it, 0) }
            if (legacy != null) {
                grants[conversationId] = legacy
                saveWorkspaceGrants(grants)
                prefs.edit().remove("workspace_grant_json").remove("workspace_uri").apply()
            }
        }
        val additional = additionalGrantMap()
        if (conversationId !in additional) {
            val legacy = runCatching {
                gson.fromJson<MutableList<DirectoryGrant>>(prefs.getString("additional_grants_json", "[]"), object : TypeToken<MutableList<DirectoryGrant>>() {}.type)
            }.getOrNull().orEmpty()
            if (legacy.isNotEmpty()) {
                additional[conversationId] = legacy.toMutableList()
                prefs.edit().putString("conversation_additional_grants_json", gson.toJson(additional)).remove("additional_grants_json").apply()
            }
        }
        val logs = taskLogs()
        if (logs.any { it.conversationId.isBlank() }) {
            prefs.edit().putString("task_logs_json", gson.toJson(logs.map { if (it.conversationId.isBlank()) it.copy(conversationId = conversationId) else it })).apply()
        }
    }

    private fun workspaceGrants(): MutableMap<String, DirectoryGrant> = runCatching {
        gson.fromJson<MutableMap<String, DirectoryGrant>>(prefs.getString("workspace_grants_json", "{}"), object : TypeToken<MutableMap<String, DirectoryGrant>>() {}.type)
    }.getOrNull() ?: mutableMapOf()
    private fun saveWorkspaceGrants(values: Map<String, DirectoryGrant>) = prefs.edit().putString("workspace_grants_json", gson.toJson(values)).apply()
    fun isGrantUsedByAnotherConversation(uri: String): Boolean = workspaceGrants().any { (id, grant) -> id != activeConversationId && grant.uri == uri } ||
        additionalGrantMap().any { (id, grants) -> id != activeConversationId && grants.any { it.uri == uri } }

    var workspaceUri: Uri?
        get() = workspaceGrant?.uri?.let(Uri::parse)
        set(value) {
            workspaceGrant = value?.let { DirectoryGrant("工作目录", it.toString(), 0) }
        }

    var workspaceGrant: DirectoryGrant?
        get() = workspaceGrants()[activeConversationId]
        set(value) {
            val values = workspaceGrants()
            if (value == null) values.remove(activeConversationId) else values[activeConversationId] = value
            saveWorkspaceGrants(values)
        }

    private fun additionalGrantMap(): MutableMap<String, MutableList<DirectoryGrant>> = runCatching {
        gson.fromJson<MutableMap<String, MutableList<DirectoryGrant>>>(prefs.getString("conversation_additional_grants_json", "{}"), object : TypeToken<MutableMap<String, MutableList<DirectoryGrant>>>() {}.type)
    }.getOrNull() ?: mutableMapOf()

    fun additionalGrants(): MutableList<DirectoryGrant> = additionalGrantMap()[activeConversationId]?.toMutableList() ?: mutableListOf()

    fun saveAdditionalGrants(grants: List<DirectoryGrant>) {
        val values = additionalGrantMap()
        if (grants.isEmpty()) values.remove(activeConversationId) else values[activeConversationId] = grants.toMutableList()
        prefs.edit().putString("conversation_additional_grants_json", gson.toJson(values)).apply()
    }

    fun taskLogs(): MutableList<TaskLog> = runCatching {
        gson.fromJson<MutableList<TaskLog>>(prefs.getString("task_logs_json", "[]"), object : TypeToken<MutableList<TaskLog>>() {}.type)
    }.getOrNull() ?: mutableListOf()

    fun conversationTaskLogs(conversationId: String = activeConversationId): List<TaskLog> = taskLogs().filter { it.conversationId == conversationId }

    fun addTaskLog(action: String, path: String = "", reason: String = "", result: String = "info", conversationId: String = activeConversationId) {
        val logs = taskLogs()
        logs.add(0, TaskLog(action = action.take(80), path = path.take(1024), reason = reason.take(1024), result = result.take(80), conversationId = conversationId))
        prefs.edit().putString("task_logs_json", gson.toJson(logs.take(500))).apply()
    }

    fun clearTaskLogs(conversationId: String = activeConversationId) = prefs.edit().putString("task_logs_json", gson.toJson(taskLogs().filterNot { it.conversationId == conversationId })).apply()
    fun taskLogsJson(conversationId: String = activeConversationId): String {
        val logs = conversationTaskLogs(conversationId)
        return gson.toJson(mapOf("product" to "Ycode", "type" to "AI file access audit log", "conversationId" to conversationId, "exportedAt" to System.currentTimeMillis(), "count" to logs.size, "logs" to logs))
    }
}
