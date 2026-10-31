package org.nieto.assistant.voice

import org.junit.Assert.*
import org.junit.Test

class RealtimePolicyTest {
    @Test fun `socket authentication stays out of URL and production rejects cleartext`() {
        assertEquals("wss://nieto.example/api/assistant/realtime", RealtimePolicy.socketUrl("https://nieto.example/", false))
        assertEquals("ws://127.0.0.1:8787/api/assistant/realtime", RealtimePolicy.socketUrl("http://127.0.0.1:8787", true))
        for (url in listOf("http://127.0.0.1:8787", "https://name:token@nieto.example", "https://nieto.example?token=abc", "https://nieto.example/path")) {
            assertThrows(IllegalArgumentException::class.java) { RealtimePolicy.socketUrl(url, false) }
        }
    }
    @Test fun `PCM preserves signed little endian bytes and rejects odd or oversized chunks`() {
        val pcm = byteArrayOf(0, -128, -1, 127, 1, 0)
        assertArrayEquals(pcm, RealtimePolicy.decodeOutput(RealtimePolicy.encodeInput(pcm, pcm.size)))
        assertThrows(IllegalArgumentException::class.java) { RealtimePolicy.encodeInput(pcm, 3) }
        assertThrows(IllegalArgumentException::class.java) { RealtimePolicy.encodeInput(ByteArray(12_002), 12_002) }
        assertThrows(IllegalArgumentException::class.java) { RealtimePolicy.decodeOutput("AA==") }
        assertEquals(2_160_000, RealtimePolicy.MAX_INPUT_BYTES)
        assertEquals(4_800, RealtimePolicy.MIN_INPUT_BYTES)
    }
    @Test fun `cancelled and replaced turn ids cannot replay audio or text`() {
        val turns = RealtimeTurnGuard()
        assertTrue(turns.begin("turn-1"))
        assertTrue(turns.accepts("turn-1"))
        assertFalse(turns.accepts("turn-2"))
        assertFalse(turns.begin("turn-1"))
        turns.cancel()
        assertFalse(turns.accepts("turn-1"))
        assertFalse(turns.begin("turn-1"))
        assertTrue(turns.begin("turn-2"))
        assertTrue(turns.begin("turn-3"))
        assertFalse(turns.accepts("turn-2"))
        assertFalse(turns.begin("turn-2"))
        assertTrue(turns.accepts("turn-3"))
    }
}
