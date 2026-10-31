package org.nieto.assistant.voice

import org.junit.Assert.*
import org.junit.Test

class ConversationControlPolicyTest {
    @Test fun `only real activity or technical rotation can resume observation of a live task`() {
        assertTrue(ObservationResumePolicy.allowed(true, true, false, true))
        assertFalse(ObservationResumePolicy.allowed(false, true, false, true))
        assertFalse(ObservationResumePolicy.allowed(true, false, false, true))
        assertFalse(ObservationResumePolicy.allowed(true, true, true, true))
        assertFalse(ObservationResumePolicy.allowed(true, true, false, false))
    }
    @Test fun `one invocation reserves preparation once while an expired intention is discarded`() {
        val policy = ConversationIdlePolicy()
        policy.turnFinished(0)
        assertFalse(policy.isRequestActive)
        assertFalse(policy.beginRequest(29_000))
        assertFalse(policy.beginRequest(35_000))
        assertTrue(policy.isRequestActive)
        assertFalse(policy.expired(35_000))
        val expired = ConversationIdlePolicy()
        expired.turnFinished(0)
        assertTrue(expired.expired(30_000))
        assertTrue(expired.beginRequest(30_000))
    }
    @Test fun `real actions extend a live task but never revive an expired one`() {
        val policy = ConversationIdlePolicy()
        assertFalse(policy.screenAction(0))
        policy.turnFinished(0)
        assertTrue(policy.screenAction(29_000))
        assertFalse(policy.expired(30_000))
        assertEquals(1L, policy.remaining(58_999))
        assertTrue(policy.expired(59_000))
        assertFalse(policy.screenAction(59_001))
        assertTrue(policy.expired(59_001))
    }
    @Test fun `end conversation preserves wake while full off disables it`() {
        assertEquals(WakeControlDecision(true, false), WakeControlPolicy.decide(WakeControlAction.END_CONVERSATION))
        assertEquals(WakeControlDecision(true, true), WakeControlPolicy.decide(WakeControlAction.FULL_OFF))
        assertEquals(WakeControlDecision(false, false), WakeControlPolicy.decide(WakeControlAction.DISMISS_NOTIFICATION))
    }
    @Test fun `idle expires thirty seconds after reply and passive checks never postpone it`() {
        val policy = ConversationIdlePolicy()
        policy.beginRequest(0)
        assertNull(policy.remaining(60_000))
        policy.turnFinished(60_000)
        repeat(29) { assertFalse(policy.expired(60_000L + it * 1000)) }
        assertEquals(1L, policy.remaining(89_999))
        assertTrue(policy.expired(90_000))
        assertTrue(policy.beginRequest(90_001))
        assertNull(policy.remaining(120_000))
    }
    @Test fun `new explicit request pauses idle and its actual reply starts the next deadline`() {
        val policy = ConversationIdlePolicy()
        policy.turnFinished(0)
        assertFalse(policy.beginRequest(29_999))
        assertFalse(policy.expired(90_000))
        policy.turnFinished(100_000)
        assertFalse(policy.expired(129_999))
        assertTrue(policy.expired(130_000))
        policy.clear()
        assertFalse(policy.expired(999_999))
    }
    @Test fun `microphone retries back off to thirty seconds and can be cancelled by their owner`() {
        val policy = WakeCaptureRetryPolicy()
        policy.failed(0)
        assertEquals(500L, policy.remaining(0))
        assertNull(policy.remaining(500))
        policy.failed(500)
        assertEquals(1000L, policy.remaining(500))
        repeat(20) { policy.failed(10_000) }
        assertEquals(30_000L, policy.remaining(10_000))
        policy.reset()
        assertNull(policy.remaining(10_000))
    }
}
