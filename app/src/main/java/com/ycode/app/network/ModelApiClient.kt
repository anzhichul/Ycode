package com.ycode.app.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ycode.app.model.ChatMessage
import com.ycode.app.model.ProviderConfig
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object ModelApiClient {
    private val gson = Gson()
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()

    fun fetchModels(baseUrl: String, apiKey: String, done: (Result<List<String>>) -> Unit) {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/models").header("Authorization", "Bearer $apiKey").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = done(Result.failure(e))
            override fun onResponse(call: Call, response: Response) = response.use {
                if (!it.isSuccessful) return done(Result.failure(IOException("模型接口返回 ${it.code}")))
                val root = gson.fromJson(it.body?.string(), JsonObject::class.java)
                val source = root.getAsJsonArray("data") ?: root.getAsJsonArray("models")
                val models = source?.mapNotNull { item -> if (item.isJsonPrimitive) item.asString else item.asJsonObject.get("id")?.asString ?: item.asJsonObject.get("name")?.asString }?.distinct()?.sorted() ?: emptyList()
                done(if (models.isEmpty()) Result.failure(IOException("接口没有返回模型")) else Result.success(models))
            }
        })
    }

    fun stream(config: ProviderConfig, model: String, messages: List<ChatMessage>, onDelta: (String) -> Unit, done: (Result<Unit>) -> Unit): Call {
        return streamRaw(config, model, messages.map { mapOf("role" to it.role, "content" to MessageContent.build(it)) }, onDelta, done)
    }

    fun streamRaw(config: ProviderConfig, model: String, messages: List<Map<String, Any?>>, onDelta: (String) -> Unit, done: (Result<Unit>) -> Unit): Call {
        val payload = JsonObject().apply {
            addProperty("model", model); addProperty("stream", true); addProperty("temperature", 0.7)
            add("messages", gson.toJsonTree(messages))
        }
        val request = Request.Builder().url("${config.baseUrl.trimEnd('/')}/chat/completions").header("Authorization", "Bearer ${config.apiKey}").header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = done(Result.failure(e))
            override fun onResponse(call: Call, response: Response) = response.use {
                if (!it.isSuccessful) return done(Result.failure(IOException("模型请求失败 ${it.code}: ${it.body?.string()?.take(500)}")))
                val source = it.body?.source() ?: return done(Result.failure(IOException("响应为空")))
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    runCatching { gson.fromJson(data, JsonObject::class.java).getAsJsonArray("choices")?.get(0)?.asJsonObject?.getAsJsonObject("delta")?.get("content")?.asString }.getOrNull()?.takeIf(String::isNotEmpty)?.let(onDelta)
                }
                done(Result.success(Unit))
            }
        })
        return call
    }
}
