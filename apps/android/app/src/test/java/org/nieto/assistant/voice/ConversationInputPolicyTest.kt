package org.nieto.assistant.voice

import org.junit.Assert.*
import org.junit.Test

class ConversationInputPolicyTest {
    @Test fun `silence unknown and unchanged partials never extend conversation activity`() {
        val policy = ConversationInputPolicy()
        assertFalse(policy.partial("", 16_000))
        assertFalse(policy.partial("[unk]", 32_000))
        assertNull(policy.finalStart("", 32_000, null))
        assertTrue(policy.partial("quiero", 48_000, 24_000))
        assertFalse(policy.partial("quiero", 64_000, 24_000))
        assertTrue(policy.partial("quiero cambiar el brillo", 80_000, 24_000))
        assertEquals(24_000L, policy.finalStart("quiero cambiar el brillo", 96_000, 24_000))
    }

    @Test fun `the actual final timestamp can recover the first word missed by a delayed partial`() {
        val policy = ConversationInputPolicy()
        assertTrue(policy.partial("si", 48_000))
        assertEquals(32_000L, policy.startSample)
        assertEquals(16_000L, policy.finalStart("si", 64_000, 16_000))
        assertEquals(32_000L, policy.finalStart("", 64_000, null))
        assertFalse(policy.atLimit(32_000 + WakePcmRingBuffer.MAX_SAMPLES - 321L))
        assertTrue(policy.atLimit(32_000 + WakePcmRingBuffer.MAX_SAMPLES - 320L))
    }
}
