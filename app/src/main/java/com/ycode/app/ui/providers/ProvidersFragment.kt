package com.ycode.app.ui.providers

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.ycode.app.R
import com.ycode.app.YcodeApp
import com.ycode.app.data.ProviderCatalog
import com.ycode.app.databinding.FragmentProvidersBinding
import com.ycode.app.databinding.ItemEnabledProviderBinding

class ProvidersFragment : Fragment() {
    private var _binding: FragmentProvidersBinding? = null
    private val binding get() = _binding!!
    private val adapter = ProviderAdapter { startActivity(Intent(requireContext(), ProviderConfigActivity::class.java).putExtra("providerId", it.id)) }

    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View { _binding = FragmentProvidersBinding.inflate(inflater, parent, false); return binding.root }
    override fun onViewCreated(view: View, state: Bundle?) {
        binding.providers.layoutManager = LinearLayoutManager(requireContext())
        binding.providers.adapter = adapter
        binding.providers.isNestedScrollingEnabled = false
        binding.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh(s.toString())
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
    override fun onResume() { super.onResume(); refresh(binding.search.text.toString()) }
    private fun refresh(query: String) {
        val store = (requireActivity().application as YcodeApp).store
        val enabled = store.enabledProviders()
        val enabledIds = enabled.map { it.first.id }.toSet()
        val keyword = query.trim()
        val other = ProviderCatalog.all.filter { it.id !in enabledIds && (keyword.isBlank() || it.name.contains(keyword, true) || it.description.contains(keyword, true)) }
        adapter.enabledIds = enabledIds
        adapter.items = other
        binding.enabledTitle.text = "${enabled.size} 个平台 · ${enabled.sumOf { it.second.selectedModels.size }} 个模型"
        binding.otherCount.text = "${other.size} 个服务商"
        binding.enabledScroll.visibility = if (enabled.isEmpty()) View.GONE else View.VISIBLE
        binding.enabledRow.removeAllViews()
        enabled.forEach { (provider, config) ->
            val card = ItemEnabledProviderBinding.inflate(layoutInflater, binding.enabledRow, false)
            card.avatar.setProvider(provider.id)
            card.name.text = provider.name
            card.models.text = config.selectedModels.joinToString(" · ")
            card.root.setOnClickListener { startActivity(Intent(requireContext(), ProviderConfigActivity::class.java).putExtra("providerId", provider.id)) }
            binding.enabledRow.addView(card.root, ViewGroup.MarginLayoutParams(resources.displayMetrics.density.times(220).toInt(), resources.displayMetrics.density.times(70).toInt()).apply { marginEnd = resources.displayMetrics.density.times(10).toInt() })
        }
    }
    override fun onDestroyView() { _binding = null; super.onDestroyView() }
}
