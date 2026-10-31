package org.nieto.assistant.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceActivityPolicyTest {
    @Test fun `a brief spoken confirmation is accepted only in the explicit reply window`() {
        val reply = VoiceActivityPolicy(followUp = true)
        reply.observe(pcm(1600, 180))
        assertTrue(reply.hasSpeech)
        assertEquals(VoiceActivityPolicy.Decision.END_OF_SPEECH, reply.observe(pcm(0, 1200)))
        val normal = VoiceActivityPolicy()
        normal.observe(pcm(1600, 180))
        assertFalse(normal.hasSpeech)
    }
    @Test fun `a sixty millisecond click does not become a spoken confirmation`() {
        val reply = VoiceActivityPolicy(followUp = true)
        reply.observe(pcm(9000, 60))
        assertFalse(reply.hasSpeech)
        assertEquals(VoiceActivityPolicy.Decision.NO_SPEECH, reply.observe(pcm(0, 7940)))
    }
    private fun pcm(amplitude: Int, milliseconds: Int = 100): ByteArray {
        val samples = RealtimePolicy.SAMPLE_RATE * milliseconds / 1000
        return ByteArray(samples * 2).apply {
            repeat(samples) { index ->
                val value = if (index % 2 == 0) amplitude else -amplitude
                this[index * 2] = (value and 0xff).toByte()
                this[index * 2 + 1] = (value shr 8).toByte()
            }
        }
    }

    @Test fun `silence times out without a speech commit`() {
        val policy = VoiceActivityPolicy()
        repeat(79) { assertEquals(VoiceActivityPolicy.Decision.CONTINUE, policy.observe(pcm(0))) }
        assertEquals(VoiceActivityPolicy.Decision.NO_SPEECH, policy.observe(pcm(0)))
        assertFalse(policy.hasSpeech)
        assertEquals(8000L, policy.elapsedMs)
    }

    @Test fun `low ambient noise and isolated clicks do not become an utterance`() {
        val noise = VoiceActivityPolicy()
        repeat(79) { assertEquals(VoiceActivityPolicy.Decision.CONTINUE, noise.observe(pcm(180))) }
        assertEquals(VoiceActivityPolicy.Decision.NO_SPEECH, noise.observe(pcm(180)))
        assertFalse(noise.hasSpeech)
        val clicks = VoiceActivityPolicy()
        repeat(3) { clicks.observe(pcm(9000)); clicks.observe(pcm(0)) }
        repeat(73) { clicks.observe(pcm(0)) }
        assertEquals(VoiceActivityPolicy.Decision.NO_SPEECH, clicks.observe(pcm(0)))
        assertFalse(clicks.hasSpeech)
    }

    @Test fun `speech finishes automatically after a twelve hundred millisecond pause`() {
        val policy = VoiceActivityPolicy()
        repeat(5) { assertEquals(VoiceActivityPolicy.Decision.CONTINUE, policy.observe(pcm(1600))) }
        assertTrue(policy.hasSpeech)
        repeat(11) { assertEquals(VoiceActivityPolicy.Decision.CONTINUE, policy.observe(pcm(0))) }
        assertEquals(VoiceActivityPolicy.Decision.END_OF_SPEECH, policy.observe(pcm(0)))
        assertEquals(1700L, policy.elapsedMs)
        assertEquals(VoiceActivityPolicy.Decision.END_OF_SPEECH, policy.observe(pcm(1600)))
    }

    @Test fun `a short pause inside a sentence does not split the user's turn`() {
        val policy = VoiceActivityPolicy()
        repeat(5) { policy.observe(pcm(1600)) }
        repeat(10) { assertEquals(VoiceActivityPolicy.Decision.CONTINUE, policy.observe(pcm(100))) }
        repeat(4) { assertEquals(VoiceActivityPolicy.Decision.CONTINUE, policy.observe(pcm(1500))) }
        repeat(11) { assertEquals(VoiceActivityPolicy.Decision.CONTINUE, policy.observe(pcm(0))) }
        assertEquals(VoiceActivityPolicy.Decision.END_OF_SPEECH, policy.observe(pcm(0)))
    }

    @Test fun `continuous energy is bounded by the forty five second cap`() {
        val policy = VoiceActivityPolicy()
        repeat(449) { assertEquals(VoiceActivityPolicy.Decision.CONTINUE, policy.observe(pcm(1600))) }
        assertEquals(VoiceActivityPolicy.Decision.MAX_DURATION, policy.observe(pcm(1600)))
        assertTrue(policy.hasSpeech)
        assertEquals(45000L, policy.elapsedMs)
    }

    @Test fun `partial frames and byte counts outside the provided PCM are rejected`() {
        val policy = VoiceActivityPolicy()
        assertThrows(IllegalArgumentException::class.java) { policy.observe(byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { policy.observe(byteArrayOf(1, 2), 4) }
        assertThrows(IllegalArgumentException::class.java) { policy.observe(byteArrayOf(1, 2), -2) }
        assertEquals(VoiceActivityPolicy.Decision.CONTINUE, policy.observe(byteArrayOf()))
        assertEquals(0L, policy.elapsedMs)
        assertFalse(policy.hasSpeech)
    }

    @Test fun `only the requested audio bytes count and signed full scale samples stay finite`() {
        val policy = VoiceActivityPolicy()
        val block = pcm(32767, 200)
        assertEquals(VoiceActivityPolicy.Decision.CONTINUE, policy.observe(block, RealtimePolicy.CHUNK_BYTES))
        assertEquals(100L, policy.elapsedMs)
        policy.observe(pcm(32767)); policy.observe(pcm(32767))
        assertTrue(policy.hasSpeech)
    }
}
