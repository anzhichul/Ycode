package com.ycode.app.ui.profile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ycode.app.BuildConfig
import com.ycode.app.R
import com.ycode.app.YcodeApp
import com.ycode.app.databinding.FragmentProfileBinding
import com.ycode.app.update.GitHubUpdater
import com.ycode.app.ui.remote.RemoteConnectionsActivity

class ProfileFragment : Fragment(R.layout.fragment_profile) {
    private var _binding: FragmentProfileBinding? = null

    override fun onViewCreated(view: View, state: Bundle?) {
        val binding = FragmentProfileBinding.bind(view)
        _binding = binding
        binding.version.text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
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

    override fun onDestroyView() { _binding = null; super.onDestroyView() }
}
