package org.nieto.assistant.family

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.nieto.assistant.BuildConfig
import org.nieto.assistant.data.LocalSettings
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class FamilyAccess(val serverUrl: String, val token: String, val session: String)

/** Independent preferences and Keystore alias: caregiver credentials never replace device access. */
class FamilyStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("nieto_family_private", Context.MODE_PRIVATE)
    val serverUrl: String get() = preferences.getString("server_url", null) ?: LocalSettings(appContext).baseUrl
    val notificationsEnabled: Boolean get() = preferences.getBoolean("notifications_enabled", false)
    val lastCheckedAt: Long get() = preferences.getLong("last_checked", 0)
    fun access(): FamilyAccess? = synchronized(lock) {
        runCatching {
            val encrypted = preferences.getString("credential", null) ?: return@runCatching null
            val parts = encrypted.split(':'); require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            val token = String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
            FamilyAccess(serverUrl, token, preferences.getString("session", "")!!).takeIf { it.session.isNotBlank() }
        }.getOrNull()
    }
    fun save(server: String, token: String): FamilyAccess = synchronized(lock) {
        val normalized = normalizeServer(server)
        require(token.matches(Regex("[A-Za-z0-9_.-]{20,512}"))) { "Revisá la credencial familiar." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val session = UUID.randomUUID().toString()
        check(preferences.edit().clear().putString("server_url", normalized).putString("credential", encrypted)
            .putString("session", session).commit()) { "No pude guardar el acceso cifrado." }
        FamilyAccess(normalized, token, session)
    }
    fun clear() = synchronized(lock) {
        val server = serverUrl
        preferences.edit().clear().putString("server_url", server).commit()
    }
    fun setNotifications(enabled: Boolean) { synchronized(lock) { preferences.edit().putBoolean("notifications_enabled", enabled).commit() } }
    fun <T> ifCurrent(access: FamilyAccess, block: () -> T): T? = synchronized(lock) {
        if (preferences.getString("session", "") != access.session || serverUrl != access.serverUrl) null else block()
    }
    fun checkpoint(): FamilyCheckpoint = FamilyCheckpoint(preferences.getBoolean("baseline", false),
        preferences.getLong("cursor_time", 0), preferences.getStringSet("cursor_ids", emptySet())!!.toSet())
    fun postedIds(): Set<String> = preferences.getStringSet("posted_ids", emptySet())!!.toSet()
    fun record(checkpoint: FamilyCheckpoint, posted: Set<String>): Boolean =
        preferences.edit().putBoolean("baseline", checkpoint.initialized).putLong("cursor_time", checkpoint.time)
            .putStringSet("cursor_ids", checkpoint.idsAtTime).putStringSet("posted_ids", posted)
            .putLong("last_checked", System.currentTimeMillis()).commit()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    companion object {
        private const val KEY_ALIAS = "nieto_family_caregiver_token"
        private val lock = Any()
        fun normalizeServer(value: String): String {
            val result = value.trim().trimEnd('/'); val uri = URI(result)
            require(uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.path in listOf("", "/") && (uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http"))) {
                "Ingresá la dirección del servidor sin /api. HTTPS es obligatorio en producción."
            }
            return result
        }
    }
}
