package com.ycode.app.ui.providers

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.ycode.app.YcodeApp
import com.ycode.app.data.ProviderCatalog
import com.ycode.app.databinding.ActivityProviderConfigBinding
import com.ycode.app.model.ProviderConfig
import com.ycode.app.network.ModelApiClient

class ProviderConfigActivity : AppCompatActivity() {
    private lateinit var binding: ActivityProviderConfigBinding
    private val available = mutableListOf<String>()
    private val selected = linkedSetOf<String>()
    private lateinit var adapter: ModelSelectionAdapter
    private var keyVisible = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        binding = ActivityProviderConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val provider = ProviderCatalog.all.firstOrNull { it.id == intent.getStringExtra("providerId") } ?: run { finish(); return }
        val store = (application as YcodeApp).store
        val config = store.providerConfig(provider)
        available += (config.availableModels + config.selectedModels).distinct()
        selected += config.selectedModels

        binding.title.text = provider.name
        binding.providerIcon.setProvider(provider.id)
        binding.baseUrl.setText(config.baseUrl)
        binding.apiKey.setText(config.apiKey)
        binding.enabled.isChecked = config.enabled
        binding.back.setOnClickListener { finish() }

        adapter = ModelSelectionAdapter(selected) { updateSelectedCount() }
        binding.modelList.layoutManager = LinearLayoutManager(this)
        binding.modelList.adapter = adapter
        binding.modelList.isNestedScrollingEnabled = false
        refreshModels()

        binding.toggleKey.setOnClickListener {
            keyVisible = !keyVisible
            binding.apiKey.inputType = InputType.TYPE_CLASS_TEXT or if (keyVisible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            binding.apiKey.setSelection(binding.apiKey.text.length)
            binding.toggleKey.text = if (keyVisible) "隐藏" else "显示"
        }
        binding.searchModels.addTextChangedListener(watcher { refreshModels() })
        binding.addModel.setOnClickListener { addManualModel() }
        binding.manualModel.setOnEditorActionListener { _, _, _ -> addManualModel(); true }

        binding.fetch.setOnClickListener {
            val url = binding.baseUrl.text.toString().trim()
            val key = binding.apiKey.text.toString().trim()
            if (url.isBlank() || key.isBlank()) return@setOnClickListener toast("请先填写 API 地址和密钥")
            binding.fetch.isEnabled = false
            binding.fetch.text = "正在获取模型..."
            ModelApiClient.fetchModels(url, key) { result -> runOnUiThread {
                binding.fetch.isEnabled = true
                binding.fetch.text = "获取该平台全部模型"
                result.onSuccess { models ->
                    available.clear(); available.addAll(models)
                    selected.retainAll(models.toSet())
                    refreshModels(); updateSelectedCount(); toast("获取到 ${models.size} 个模型，可点击勾选")
                }.onFailure { toast("${it.message ?: "获取失败"}，仍可手动添加模型") }
            } }
        }

        binding.save.setOnClickListener {
            val url = binding.baseUrl.text.toString().trim().trimEnd('/')
            val key = binding.apiKey.text.toString().trim()
            if (url.isBlank() || key.isBlank()) return@setOnClickListener toast("API 地址和密钥不能为空")
            if (binding.enabled.isChecked && selected.isEmpty()) return@setOnClickListener toast("启用平台前至少勾选一个模型")
            store.saveProvider(provider.id, ProviderConfig(url, key, available.distinct().toMutableList(), selected.toMutableList(), binding.enabled.isChecked))
            if (binding.enabled.isChecked && selected.isNotEmpty()) store.selectModel(provider.id, selected.first())
            toast("平台和 ${selected.size} 个模型已保存")
            finish()
        }
    }

    private fun addManualModel() {
        val model = binding.manualModel.text.toString().trim()
        if (model.isBlank()) return
        if (model !in available) available.add(0, model)
        selected.add(model)
        binding.manualModel.text.clear()
        binding.searchModels.text.clear()
        refreshModels(); updateSelectedCount()
    }

    private fun refreshModels() {
        val keyword = binding.searchModels.text?.toString()?.trim().orEmpty()
        adapter.items = available.filter { keyword.isBlank() || it.contains(keyword, true) }
        updateSelectedCount()
    }

    private fun updateSelectedCount() {
        binding.selectedCount.text = "${selected.size} 已选"
    }

    private fun watcher(changed: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = changed()
        override fun afterTextChanged(value: Editable?) = Unit
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
