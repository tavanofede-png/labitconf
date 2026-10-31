package org.nieto.assistant.voice

enum class WakeControlAction { END_CONVERSATION, DISMISS_NOTIFICATION, FULL_OFF }
data class WakeControlDecision(val clearConversation: Boolean, val disableWake: Boolean)
object WakeControlPolicy {
    fun decide(action: WakeControlAction) = when (action) {
        WakeControlAction.END_CONVERSATION -> WakeControlDecision(true, false)
        WakeControlAction.DISMISS_NOTIFICATION -> WakeControlDecision(false, false)
        WakeControlAction.FULL_OFF -> WakeControlDecision(true, true)
    }
}

/** Explicit turns, replies and real actions extend this clock. Passive observation does not. */
class ConversationIdlePolicy {
    @Volatile private var active = false
    @Volatile private var deadline: Long? = null
    fun expired(now: Long): Boolean = !active && deadline?.let { now >= it } == true
    val isRequestActive: Boolean get() = active
    fun hasConversation(now: Long): Boolean = active || (deadline != null && !expired(now))
    fun beginRequest(now: Long): Boolean {
        if (active) return false
        val expired = expired(now)
        active = true; deadline = null
        return expired
    }
    fun turnFinished(now: Long) { active = false; deadline = now + IDLE_MS }
    fun remaining(now: Long): Long? = if (active) null else deadline?.let { (it - now).coerceAtLeast(0) }
    fun screenAction(now: Long): Boolean {
        if (!hasConversation(now)) return false
        if (!active) deadline = now + IDLE_MS
        return true
    }
    fun clear() { active = false; deadline = null }
    companion object { const val IDLE_MS = 30_000L }
}

/** AudioRecord can briefly be unavailable after another recorder releases it. */
class WakeCaptureRetryPolicy {
    private var failures = 0
    private var retryAt = 0L
    fun failed(now: Long) {
        val delays = longArrayOf(500, 1_000, 3_000, 10_000, 30_000)
        retryAt = now + delays[failures.coerceAtMost(delays.lastIndex)]
        failures = (failures + 1).coerceAtMost(delays.size)
    }
    fun remaining(now: Long): Long? = (retryAt - now).takeIf { it > 0 }
    fun reset() { failures = 0; retryAt = 0 }
}

object ObservationResumePolicy {
    fun allowed(hasTask: Boolean, conversationAlive: Boolean, requestActive: Boolean, triggered: Boolean): Boolean =
        hasTask && conversationAlive && !requestActive && triggered
}
