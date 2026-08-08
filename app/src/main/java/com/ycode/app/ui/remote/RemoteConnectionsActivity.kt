package com.ycode.app.ui.remote

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ycode.app.remote.ConnectionProfile
import com.ycode.app.remote.RemoteProfileStore
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey
import java.util.UUID
import kotlin.concurrent.thread

class RemoteConnectionsActivity : AppCompatActivity() {
    private lateinit var store: RemoteProfileStore
    private lateinit var list: LinearLayout

    override fun onCreate(state: Bundle?) {
        super.onCreate(state); store = RemoteProfileStore(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)); setBackgroundColor(0xfff5f7fb.toInt())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)); background = rounded(0xff09111f.toInt(), 26)
                addView(TextView(context).apply { text = "YCODE REMOTE"; textSize = 10f; letterSpacing = .14f; setTextColor(0xffa9c5ec.toInt()); setTypeface(typeface, Typeface.BOLD) })
                addView(TextView(context).apply { text = "远程连接"; textSize = 25f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(10), 0, 0) })
                addView(TextView(context).apply { text = "内置 SSH、SFTP 与 FTP 工具"; textSize = 12f; setTextColor(0xffc6d6ec.toInt()); setPadding(0, dp(5), 0, 0) })
            })
            addView(TextView(context).apply { text = "连接资料由 Ycode 使用 Android Keystore 加密。FTP 不加密，建议优先使用 SFTP。"; textSize = 11f; setTextColor(0xff65748a.toInt()); setPadding(dp(2), dp(15), dp(2), dp(12)) })
            addView(TextView(context).apply { text = "+  添加远程连接"; gravity = android.view.Gravity.CENTER; textSize = 13f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); background = rounded(0xff146cff.toInt(), 22); setOnClickListener { edit(null) } }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(ScrollView(context).apply { isFillViewport = true; addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        }
        setContentView(root); render()
    }

    private fun render() {
        list.removeAllViews()
        if (store.all().isEmpty()) list.addView(TextView(this).apply { text = "还没有保存的连接\n添加后，AI 可按你授予的权限直接使用。"; gravity = android.view.Gravity.CENTER; setTextColor(0xff7d899b.toInt()); setPadding(0, dp(40), 0, dp(20)) })
        store.all().forEach { profile -> list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(12)); background = rounded(Color.WHITE, 22, 0xffdce3ed.toInt())
            addView(TextView(context).apply { text = "${profile.name}  ·  ${profile.protocol.uppercase()}"; textSize = 16f; setTextColor(0xff17253b.toInt()); setTypeface(typeface, Typeface.BOLD) })
            addView(TextView(context).apply { text = "ID: ${profile.id}\n${profile.username}@${profile.host}:${profile.port}\n命令 ${yes(profile.allowCommands)}  写入 ${yes(profile.allowWrite)}  删除 ${yes(profile.allowDelete)}"; textSize = 11f; setTextColor(0xff64748b.toInt()) })
            addView(LinearLayout(context).apply { setPadding(0, dp(8), 0, 0); addView(TextView(context).apply { text = "编辑"; gravity = android.view.Gravity.CENTER; setTextColor(0xff146cff.toInt()); background = rounded(0xffe8f1ff.toInt(), 18); setOnClickListener { edit(profile) } }, LinearLayout.LayoutParams(0, dp(38), 1f)); addView(TextView(context).apply { text = "删除"; gravity = android.view.Gravity.CENTER; setTextColor(0xffc34555.toInt()); setOnClickListener { confirmDelete(profile) } }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(8) }) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }) }
    }

    private fun edit(existing: ConnectionProfile?) {
        val protocols = arrayOf("SFTP", "SSH", "FTP")
        val protocol = Spinner(this).apply { adapter = ArrayAdapter(this@RemoteConnectionsActivity, android.R.layout.simple_spinner_dropdown_item, protocols); setSelection(protocols.indexOf(existing?.protocol?.uppercase()).coerceAtLeast(0)) }
        val name = field("连接名称", existing?.name.orEmpty()); val host = field("服务器域名或 IP", existing?.host.orEmpty()); val port = field("端口", existing?.port?.toString().orEmpty()).apply { inputType = InputType.TYPE_CLASS_NUMBER }; val username = field("用户名", existing?.username.orEmpty()); val password = field(if (existing == null) "密码" else "密码（留空保持不变）", "").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }; val fingerprint = field("SSH 主机密钥 SHA-256 指纹", existing?.hostKeySha256.orEmpty())
        val fetch = Button(this).apply { text = "获取 SSH 指纹"; setOnClickListener { val target = host.text.toString().trim(); if (target.isBlank()) return@setOnClickListener toast("请先填写服务器地址"); isEnabled = false; thread { val result = runCatching { fetchFingerprint(target, port.text.toString().toIntOrNull() ?: 22) }; runOnUiThread { isEnabled = true; result.onSuccess { fingerprint.setText(it); MaterialAlertDialogBuilder(this@RemoteConnectionsActivity).setTitle("核对服务器指纹").setMessage(it).setPositiveButton("知道了", null).show() }.onFailure { toast(it.message ?: "无法获取指纹") } } } } }
        val commands = CheckBox(this).apply { text = "允许 AI 执行 SSH 命令"; isChecked = existing?.allowCommands == true }; val write = CheckBox(this).apply { text = "允许 AI 写入和重命名远程文件"; isChecked = existing?.allowWrite == true }; val delete = CheckBox(this).apply { text = "允许 AI 递归删除远程路径"; isChecked = existing?.allowDelete == true }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0); listOf(name, protocol, host, port, username, password, fingerprint, fetch, commands, write, delete).forEach(::addView) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(if (existing == null) "添加远程连接" else "编辑远程连接").setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener { dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { val selected = protocols[protocol.selectedItemPosition].lowercase(); val resolvedPort = port.text.toString().toIntOrNull() ?: if (selected == "ftp") 21 else 22; val secret = password.text.toString().ifBlank { existing?.password.orEmpty() }; val error = when { name.text.isBlank() || host.text.isBlank() || username.text.isBlank() -> "名称、服务器和用户名必须填写"; resolvedPort !in 1..65535 -> "端口无效"; secret.isBlank() -> "密码必须填写"; selected != "ftp" && fingerprint.text.isBlank() -> "SSH/SFTP 必须填写主机指纹"; else -> null }; if (error != null) return@setOnClickListener toast(error); val profiles = store.all(); val id = existing?.id ?: uniqueId(slug(name.text.toString()), profiles); val value = ConnectionProfile(id, name.text.toString().trim(), selected, host.text.toString().trim(), resolvedPort, username.text.toString().trim(), secret, fingerprint.text.toString().trim(), commands.isChecked, write.isChecked, delete.isChecked); existing?.let { old -> profiles.indexOfFirst { it.id == old.id }.takeIf { it >= 0 }?.let { profiles[it] = value } } ?: profiles.add(value); store.save(profiles); dialog.dismiss(); render() } }; dialog.show()
    }

    private fun confirmDelete(profile: ConnectionProfile) = MaterialAlertDialogBuilder(this).setTitle("删除 ${profile.name}？").setMessage("只删除本机加密连接资料，不会删除服务器数据。").setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> store.save(store.all().filterNot { it.id == profile.id }); render() }.show()
    private fun fetchFingerprint(host: String, port: Int): String { var result: String? = null; val ssh = SSHClient().apply { connectTimeout = 15_000; addHostKeyVerifier(object : HostKeyVerifier { override fun verify(hostname: String, port: Int, key: PublicKey): Boolean { result = "SHA256:" + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key.encoded), Base64.NO_WRAP or Base64.NO_PADDING); return true }; override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList() }) }; try { ssh.connect(host, port) } finally { runCatching { ssh.disconnect() }; runCatching { ssh.close() } }; return result ?: error("服务器未返回主机密钥") }
    private fun field(hint: String, value: String) = EditText(this).apply { this.hint = hint; setText(value); setPadding(dp(4), dp(10), dp(4), dp(10)) }
    private fun slug(value: String) = value.lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-').take(24).ifBlank { UUID.randomUUID().toString().take(8) }
    private fun uniqueId(base: String, profiles: List<ConnectionProfile>): String { var value = base; var suffix = 2; while (profiles.any { it.id == value }) value = "${base.take(20)}-${suffix++}"; return value }
    private fun yes(value: Boolean) = if (value) "开" else "关"
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); stroke?.let { setStroke(dp(1), it) } }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
