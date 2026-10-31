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
    private var conversationResumeAfter = 0L

    fun reset() { handoffStartedAt = null; voiceObserved = false; resumeAfter = 0L; conversationResumeAfter = 0L }

    fun updateVoice(voiceBusy: Boolean, now: Long) {
        val started = handoffStartedAt ?: return
        if (voiceBusy) voiceObserved = true
        if ((voiceObserved && !voiceBusy) || (!voiceObserved && now - started >= HANDOFF_TIMEOUT_MS)) {
            handoffStartedAt = null
            voiceObserved = false
            resumeAfter = now + REARM_DELAY_MS
            conversationResumeAfter = now + CONVERSATION_REARM_MS
        }
    }

    fun canListen(conditions: WakeWordConditions, now: Long): Boolean =
        available(conditions) && now >= resumeAfter

    private fun available(conditions: WakeWordConditions): Boolean =
        conditions.enabled && conditions.assistanceEnabled && conditions.accessibilityConnected &&
            !conditions.voiceBusy && !conditions.ttsSpeaking &&
            handoffStartedAt == null

    fun canListenConversation(conditions: WakeWordConditions, activeConversation: Boolean, now: Long): Boolean =
        activeConversation && available(conditions) && now >= conversationResumeAfter

    fun acceptConversationTurn(conditions: WakeWordConditions, activeConversation: Boolean, now: Long): Boolean {
        if (!canListenConversation(conditions, activeConversation, now)) return false
        beginHandoff(now)
        return true
    }

    fun accept(text: String, conditions: WakeWordConditions, now: Long): Boolean {
        if (!matches(text)) return false
        return acceptVerifiedWake(conditions, now)
    }

    /** Called only with PCM authorized by the constrained wake recognizer, independent of free-ASR spelling. */
    fun acceptVerifiedWake(conditions: WakeWordConditions, now: Long): Boolean {
        if (!canListen(conditions, now)) return false
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
    fun nextCheckDelay(now: Long, conversation: Boolean = false): Long? = handoffStartedAt?.let { started ->
        if (voiceObserved) null else (started + HANDOFF_TIMEOUT_MS - now).coerceAtLeast(1)
    } ?: ((if (conversation) conversationResumeAfter else resumeAfter) - now).takeIf { it > 0 }

    companion object {
        const val HANDOFF_TIMEOUT_MS = 12_000L
        const val REARM_DELAY_MS = 2_000L
        const val CONVERSATION_REARM_MS = 250L

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
