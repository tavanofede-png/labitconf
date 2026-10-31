package org.nieto.assistant.voice

import org.junit.Assert.*
import org.junit.Test

class StepContinuationPolicyTest {
    private fun allowed(reason: String = "step_completed", task: Boolean = true, alive: Boolean = true,
        request: Boolean = false, voice: Boolean = false, speech: Boolean = false, screen: Boolean = true,
        action: Long = 1_000, consumed: Long = 0, now: Long = 3_000) = StepContinuationPolicy.accepts(
        reason, task, alive, request, voice, speech, screen, action, consumed, now)

    @Test fun `verified step can continue once after a fresh real action in a live task`() {
        assertTrue(allowed())
        assertFalse(allowed(consumed = 1_000))
        assertFalse(allowed(action = 0))
        assertFalse(allowed(now = 31_000))
        assertFalse(allowed(now = 999))
    }

    @Test fun `passive output stale screens and active speech never authorize a next step`() {
        assertFalse(allowed(reason = ""))
        assertFalse(allowed(task = false))
        assertFalse(allowed(alive = false))
        assertFalse(allowed(request = true))
        assertFalse(allowed(voice = true))
        assertFalse(allowed(speech = true))
        assertFalse(allowed(screen = false))
    }
}
