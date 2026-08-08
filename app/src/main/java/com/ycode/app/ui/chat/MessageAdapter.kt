package com.ycode.app.ui.chat

import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.TypefaceSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ycode.app.R
import com.ycode.app.databinding.ItemMessageBinding
import com.ycode.app.model.ChatMessage

class MessageAdapter(private val onToolClick: (ChatMessage, String) -> Unit = { _, _ -> }) : RecyclerView.Adapter<MessageAdapter.Holder>() {
    val items = mutableListOf<ChatMessage>()
    class Holder(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) = with(holder.binding) {
        val message = items[position]
        val user = message.role == "user"
        val tool = message.role == "tool_event"
        val continuesAssistant = !user && position > 0 && items[position - 1].role in setOf("assistant", "tool_event")
        container.gravity = if (user) Gravity.END else Gravity.START
        author.text = if (user) "你" else "Ycode"
        avatar.visibility = if (user) View.GONE else View.VISIBLE
        avatar.text = "Y_"
        header.visibility = if (user || continuesAssistant) View.GONE else View.VISIBLE
        content.maxWidth = (root.resources.displayMetrics.widthPixels * .83).toInt()
        liveLog.maxWidth = content.maxWidth
        val attachmentLabel = message.attachments.orEmpty().joinToString("\n") { "${if (it.image) "[图片]" else "[文件]"} ${it.name}" }
        val operationTimeline = if (!user && !tool) operationTimeline(message) else ""
        val visibleContent = listOf(attachmentLabel, operationTimeline, message.content).filter(String::isNotBlank).joinToString("\n\n")
        content.text = if (tool) toolTimeline(message.content) else markdown(visibleContent + if (message.streaming) "  ▍" else "", message)
        content.setBackgroundResource(when { user -> R.drawable.bg_user_message; tool -> android.R.color.transparent; else -> R.drawable.bg_assistant_message })
        content.setTextColor(root.context.getColor(when { message.error -> R.color.red; tool -> R.color.muted; else -> R.color.text }))
        content.typeface = if (tool) Typeface.MONOSPACE else Typeface.DEFAULT
        val hasDetails = !user && !tool && message.toolDetails.orEmpty().isNotEmpty()
        content.movementMethod = if (hasDetails) LinkMovementMethod.getInstance() else null
        content.highlightColor = android.graphics.Color.TRANSPARENT
        content.setOnClickListener(null)
        content.isClickable = hasDetails
        val logs = message.liveLogs.orEmpty().takeLast(3)
        liveLog.visibility = if (!user && !tool && message.streaming && logs.isNotEmpty()) View.VISIBLE else View.GONE
        liveLog.text = logs.joinToString("\n") { "•  ${it.substringAfter("] ", it)}" }
        if (tool) {
            container.setPadding(0, dp(root, 1), 0, dp(root, 1))
            content.setPadding(dp(root, 10), dp(root, 4), dp(root, 8), dp(root, 4))
            content.textSize = 11f
        } else {
            container.setPadding(0, dp(root, 5), 0, dp(root, 7))
            content.setPadding(dp(root, 13), dp(root, 10), dp(root, 13), dp(root, 10))
            content.textSize = 13f
        }
    }
    fun replace(messages: List<ChatMessage>) { items.clear(); items.addAll(messages); notifyDataSetChanged() }
    fun append(message: ChatMessage) { items.add(message); notifyItemInserted(items.lastIndex) }
    fun updateLast() { if (items.isNotEmpty()) notifyItemChanged(items.lastIndex) }
    fun insertBefore(message: ChatMessage, before: ChatMessage) {
        val index = items.indexOfFirst { it.id == before.id }.takeIf { it >= 0 } ?: items.size
        items.add(index, message)
        notifyItemInserted(index)
    }
    fun update(message: ChatMessage) {
        items.indexOfFirst { it.id == message.id }.takeIf { it >= 0 }?.let(::notifyItemChanged)
    }
    private fun markdown(value: String, message: ChatMessage? = null): CharSequence {
        val normalized = fenceToolProtocol(value).replace(Regex("(?m)^#{1,3}\\s+"), "").replace("**", "")
        val codeRanges = mutableListOf<IntRange>()
        val clean = buildString {
            var cursor = 0
            Regex("(?s)```(?:[A-Za-z0-9_+.#-]+)?\\s*\\n?(.*?)```").findAll(normalized).forEach { match ->
                append(normalized.substring(cursor, match.range.first))
                if (isNotEmpty() && last() != '\n') append('\n')
                val start = length
                append(match.groupValues[1].trimEnd())
                codeRanges += start until length
                append('\n')
                cursor = match.range.last + 1
            }
            append(normalized.substring(cursor))
        }
        val span = SpannableString(clean)
        codeRanges.forEach { range ->
            if (range.isEmpty()) return@forEach
            span.setSpan(TypefaceSpan("monospace"), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(BackgroundColorSpan(0xFFF0F3F8.toInt()), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(ForegroundColorSpan(0xFF17253B.toInt()), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(LeadingMarginSpan.Standard(dpFromText(8)), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Regex("`([^`\\n]+)`").findAll(clean).filterNot { match -> codeRanges.any { match.range.first in it } }.forEach { span.setSpan(TypefaceSpan("monospace"), it.range.first, it.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        message?.toolDetails.orEmpty().map { it.action }.distinct().forEach { action ->
            Regex("(?m)^[✓●×]  ${Regex.escape(action)}  .+$").find(clean)?.let { match ->
                span.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) = onToolClick(message!!, action)
                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = widgetColor(message!!)
                        ds.isUnderlineText = false
                        ds.isFakeBoldText = true
                    }
                }, match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return span
    }

    private fun fenceToolProtocol(value: String): String = Regex("(?s)(<｜｜DSML｜｜tool_calls>.*?</｜｜DSML｜｜tool_calls>)").replace(value) { match ->
        "```text\n${match.value}\n```"
    }

    private fun dpFromText(value: Int) = (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

    private fun widgetColor(message: ChatMessage) = if (message.error) 0xFFC34555.toInt() else 0xFF146CFF.toInt()
    private fun toolTimeline(value: String): CharSequence {
        val lines = value.lines().filter(String::isNotBlank)
        if (lines.any { it.matches(Regex("(创建目录|写入文件|编辑文件|删除路径|读取文件|查看目录|搜索文件|网络请求) \\d+.*")) || it.startsWith("进行中：") || it.startsWith("失败：") || it.startsWith("说明：") }) {
            return lines.joinToString("\n") { line ->
                when {
                    line.startsWith("进行中：") -> "·  $line"
                    line.startsWith("失败：") -> "×  $line"
                    else -> "✓  $line"
                }
            }
        }
        val action = lines.firstOrNull()?.dropWhile { !it.isWhitespace() }?.trim().orEmpty()
        val target = lines.getOrNull(1)?.substringAfterLast('/')?.substringAfterLast('\\').orEmpty()
        val status = lines.lastOrNull().orEmpty()
        val marker = when {
            status.startsWith("失败") -> "×"
            status == "完成" -> "✓"
            else -> "·"
        }
        val detail = when {
            status == "运行中" -> "…"
            status.startsWith("失败") -> status
            else -> ""
        }
        return listOf(marker, action, target, detail).filter(String::isNotBlank).joinToString("  ")
    }

    private fun operationTimeline(message: ChatMessage): String {
        val details = message.toolDetails.orEmpty()
        if (details.isEmpty()) return ""
        val completed = details.filter { it.status == "completed" }
        val grouped = completed.groupingBy { it.action }.eachCount()
        return buildString {
            append("文件操作")
            if (completed.isNotEmpty()) append(" · ${completed.size} 项")
            append(" · 点选类别查看")
            grouped.forEach { (action, count) -> append("\n✓  $action  $count  ›") }
            details.filter { it.status != "completed" }.forEach { item ->
                val marker = if (item.status == "failed") "×" else "●"
                val name = item.path.substringAfterLast('/').substringAfterLast('\\').ifBlank { item.detail }
                append("\n$marker  ${item.action}  $name")
                if (item.status == "failed" && item.detail.isNotBlank()) append(" · ${item.detail}")
            }
        }
    }

    private fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density).toInt()
}
