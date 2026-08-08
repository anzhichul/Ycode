package com.ycode.app.ui.splash

import android.content.Intent
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.ycode.app.MainActivity
import com.ycode.app.YcodeApp
import com.ycode.app.databinding.ActivitySplashBinding
import com.ycode.app.ui.onboarding.OnboardingActivity

class SplashActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySplashBinding
    private val next = Runnable {
        val target = if ((application as YcodeApp).store.onboardingComplete) MainActivity::class.java else OnboardingActivity::class.java
        startActivity(Intent(this, target))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.brandGroup.alpha = 0f
        binding.brandGroup.scaleX = .92f
        binding.brandGroup.scaleY = .92f
        binding.brandGroup.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(520L).setInterpolator(DecelerateInterpolator()).start()
        binding.root.postDelayed(next, DISPLAY_MS)
    }

    override fun onDestroy() {
        binding.root.removeCallbacks(next)
        super.onDestroy()
    }

    companion object { private const val DISPLAY_MS = 1_050L }
}
