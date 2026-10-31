package org.nieto.assistant.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.nieto.assistant.BuildConfig

/** Private installation state. Tokens and profiles never leave this app's encrypted preferences. */
class LocalSettings(context: Context) {
    private val prefs = context.getSharedPreferences("tino.private.v1", Context.MODE_PRIVATE)
    private val alias = "tino.preferences.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(name: String): String {
        val value = prefs.getString(name, null) ?: return ""
        val pieces = value.split(':', limit = 2)
        require(pieces.size == 2) { "No se pudo leer la configuración protegida." }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
            updateAAD(name.toByteArray(Charsets.UTF_8))
            String(doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }
    }
    @Synchronized fun write(name: String, value: String) {
        if (value.isEmpty()) { check(prefs.edit().remove(name).commit()); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(name.toByteArray(Charsets.UTF_8)) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(name, Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    var baseUrl: String
        get() = read("baseUrl").ifBlank { if (BuildConfig.DEBUG) "http://10.0.2.2:8787" else "" }
        set(value) {
            val url = URI(value.trim().trimEnd('/').removeSuffix("/api"))
            require(url.scheme == "https" || (BuildConfig.DEBUG && url.scheme == "http")) { "Usá una dirección HTTPS válida (HTTP solo en debug)." }
            require(!url.host.isNullOrBlank() && url.userInfo == null && url.query == null && url.fragment == null && url.path.isNullOrEmpty()) { "Ingresá solo la dirección del servidor, sin rutas, claves ni parámetros." }
            val clean = url.toASCIIString()
            if (baseUrl != clean) {
                listOf("installation", "accountSession", "caregiverToken", "institutionToken", "onboardingComplete").forEach { write(it, "") }
            }
            write("baseUrl", clean)
        }
    var deviceName: String
        get() = read("deviceName").ifBlank { "Mi teléfono TINO" }
        set(value) = write("deviceName", value.trim().take(60).ifBlank { "Mi teléfono TINO" })
    var territoryId: String
        get() = read("territoryId").ifBlank { "AR-C" }
        set(value) = write("territoryId", value)
    val token: String get() = read("installation").takeIf { it.isNotEmpty() }?.let { JSONObject(it).optString("token") }.orEmpty()
    fun installation(deviceId: String, token: String) = write("installation", JSONObject().put("deviceId", deviceId).put("token", token).toString())
    var screenConsent: Boolean
        get() = read("screenConsent") == "true"
        set(value) = write("screenConsent", value.toString())

    var openAiConsent: Boolean
        get() = read("openAiConsent") == "true"
        set(value) = write("openAiConsent", value.toString())
    var remoteScreenConsent: Boolean
        get() = read("remoteScreenConsent") == "true"
        set(value) = write("remoteScreenConsent", value.toString())
    var screenImagesConsent: Boolean
        get() = read("remoteImageConsent") == "true"
        set(value) = write("remoteImageConsent", value.toString())
}
