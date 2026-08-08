package com.ycode.app.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class RemoteProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("ycode.remote.profiles", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun all(): MutableList<ConnectionProfile> {
        val encrypted = prefs.getString("profiles", null) ?: return mutableListOf()
        return runCatching {
            val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
            val ivSize = bytes.first().toInt() and 0xff
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 1, ivSize))
            val json = String(cipher.doFinal(bytes.copyOfRange(1 + ivSize, bytes.size)), Charsets.UTF_8)
            gson.fromJson<List<ConnectionProfile>>(json, object : TypeToken<List<ConnectionProfile>>() {}.type).toMutableList()
        }.getOrElse { mutableListOf() }
    }

    fun save(profiles: List<ConnectionProfile>) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val packed = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(Gson().toJson(profiles).toByteArray(Charsets.UTF_8))
        prefs.edit().putString("profiles", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    companion object { private const val KEY_ALIAS = "ycode_builtin_remote_profiles_v1" }
}
