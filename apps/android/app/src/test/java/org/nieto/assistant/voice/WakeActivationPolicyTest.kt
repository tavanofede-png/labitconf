package org.nieto.assistant.voice

import org.junit.Assert.*
import org.junit.Test

class WakeActivationPolicyTest {
    private fun words(first: Double? = 0.96, second: Double? = 0.95) =
        listOf(WakeRecognitionWord("hola", first), WakeRecognitionWord("tino", second))

    @Test fun `a completed exact wake with both word scores at threshold is accepted`() {
        assertTrue(WakeActivationPolicy.accepts(true, "hola tino", words()))
        assertTrue(WakeActivationPolicy.accepts(true, "hola tino", words(0.85, 0.85)))
        assertTrue(WakeActivationPolicy.accepts(true, "hola tino", words(1.0, 1.0)))
    }

    @Test fun `accents case whitespace and punctuation normalize without discarding additional words or numbers`() {
        assertTrue(WakeActivationPolicy.accepts(true, "  ¡HOLÁ, TÍNO!  ", words()))
        assertTrue(WakeActivationPolicy.accepts(true, "hola—tino", listOf(WakeRecognitionWord("¡Holá!", 0.9), WakeRecognitionWord("Tíno", 0.9))))
        assertFalse(WakeActivationPolicy.accepts(true, "hola tino 2", words()))
        assertFalse(WakeActivationPolicy.accepts(true, "hola tino2", words()))
        assertFalse(WakeActivationPolicy.accepts(true, "holatino", words()))
        assertFalse(WakeActivationPolicy.accepts(true, "hоla tino", words())) // Cyrillic o is a different decoded word.
    }

    @Test fun `partial guesses can never activate even when their words and scores appear correct`() {
        assertFalse(WakeActivationPolicy.accepts(false, "hola tino", words(1.0, 1.0)))
        assertFalse(WakeActivationPolicy.accepts(false, "¡Hola Tino!", words()))
    }

    @Test fun `unknown tokens surrounding speech and quoted mentions are not wake commands`() {
        listOf("", "hola", "tino", "hola [unk] tino", "[unk] hola tino", "hola tino [unk]",
            "Le dije hola Tino", "Dijo hola tino y siguió hablando", "hola tino ayudame", "hola tino hola tino", "buen día")
            .forEach { assertFalse(it, WakeActivationPolicy.accepts(true, it, words())) }
        assertFalse(WakeActivationPolicy.accepts(true, "hola tino", listOf(WakeRecognitionWord("hola", 0.99), WakeRecognitionWord("[unk]", 0.99))))
    }

    @Test fun `each word requires a finite in-range confidence and one strong word cannot compensate for the other`() {
        for (invalid in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 0.0, 0.849, 1.001)) {
            assertFalse("first score $invalid", WakeActivationPolicy.accepts(true, "hola tino", words(invalid, 1.0)))
            assertFalse("second score $invalid", WakeActivationPolicy.accepts(true, "hola tino", words(1.0, invalid)))
        }
    }

    @Test fun `missing mismatched or additional word results are rejected instead of inferred from text`() {
        assertFalse(WakeActivationPolicy.accepts(true, "hola tino", null))
        assertFalse(WakeActivationPolicy.accepts(true, "hola tino", emptyList()))
        assertFalse(WakeActivationPolicy.accepts(true, "hola tino", listOf(WakeRecognitionWord("hola tino", 0.99))))
        assertFalse(WakeActivationPolicy.accepts(true, "hola tino", words() + WakeRecognitionWord("ayuda", 0.99)))
        assertFalse(WakeActivationPolicy.accepts(true, "hola tino", listOf(WakeRecognitionWord("tino", 0.99), WakeRecognitionWord("hola", 0.99))))
    }
}
