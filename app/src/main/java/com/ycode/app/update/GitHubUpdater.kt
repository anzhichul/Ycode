package com.ycode.app.update

import android.content.Context
import com.google.gson.JsonParser
import com.ycode.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object GitHubUpdater {
    private const val REPOSITORY = "anzhichul/Ycode"
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    fun check(context: Context, done: (Result<String>) -> Unit) {
        if (REPOSITORY.isBlank()) return done(Result.success("尚未配置 GitHub 仓库地址"))
        Thread {
            done(runCatching {
                val request = Request.Builder().url("https://api.github.com/repos/$REPOSITORY/releases/latest").header("Accept", "application/vnd.github+json").build()
                client.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "GitHub 返回 ${response.code}" }
                    val root = JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
                    val tag = root.get("tag_name")?.asString.orEmpty().removePrefix("v")
                    if (tag.isBlank()) error("Release 没有版本号")
                    if (isNewer(tag, BuildConfig.VERSION_NAME)) "发现新版本 v$tag" else "当前已是最新版本 v${BuildConfig.VERSION_NAME}"
                }
            })
        }.start()
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val a = remote.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val b = local.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        repeat(maxOf(a.size, b.size)) { index ->
            val diff = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
            if (diff != 0) return diff > 0
        }
        return false
    }
}
