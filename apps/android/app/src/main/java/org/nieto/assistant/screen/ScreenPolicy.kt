package org.nieto.assistant.screen

import org.nieto.assistant.flow.Box
import java.text.Normalizer

data class ScreenControl(val id: String, val label: String, val role: String, val bounds: Box)
data class ScreenContext(val id: String, val packageName: String, val width: Int, val height: Int,
    val controls: List<ScreenControl>, val capturedAt: Long, val fingerprint: String,
    val image: String? = null, val blocked: Boolean = false, val notice: String = "")
data class ScreenStep(val screenId: String, val controlId: String?, val instruction: String)

/** Pure policies: no platform actions, network, captured pixels or persisted user content. */
object ScreenPolicy {
    private fun normalized(value: String) = Normalizer.normalize(value.lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{M}".toRegex(), "")
    fun blockedPackage(value: String): Boolean {
        val name = value.lowercase(java.util.Locale.ROOT)
        return name.isBlank() || name == "android" || name.contains("permissioncontroller") || name.contains("systemui") ||
            name.contains("inputmethod") || name.contains("keyboard") || name.contains("authenticator") ||
            name.contains("password") || name.contains("bank") || name.contains("banco") || name.contains("wallet") ||
            listOf("com.android.settings", "com.mercadopago", "com.mercadolibre", "com.uala", "com.naranja", "com.binance", "com.coinbase").any { name.startsWith(it) }
    }
    fun sensitiveLabel(value: String): Boolean = Regex("(^|[^a-z])(contrasena|password|passcode|otp|pin|cvv|cvc|token|clave|verificacion|verification|tarjeta|card number|biometria|biometric|transferir|transferencia|pagar|payment)([^a-z]|$)")
        .containsMatchIn(normalized(value))
    fun safeLabel(value: String): String? {
        val clean = value.replace("[\\p{Cntrl}]".toRegex(), " ").trim().take(120)
        if (clean.isBlank() || sensitiveLabel(clean) || Regex("\\d{4,}|[\\w.+-]+@[\\w.-]+\\.[a-zA-Z]{2,}").containsMatchIn(clean)) return null
        return clean
    }
    fun target(step: ScreenStep?, screen: ScreenContext?, now: Long): Box? {
        if (step == null || screen == null || screen.blocked || step.screenId != screen.id || now - screen.capturedAt !in 0..120_000) return null
        return screen.controls.singleOrNull { it.id == step.controlId }?.bounds?.takeIf {
            it.valid && it.right <= screen.width && it.bottom <= screen.height
        }
    }
}
