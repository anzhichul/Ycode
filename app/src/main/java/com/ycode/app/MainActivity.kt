package com.ycode.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.ycode.app.databinding.ActivityMainBinding
import com.ycode.app.ui.onboarding.OnboardingActivity
import com.ycode.app.ui.shell.MainShellFragment

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val store = (application as YcodeApp).store
        if (!store.onboardingComplete) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        if (savedInstanceState == null) supportFragmentManager.beginTransaction()
            .replace(binding.rootContainer.id, MainShellFragment())
            .commit()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (intent.getBooleanExtra("from_floating", false)) {
            intent.removeExtra("from_floating")
            val target = intent.getStringExtra("target_package") ?: ""
            if (target.isNotBlank()) { /* from floating - user returned to chat */ }
        }
    }
}
