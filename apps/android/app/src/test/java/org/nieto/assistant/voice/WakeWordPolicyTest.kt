package org.nieto.assistant.voice

import org.junit.Assert.*
import org.junit.Test

class WakeWordPolicyTest {
    private val ready = WakeWordConditions(true, true, true, false, false, false)

    @Test fun `activation requires adjacent complete wake words`() {
        for (phrase in listOf("Hola Tino", "¡HÓLA, TINO!", "hola tino quiero ayuda")) assertTrue(WakeWordPolicy.matches(phrase))
        for (phrase in listOf("hola", "tino", "hola tinoide", "caracola tino", "hola mi tino", "holatino", "")) assertFalse(WakeWordPolicy.matches(phrase))
    }

    @Test fun `every permission and audio ownership gate prevents wake`() {
        val policy = WakeWordPolicy()
        assertTrue(policy.canListen(ready, 0))
        val blocked = listOf(ready.copy(enabled = false), ready.copy(assistanceEnabled = false),
            ready.copy(accessibilityConnected = false), ready.copy(voiceBusy = true), ready.copy(ttsSpeaking = true))
        blocked.forEach { conditions ->
            assertFalse(policy.canListen(conditions, 0))
            assertFalse(policy.accept("hola tino", conditions, 0))
        }
    }

    @Test fun `wake stays available in TINOs own configuration and family surfaces`() {
        val policy = WakeWordPolicy()
        val ownSurface = ready.copy(nativeSurfaceVisible = true)
        assertTrue(policy.canListen(ownSurface, 0))
        assertTrue(policy.accept("hola tino", ownSurface, 0))
    }

    @Test fun `wake latches until the assistant turn finishes and playback has settled`() {
        val policy = WakeWordPolicy()
        assertTrue(policy.accept("hola tino", ready, 100))
        assertFalse(policy.accept("hola tino", ready, 101))
        policy.updateVoice(true, 200)
        policy.updateVoice(true, 30_000)
        assertFalse(policy.canListen(ready, 30_000))
        policy.updateVoice(false, 31_000)
        assertFalse(policy.canListen(ready, 32_999))
        assertTrue(policy.canListen(ready, 33_000))
    }

    @Test fun `failed preparation eventually rearms without depending on another state emission`() {
        val policy = WakeWordPolicy()
        assertTrue(policy.accept("hola tino", ready, 0))
        assertEquals(12_000L, policy.nextCheckDelay(0))
        policy.updateVoice(false, 11_999)
        assertFalse(policy.canListen(ready, 11_999))
        policy.updateVoice(false, 12_000)
        assertEquals(2_000L, policy.nextCheckDelay(12_000))
        assertFalse(policy.canListen(ready, 13_999))
        assertTrue(policy.canListen(ready, 14_000))
        assertTrue(policy.accept("hola tino", ready, 14_000))
    }

    @Test fun `unrelated recognized speech never starts a handoff`() {
        val policy = WakeWordPolicy()
        assertFalse(policy.accept("abrir whatsapp", ready, 0))
        assertTrue(policy.canListen(ready, 1))
        assertNull(policy.nextCheckDelay(1))
    }

    @Test fun `explicit system gesture hands off once without requiring a visible app or acoustic phrase`() {
        val policy = WakeWordPolicy()
        assertTrue(policy.requestHandoff(ready.copy(nativeSurfaceVisible = true), 0))
        assertFalse(policy.requestHandoff(ready, 1))
        policy.updateVoice(true, 2)
        policy.updateVoice(false, 3)
        assertTrue(policy.requestHandoff(ready, 4))
    }
}
