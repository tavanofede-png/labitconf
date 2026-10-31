package org.nieto.assistant.voice

/** A bounded intention, not a conversation log. It stays in process memory only. */
data class TaskContext(val goal: String, val targetApp: String, val progress: String) {
    companion object {
        fun bounded(goal: String, targetApp: String, progress: String): TaskContext? {
            fun clean(value: String, maximum: Int) = value.replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ").trim().take(maximum)
            val boundedGoal = clean(goal, 600)
            return boundedGoal.takeIf { it.isNotBlank() }?.let { TaskContext(it, clean(targetApp, 80), clean(progress, 400)) }
        }
    }
}

/** Transport expiry is deliberately absent from this memory's clearing conditions. */
class TaskContextMemory {
    private var owner: Pair<String, String>? = null
    var context: TaskContext? = null; private set
    fun bind(identity: Pair<String, String>): Boolean {
        val changed = owner != null && owner != identity
        if (changed) context = null
        owner = identity
        return changed
    }
    fun remember(value: TaskContext?) {
        context = value?.let { TaskContext.bounded(it.goal, it.targetApp, it.progress) }
    }
    fun clear() { context = null; owner = null }
}

/** Transport clocks never count logical messages or discard a person's task. */
class SessionContinuityPolicy {
    private var openedAt: Long? = null
    private var maximumMs = DEFAULT_MAX_MS
    private var renewAfterMs = DEFAULT_RENEW_MS
    private var renewalRequested = false
    var renewable = false; private set

    fun ready(now: Long, maxSessionMs: Long, renewAfter: Long, canRenew: Boolean) {
        openedAt = now
        maximumMs = maxSessionMs.coerceIn(30_000L, 3_600_000L)
        renewAfterMs = renewAfter.coerceIn(1_000L, maximumMs)
        renewable = canRenew
        renewalRequested = false
    }

    fun requireRenewal() { renewalRequested = true }
    fun shouldRenewBeforeTurn(now: Long): Boolean {
        val opened = openedAt ?: return false
        val elapsed = (now - opened).coerceAtLeast(0)
        return renewable && (renewalRequested || elapsed >= renewAfterMs || maximumMs - elapsed <= TURN_BUDGET_MS)
    }

    fun transportClosed() { openedAt = null; renewalRequested = false }

    companion object {
        const val DEFAULT_MAX_MS = 270_000L
        const val DEFAULT_RENEW_MS = 225_000L
        // 45 s input + 60 s generation + 90 s queued playback + 15 s network margin.
        const val TURN_BUDGET_MS = 210_000L
        const val LEASE_RELEASE_MS = 500L
        const val IDLE_CLOSE_MS = 30_000L
        const val MAX_RETRIES = 3

        fun retryDelay(attempt: Int, jitterMs: Long): Long? = when (attempt) {
            1 -> 500L
            2 -> 1_000L
            3 -> 2_000L
            else -> null
        }?.plus(jitterMs.coerceIn(0, 200))

        fun shouldSpeakError(automaticReply: Boolean, hasSpeech: Boolean): Boolean = !automaticReply || hasSpeech
    }
}
