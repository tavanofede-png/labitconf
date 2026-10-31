package org.nieto.assistant.voice

import java.text.Normalizer
import java.util.Locale

data class WakeWordConditions(
    val enabled: Boolean,
    val assistanceEnabled: Boolean,
    val accessibilityConnected: Boolean,
    val voiceBusy: Boolean,
    val ttsSpeaking: Boolean,
    val nativeSurfaceVisible: Boolean,
)

/** Local recognition only authorizes a handoff; it never executes a phone action. */
class WakeWordPolicy {
    private var handoffStartedAt: Long? = null
    private var voiceObserved = false
    private var resumeAfter = 0L

    fun updateVoice(voiceBusy: Boolean, now: Long) {
        val started = handoffStartedAt ?: return
        if (voiceBusy) voiceObserved = true
        if ((voiceObserved && !voiceBusy) || (!voiceObserved && now - started >= HANDOFF_TIMEOUT_MS)) {
            handoffStartedAt = null
            voiceObserved = false
            resumeAfter = now + REARM_DELAY_MS
        }
    }

    fun canListen(conditions: WakeWordConditions, now: Long): Boolean =
        conditions.enabled && conditions.assistanceEnabled && conditions.accessibilityConnected &&
            !conditions.voiceBusy && !conditions.ttsSpeaking && !conditions.nativeSurfaceVisible &&
            handoffStartedAt == null && now >= resumeAfter

    fun accept(text: String, conditions: WakeWordConditions, now: Long): Boolean {
        if (!canListen(conditions, now) || !matches(text)) return false
        beginHandoff(now)
        return true
    }

    /** A system assistant gesture is explicit, so it need not wait out the acoustic cooldown. */
    fun requestHandoff(conditions: WakeWordConditions, now: Long): Boolean {
        if (!conditions.enabled || !conditions.assistanceEnabled || conditions.voiceBusy ||
            conditions.ttsSpeaking || handoffStartedAt != null) return false
        beginHandoff(now)
        return true
    }

    private fun beginHandoff(now: Long) {
        handoffStartedAt = now
        voiceObserved = false
    }

    /** A timer is needed even when no AppState emission follows a failed preparation. */
    fun nextCheckDelay(now: Long): Long? = handoffStartedAt?.let { started ->
        if (voiceObserved) null else (started + HANDOFF_TIMEOUT_MS - now).coerceAtLeast(1)
    } ?: (resumeAfter - now).takeIf { it > 0 }

    companion object {
        const val HANDOFF_TIMEOUT_MS = 12_000L
        const val REARM_DELAY_MS = 2_000L

        fun matches(text: String): Boolean {
            val words = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z ]"), " ")
                .trim().split(Regex("\\s+"))
            return words.zipWithNext().any { (first, second) -> first == "hola" && second == "tino" }
        }
    }
}
