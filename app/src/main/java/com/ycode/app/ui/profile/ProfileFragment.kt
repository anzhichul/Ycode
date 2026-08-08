package com.ycode.app.ui.profile

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ycode.app.BuildConfig
import com.ycode.app.R
import com.ycode.app.YcodeApp
import com.ycode.app.databinding.FragmentProfileBinding
import com.ycode.app.update.GitHubUpdater
import com.ycode.app.ui.remote.RemoteConnectionsActivity
import okhttp3.OkHttpClient
import okhttp3.Request

class ProfileFragment : Fragment(R.layout.fragment_profile) {
    private var _binding: FragmentProfileBinding? = null

    override fun onViewCreated(view: View, state: Bundle?) {
        val binding = FragmentProfileBinding.bind(view)
        _binding = binding
        binding.version.text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        binding.authorAvatar.setOnClickListener { openQqProfile() }
        binding.authorCard.setOnClickListener { openQqProfile() }
        loadQqAvatar()
        binding.rules.setOnClickListener {
            val store = (requireActivity().application as YcodeApp).store
            val input = EditText(requireContext()).apply { setText(store.rules().systemPrompt); hint = "每次对话遵守的系统规则" }
            MaterialAlertDialogBuilder(requireContext()).setTitle("全局聊天规则").setView(input)
                .setNegativeButton("取消", null).setPositiveButton("保存") { _, _ -> store.saveRules(store.rules().apply { enabled = input.text.isNotBlank(); systemPrompt = input.text.toString().trim() }) }.show()
        }
        binding.remoteConnections.setOnClickListener { startActivity(Intent(requireContext(), RemoteConnectionsActivity::class.java)) }
        binding.checkUpdate.setOnClickListener {
            binding.updateStatus.text = "正在检查 GitHub Release…"
            GitHubUpdater.check(requireContext()) { result -> activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                binding.updateStatus.text = result.fold({ it }, { "检查失败：${it.message}" })
            } }
        }
        binding.localData.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle("本地数据说明")
                .setMessage("模型密钥、模型配置、聊天历史、目录授权和本地使用统计均保存在设备本机。Ycode 不要求账号，不连接 Ycode 业务服务器，也不会自动上传上述数据。\n\n模型对话仍会发送到你主动配置的模型 API；GitHub 更新检查只访问配置的 GitHub Release 接口。")
                .setPositiveButton("知道了", null).show()
        }
    }

    private fun loadQqAvatar() {
        val cache = java.io.File(requireContext().cacheDir, "author_qq_avatar.jpg")
        if (cache.exists()) runCatching { BitmapFactory.decodeFile(cache.absolutePath) }.getOrNull()?.let { _binding?.authorAvatar?.setImageBitmap(it); return }
        val app = requireContext().applicationContext
        Thread {
            runCatching {
                val request = Request.Builder().url(QQ_AVATAR_URL).build()
                AVATAR_CLIENT.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "QQ 头像请求失败" }
                    val bytes = response.body?.bytes() ?: error("QQ 头像为空")
                    cache.writeBytes(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }.getOrNull()?.let { bitmap -> activity?.runOnUiThread { _binding?.authorAvatar?.setImageBitmap(bitmap) } }
        }.start()
    }

    private fun openQqProfile() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$QQ&card_type=person&source=qrcode"))
        runCatching { startActivity(intent) }.onFailure { Toast.makeText(requireContext(), "未安装 QQ，QQ 号：$QQ", Toast.LENGTH_LONG).show() }
    }

    override fun onDestroyView() { _binding = null; super.onDestroyView() }

    companion object {
        private const val QQ = "3391649367"
        private const val QQ_AVATAR_URL = "https://q1.qlogo.cn/g?b=qq&nk=$QQ&s=640"
        private val AVATAR_CLIENT = OkHttpClient()
    }
}
