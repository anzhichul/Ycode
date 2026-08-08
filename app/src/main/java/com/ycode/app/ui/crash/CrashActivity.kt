package com.ycode.app.ui.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.ycode.app.MainActivity
import com.ycode.app.databinding.ActivityCrashBinding

class CrashActivity : AppCompatActivity() {
    companion object { const val EXTRA_REPORT = "crash_report" }
    private lateinit var binding: ActivityCrashBinding
    private lateinit var report: String

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        binding = ActivityCrashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        report = intent.getStringExtra(EXTRA_REPORT) ?: CrashHandler.readLastReport(this)
        binding.errorDetails.text = report
        binding.copyError.setOnClickListener {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Ycode crash report", report))
            Snackbar.make(binding.root, "错误信息已复制", Snackbar.LENGTH_SHORT).show()
        }
        binding.restart.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        }
        binding.close.setOnClickListener { finishAndRemoveTask() }
    }
}
