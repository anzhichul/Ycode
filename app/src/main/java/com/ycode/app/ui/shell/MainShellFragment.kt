package com.ycode.app.ui.shell

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ycode.app.R
import com.ycode.app.databinding.FragmentMainShellBinding
import com.ycode.app.ui.chat.ChatFragment
import com.ycode.app.ui.cloud.CloudFragment
import com.ycode.app.ui.profile.ProfileFragment
import com.ycode.app.ui.providers.ProvidersFragment

class MainShellFragment : Fragment() {
    private var _binding: FragmentMainShellBinding? = null
    private val binding get() = _binding!!
    private var selected = "home"
    private var switching = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentMainShellBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val tabs = mapOf("home" to binding.tabHome, "models" to binding.tabModels, "cloud" to binding.tabCloud, "profile" to binding.tabProfile)
        setupGlass()
        setupKeyboardVisibility()
        tabs.forEach { (key, tab) -> tab.setOnClickListener { select(key, tabs) } }
        selected = state?.getString("selectedTab")?.let { if (it == "memory") "models" else it } ?: "home"
        if (state == null) select("home", tabs, animate = false) else restoreSelectedPage()
        updateTabs(tabs)
    }

    private fun setupKeyboardVisibility() {
        val normalBottomMargin = (72 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            binding.tabGlass.visibility = if (keyboardVisible) View.GONE else View.VISIBLE
            (binding.contentContainer.layoutParams as ViewGroup.MarginLayoutParams).apply {
                val target = if (keyboardVisible) 0 else normalBottomMargin
                if (bottomMargin != target) {
                    bottomMargin = target
                    binding.contentContainer.layoutParams = this
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupGlass() {
        val root = requireActivity().window.decorView.findViewById<ViewGroup>(android.R.id.content)
        runCatching {
            binding.tabGlass.setupWith(root)
                .setFrameClearDrawable(requireActivity().window.decorView.background ?: ColorDrawable(Color.TRANSPARENT))
                .setBlurRadius(18f)
                .setOverlayColor(Color.argb(184, 255, 255, 255))
                .setBlurAutoUpdate(true)
        }.onFailure {
            binding.tabGlass.setBackgroundResource(R.drawable.bg_glass)
        }
    }

    private fun select(key: String, tabs: Map<String, TextView>, animate: Boolean = true) {
        if (switching || (animate && key == selected)) return
        val order = listOf("home", "models", "cloud", "profile")
        val moveRight = order.indexOf(key) > order.indexOf(selected)
        val previous = childFragmentManager.findFragmentByTag(tag(selected))
        val fragment = childFragmentManager.findFragmentByTag(tag(key)) ?: createPage(key)
        val transaction = childFragmentManager.beginTransaction()
        if (animate) {
            switching = true
            if (moveRight) transaction.setCustomAnimations(R.anim.page_enter_from_right, R.anim.page_exit_to_left)
            else transaction.setCustomAnimations(R.anim.page_enter_from_left, R.anim.page_exit_to_right)
            binding.tabGlass.postDelayed({ switching = false }, 280)
        }
        if (previous != null && previous !== fragment) transaction.hide(previous)
        if (fragment.isAdded) transaction.show(fragment)
        else transaction.add(binding.contentContainer.id, fragment, tag(key))
        transaction.setPrimaryNavigationFragment(fragment).commit()
        selected = key
        updateTabs(tabs)
    }

    private fun restoreSelectedPage() {
        val transaction = childFragmentManager.beginTransaction()
        var selectedFragment = childFragmentManager.findFragmentByTag(tag(selected))
        childFragmentManager.fragments.forEach { fragment ->
            if (fragment === selectedFragment) transaction.show(fragment) else transaction.hide(fragment)
        }
        if (selectedFragment == null) {
            selectedFragment = createPage(selected)
            transaction.add(binding.contentContainer.id, selectedFragment, tag(selected))
        }
        transaction.setPrimaryNavigationFragment(selectedFragment).commit()
    }

    private fun createPage(key: String): Fragment = when (key) {
        "models" -> ProvidersFragment()
        "cloud" -> CloudFragment()
        "profile" -> ProfileFragment()
        else -> ChatFragment()
    }

    private fun tag(key: String) = "main-page-$key"

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selectedTab", selected)
        super.onSaveInstanceState(outState)
    }

    private fun updateTabs(tabs: Map<String, TextView>) = tabs.forEach { (key, tab) ->
        tab.setTextColor(requireContext().getColor(if (key == selected) R.color.blue else R.color.muted))
        tab.setBackgroundResource(if (key == selected) R.drawable.bg_tab_active_glass else android.R.color.transparent)
    }

    override fun onDestroyView() { _binding = null; super.onDestroyView() }
}
