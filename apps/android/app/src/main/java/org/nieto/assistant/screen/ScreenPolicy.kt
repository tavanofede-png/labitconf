package org.nieto.assistant.screen

import org.nieto.assistant.flow.Box
import java.text.Normalizer

data class ScreenControl(val id: String, val label: String, val role: String, val bounds: Box, val redacted: Boolean = false)
data class InstalledGuideApp(val packageName: String, val label: String)
data class ScreenNavigation(val home: String = "unknown", val back: String = "unknown",
    val installedApps: List<InstalledGuideApp> = emptyList(), val isLauncher: Boolean = false)
data class ScreenContext(val id: String, val packageName: String, val width: Int, val height: Int,
    val controls: List<ScreenControl>, val capturedAt: Long, val fingerprint: String,
    val image: String? = null, val blocked: Boolean = false, val notice: String = "",
    val navigation: ScreenNavigation = ScreenNavigation())
data class ScreenStep(val screenId: String, val controlId: String?, val instruction: String)

object ScreenNavigationPolicy {
    /** AOSP modes: 0 = three buttons, 1 = two buttons, 2 = gestures. OEM/conflicting values stay unknown. */
    fun method(resourceMode: Int?, secureMode: Int?): String {
        if (listOfNotNull(resourceMode, secureMode).any { it !in 0..2 }) return "unknown"
        if (resourceMode != null && secureMode != null && resourceMode != secureMode) return "unknown"
        return when (resourceMode ?: secureMode) { 0, 1 -> "button"; 2 -> "gesture"; else -> "unknown" }
    }

    fun installedApp(packageName: String, label: CharSequence?): InstalledGuideApp? {
        if (!Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*").matches(packageName) || packageName.length > 120) return null
        val safe = ScreenPolicy.safeLabel(label?.toString().orEmpty())?.take(80) ?: return null
        return InstalledGuideApp(packageName, safe)
    }

    /** Only a label observed inside the confirmed launcher can describe an app icon. */
    fun launcherAppLabel(raw: CharSequence?, navigation: ScreenNavigation): String? {
        if (!navigation.isLauncher) return null
        return ScreenPolicy.safeLabel(raw?.toString().orEmpty())
    }
}

/** Pure policies: no platform actions, network, captured pixels or persisted user content. */
object ScreenPolicy {
    private fun normalized(value: String) = Normalizer.normalize(Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{M}".toRegex(), "")
    fun blockedPackage(value: String): Boolean {
        return value.isBlank()
    }
    fun sensitiveLabel(value: String): Boolean {
        val text = normalized(value)
        return Regex("\\b(?:password|contrasena|passcode|otp|pin|cvv|cvc|token|clave(?: fiscal)?)\\s*[\"']?\\s*[:=]\\s*\\S+").containsMatchIn(text) ||
            Regex("\\b(?:codigo(?: de verificacion)?|pin|otp|cvv|cvc)\\s+\\d{3,}\\b").containsMatchIn(text) ||
            Regex("\\b(?:sk-[a-z0-9_-]{8,}|bearer\\s+\\S+|api[_ -]?key\\s*[:=]\\s*\\S+)").containsMatchIn(text) ||
            Regex("\\d{6,}|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}").containsMatchIn(text) ||
            Regex("\\b(?:tarjeta|card|cbu|cvu|cuit|cuil|dni|telefono|phone)\\s*[:#=]?\\s*[+\\d][\\d .-]{3,}").containsMatchIn(text)
    }
    fun privateNodeResource(value: String): Boolean {
        val id = value.substringAfterLast('/').lowercase(java.util.Locale.ROOT)
        return Regex("^(password|passcode|otp|pin|cvv|cvc|token|secret)(?:$|_(?:input|field|value|entry|text|edit|digits|code)(?:_|$))").containsMatchIn(id) ||
            Regex("(?:contact|conversation|recipient|account|profile|person|customer|client)_(?:name|title|row|item|list|selector)(?:_|$)").containsMatchIn(id) ||
            Regex("^(?:suggestion|candidate|clipboard|autofill|completion|prediction)(?:_|$)").containsMatchIn(id) ||
            Regex("^(?:dni|cuit|cuil|cbu|cvu|iban|card|email|phone|address)_(?:value|number|text|entry|input)(?:_|$)").containsMatchIn(id)
    }
    fun safeLabel(value: String): String? {
        val clean = value.replace("[\\p{Cntrl}]".toRegex(), " ").trim()
        if (clean.isBlank() || clean.length > 120 || sensitiveLabel(clean)) return null
        return clean
    }
    fun target(step: ScreenStep?, screen: ScreenContext?, now: Long): Box? {
        if (step == null || screen == null || screen.blocked || step.screenId != screen.id || now - screen.capturedAt !in 0..120_000) return null
        return screen.controls.singleOrNull { it.id == step.controlId }?.bounds?.takeIf {
            it.valid && it.right <= screen.width && it.bottom <= screen.height
        }
    }
}
