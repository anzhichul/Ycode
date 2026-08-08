package com.ycode.app.ui.providers

import android.view.LayoutInflater
import android.view.ViewGroup
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.ycode.app.databinding.ItemProviderBinding
import com.ycode.app.model.Provider

class ProviderAdapter(private val open: (Provider) -> Unit) : RecyclerView.Adapter<ProviderAdapter.Holder>() {
    var items: List<Provider> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }
    var enabledIds: Set<String> = emptySet()
    class Holder(val binding: ItemProviderBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(ItemProviderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) = with(holder.binding) {
        val provider = items[position]
        mark.setProvider(provider.id)
        avatarShell.background = null
        activeDot.visibility = if (provider.id in enabledIds) View.VISIBLE else View.GONE
        name.text = provider.name
        description.text = provider.description
        root.setOnClickListener { open(provider) }
    }

    companion object {
        fun providerMark(id: String, name: String): String = mapOf(
            "custom" to "＋", "deepseek" to "DS", "qwen" to "Q", "zhipu" to "GLM", "moonshot" to "K",
            "doubao" to "豆", "hunyuan" to "混", "minimax" to "M", "baidu" to "千", "stepfun" to "S",
            "yi" to "01", "baichuan" to "百", "modelscope" to "魔", "siliconflow" to "SF", "openai" to "◎",
            "anthropic" to "A", "gemini" to "G", "xai" to "X", "mistral" to "MI", "groq" to "GQ",
            "openrouter" to "OR", "together" to "T", "fireworks" to "F", "perplexity" to "P", "cerebras" to "C", "nvidia" to "N"
        )[id] ?: name.take(2)

        fun avatarBackground(id: String): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 15f * android.content.res.Resources.getSystem().displayMetrics.density
            setColor(Color.parseColor(mapOf(
                "custom" to "#334155", "deepseek" to "#4D6BFE", "qwen" to "#6246EA", "zhipu" to "#246BFD", "moonshot" to "#111827",
                "doubao" to "#356DF3", "hunyuan" to "#006EFF", "minimax" to "#FF5B35", "baidu" to "#2932E1", "stepfun" to "#635BFF",
                "yi" to "#111827", "baichuan" to "#1677FF", "modelscope" to "#6B4EFF", "siliconflow" to "#0E9888", "openai" to "#111827",
                "anthropic" to "#D97757", "gemini" to "#4285F4", "xai" to "#111111", "mistral" to "#FF7000", "groq" to "#F55036",
                "openrouter" to "#5B5BF7", "together" to "#111827", "fireworks" to "#F43F5E", "perplexity" to "#20B8AA", "cerebras" to "#FF4F00", "nvidia" to "#76B900"
            )[id] ?: "#146CFF"))
        }
    }
}
