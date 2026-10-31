package org.nieto.assistant.voice

import org.junit.Assert.*
import org.junit.Test

class LocalMicrophoneLeaseTest {
    @Test fun `only the recorder owner can release its microphone reservation`() {
        try {
            assertTrue(LocalMicrophoneLease.acquire("wake-test"))
            assertTrue(LocalMicrophoneLease.occupied)
            assertFalse(LocalMicrophoneLease.acquire("dictation-test"))
            LocalMicrophoneLease.release("dictation-test")
            assertTrue(LocalMicrophoneLease.occupied)
            LocalMicrophoneLease.release("wake-test")
            assertTrue(LocalMicrophoneLease.acquire("dictation-test"))
        } finally {
            LocalMicrophoneLease.release("wake-test")
            LocalMicrophoneLease.release("dictation-test")
        }
        assertFalse(LocalMicrophoneLease.occupied)
    }
}
