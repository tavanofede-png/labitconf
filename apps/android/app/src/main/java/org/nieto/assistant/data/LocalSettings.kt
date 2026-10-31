package org.nieto.assistant.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.nieto.assistant.BuildConfig

class LocalSettings(context: Context) {
    private val preferences = context.getSharedPreferences("nieto_private", Context.MODE_PRIVATE)
    var baseUrl: String
        get() = preferences.getString("base_url", "http://10.0.2.2:8787")!!
        set(value) {
            val normalized = value.trim().trimEnd('/')
            val uri = URI(normalized)
            require(uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.path in listOf("", "/") && (uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http"))) {
                "Ingresá la dirección del servidor, por ejemplo http://192.168.1.10:8787. HTTPS es obligatorio en producción."
            }
            if (normalized != baseUrl) preferences.edit().putString("base_url", normalized).remove("token")
                .remove("device_id").apply()
        }
    var deviceName: String
        get() = preferences.getString("device_name", "Mi teléfono")!!
        set(value) { preferences.edit().putString("device_name", value.take(60)).apply() }
    var deviceId: String
        get() = preferences.getString("device_id", "")!!
        set(value) { preferences.edit().putString("device_id", value).apply() }
    var screenConsent: Boolean
        get() = preferences.getBoolean("screen_consent_v1", false)
        set(value) { preferences.edit().putBoolean("screen_consent_v1", value).apply() }
    var openAiConsent: Boolean
        get() = preferences.getBoolean("openai_voice_and_text_consent_v1", false)
        set(value) { preferences.edit().putBoolean("openai_voice_and_text_consent_v1", value).apply() }
    var token: String
        get() = runCatching {
            val encrypted = preferences.getString("token", null) ?: return@runCatching ""
            val parts = encrypted.split(':')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
        set(value) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            preferences.edit().putString("token", encrypted).apply()
        }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("nieto_installation_token", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("nieto_installation_token", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
