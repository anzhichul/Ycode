package com.ycode.app.ui.cloud

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.ycode.app.R
import com.ycode.app.YcodeApp
import com.ycode.app.databinding.FragmentCloudBinding
import com.ycode.app.model.LocalModelUsage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CloudFragment : Fragment(R.layout.fragment_cloud) {
    private var _binding: FragmentCloudBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, state: Bundle?) {
        _binding = FragmentCloudBinding.bind(view)
        binding.refresh.setOnClickListener { render() }
        binding.clearUsage.setOnClickListener {
            (requireActivity().application as YcodeApp).store.clearLocalUsage()
            render()
        }
        render()
    }

    override fun onResume() { super.onResume(); if (_binding != null) render() }

    private fun render() {
        val usage = (requireActivity().application as YcodeApp).store.localUsage()
        val todayStart = System.currentTimeMillis() - System.currentTimeMillis() % 86_400_000L
        val today = usage.filter { it.timestamp >= todayStart }
        binding.todayRequests.text = today.size.toString()
        binding.totalRequests.text = usage.size.toString()
        binding.successRate.text = if (usage.isEmpty()) "--" else "${usage.count { it.success } * 100 / usage.size}%"
        binding.totalChars.text = compact(usage.sumOf { it.inputChars.toLong() + it.outputChars })
        binding.status.text = "统计仅保存在本机 · ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}"
        renderRows(usage.take(100))
    }

    private fun renderRows(rows: List<LocalModelUsage>) {
        binding.usageRows.removeAllViews()
        if (rows.isEmpty()) {
            binding.usageRows.addView(TextView(requireContext()).apply {
                text = "暂无本地使用记录。配置模型密钥并发起对话后会自动记录。"
                textSize = 11f; setTextColor(context.getColor(R.color.muted)); setPadding(0, dp(14), 0, dp(8))
            })
            return
        }
        rows.forEachIndexed { index, item ->
            if (index > 0) binding.usageRows.addView(View(requireContext()).apply { setBackgroundColor(context.getColor(R.color.line)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            binding.usageRows.addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, dp(11), 0, dp(11))
                addView(TextView(context).apply { text = "${item.providerName} · ${item.model}"; textSize = 12f; setTextColor(context.getColor(R.color.text)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
                addView(TextView(context).apply {
                    text = "密钥 #${item.keyFingerprint} · 输入 ${compact(item.inputChars.toLong())} / 输出 ${compact(item.outputChars.toLong())} · ${item.durationMs} ms · ${if (item.success) "成功" else "失败"}"
                    textSize = 9f; setTextColor(context.getColor(if (item.success) R.color.muted else R.color.red)); setPadding(0, dp(4), 0, 0)
                })
            })
        }
    }

    private fun compact(value: Long): String = when {
        value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
        else -> value.toString()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroyView() { _binding = null; super.onDestroyView() }
}
