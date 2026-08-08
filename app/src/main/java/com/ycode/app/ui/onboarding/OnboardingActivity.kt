package com.ycode.app.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.ycode.app.MainActivity
import com.ycode.app.YcodeApp
import com.ycode.app.databinding.ActivityOnboardingBinding
import com.ycode.app.databinding.ItemOnboardingBinding

class OnboardingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOnboardingBinding
    private val pages = listOf(
        Triple("Y_", "随身携带的 AI 工作伙伴", "连接你自己的模型，在手机上讨论、规划和持续完成工作。"),
        Triple("PLAN", "不只是聊天，更专注于行动", "任务模式可以读取授权目录、调用工具并把执行过程展示在聊天时间线。"),
        Triple("Y_", "Local First", "API 密钥与工作数据默认保存在本机。你始终掌控模型和文件权限。")
    )
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); binding = ActivityOnboardingBinding.inflate(layoutInflater); setContentView(binding.root)
        binding.pager.adapter = object : RecyclerView.Adapter<PageHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, type: Int) = PageHolder(ItemOnboardingBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            override fun getItemCount() = pages.size
            override fun onBindViewHolder(holder: PageHolder, position: Int) = with(holder.binding) { val page = pages[position]; visual.text = page.first; title.text = page.second; description.text = page.third }
        }
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() { override fun onPageSelected(position: Int) { binding.indicator.text = (0..2).joinToString("  ") { if (it == position) "●" else "○" }; binding.next.text = if (position == 2) "进入 Ycode" else "下一步" } })
        binding.next.setOnClickListener { if (binding.pager.currentItem < 2) binding.pager.currentItem++ else { (application as YcodeApp).store.onboardingComplete = true; startActivity(Intent(this, MainActivity::class.java)); finish() } }
    }
    class PageHolder(val binding: ItemOnboardingBinding) : RecyclerView.ViewHolder(binding.root)
}
