package org.nieto.assistant.presence

import org.junit.Assert.*
import org.junit.Test

class WakePhraseTest {
    @Test fun acceptsGreetingWithPunctuationAndCapitalization() {
        assertTrue(WakePhrase.matches("¡Hola, TINO!"))
        assertTrue(WakePhrase.matches("hola tino"))
    }
    @Test fun requiresBothCompleteAdjacentWords() {
        listOf("tino", "hola", "hola vecino", "hola argentino", "hola a tino", "holatino").forEach { assertFalse(it, WakePhrase.matches(it)) }
    }
}
