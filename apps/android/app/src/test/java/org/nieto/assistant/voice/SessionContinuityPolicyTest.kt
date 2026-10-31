package org.nieto.assistant.voice

import org.junit.Assert.*
import org.junit.Test

class SessionContinuityPolicyTest {
    @Test fun `errors are audible for a requested turn but not an unanswered automatic reply window`() {
        assertTrue(SessionContinuityPolicy.shouldSpeakError(false, false))
        assertFalse(SessionContinuityPolicy.shouldSpeakError(true, false))
        assertTrue(SessionContinuityPolicy.shouldSpeakError(true, true))
    }
    @Test fun `technical closure preserves intention while user stop and identity change clear it`() {
        val memory = TaskContextMemory()
        val policy = SessionContinuityPolicy()
        val identity = "https://tino.example" to "test-device-a"
        memory.bind(identity)
        memory.remember(TaskContext("Crear un grupo", "WhatsApp", "Elegir contactos"))
        policy.ready(0, 270_000, 225_000, true)
        policy.transportClosed()
        assertNotNull(memory.context)
        assertFalse(memory.bind(identity))
        assertNotNull(memory.context)
        assertTrue(memory.bind(identity.first to "test-device-b"))
        assertNull(memory.context)
        memory.remember(TaskContext("Crear un correo", "Gmail", "Nombre"))
        memory.clear()
        assertNull(memory.context)
    }
    @Test fun `rotate before a turn can outlive the remaining transport budget`() {
        val policy = SessionContinuityPolicy()
        policy.ready(10_000, 270_000, 225_000, true)
        assertFalse(policy.shouldRenewBeforeTurn(69_999))
        assertTrue(policy.shouldRenewBeforeTurn(70_000))
    }
    @Test fun `server renewal is deferred to caller safe point and reset on next ready`() {
        val policy = SessionContinuityPolicy()
        policy.ready(0, 270_000, 225_000, true)
        policy.requireRenewal()
        assertTrue(policy.shouldRenewBeforeTurn(1_000))
        policy.transportClosed()
        assertFalse(policy.shouldRenewBeforeTurn(280_000))
        policy.ready(280_000, 270_000, 225_000, true)
        assertFalse(policy.shouldRenewBeforeTurn(280_001))
    }
    @Test fun `server opt in is required for automatic continuity`() {
        val policy = SessionContinuityPolicy()
        policy.ready(0, 270_000, 225_000, false)
        policy.requireRenewal()
        assertFalse(policy.shouldRenewBeforeTurn(269_000))
    }
    @Test fun `retries are bounded with bounded jitter and no fourth retry`() {
        assertEquals(500L, SessionContinuityPolicy.retryDelay(1, -99))
        assertEquals(1_100L, SessionContinuityPolicy.retryDelay(2, 100))
        assertEquals(2_200L, SessionContinuityPolicy.retryDelay(3, 99_999))
        assertNull(SessionContinuityPolicy.retryDelay(4, 0))
    }
    @Test fun `only three bounded strings are carried and empty intention clears context`() {
        val context = TaskContext.bounded("g".repeat(700), "a".repeat(100), "p".repeat(500))!!
        assertEquals(600, context.goal.length)
        assertEquals(80, context.targetApp.length)
        assertEquals(400, context.progress.length)
        assertNull(TaskContext.bounded(" \n", "app", "old progress"))
        assertEquals("crear correo", TaskContext.bounded("crear\n\tcorreo", "Gmail", "")!!.goal)
    }
}
