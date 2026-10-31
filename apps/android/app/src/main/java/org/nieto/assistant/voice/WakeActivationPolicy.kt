package org.nieto.assistant.voice

import java.text.Normalizer
import java.util.Locale

data class WakeRecognitionWord(val word: String, val confidence: Double?)

/** Gates decoded wake candidates, not the speaker's identity or intention. */
object WakeActivationPolicy {
    // Both words must independently clear a conservative initial recognition-score gate.
    // 0.85 reduces acceptance of uncertain guesses; it is not a guarantee against false wakes.
    const val MIN_CONFIDENCE = 0.85
    private val phrase = listOf("hola", "tino")

    fun accepts(isFinal: Boolean, text: String, words: List<WakeRecognitionWord>?): Boolean {
        val observedWords = words ?: return false
        if (!isFinal || text.length > 256 || text.contains("[unk]", ignoreCase = true) || normalized(text) != phrase || observedWords.size != 2) return false
        return observedWords.zip(phrase).all { (observed, expected) ->
            val confidence = observed.confidence
            normalized(observed.word) == listOf(expected) && confidence != null && confidence.isFinite() &&
                confidence >= MIN_CONFIDENCE && confidence <= 1.0
        }
    }

    private fun normalized(value: String): List<String> = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\p{P}\\p{S}\\p{Z}\\p{Cc}\\p{Cf}]+"), " ")
        .trim().split(Regex("\\s+"))
}
