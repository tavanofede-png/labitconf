package org.nieto.assistant.voice

/** Accept only a verified next step of a live task after a real, fresh action on the current screen. */
object StepContinuationPolicy {
    fun accepts(reason: String, taskPresent: Boolean, conversationAlive: Boolean, requestActive: Boolean,
        voiceActive: Boolean, localSpeechActive: Boolean, screenMatches: Boolean,
        activityAt: Long, consumedActivityAt: Long, now: Long): Boolean =
        reason == "step_completed" && taskPresent && conversationAlive && !requestActive &&
            !voiceActive && !localSpeechActive && screenMatches && activityAt > consumedActivityAt &&
            now - activityAt in 0 until ConversationIdlePolicy.IDLE_MS
}
