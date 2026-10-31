package org.nieto.assistant.presence

import java.text.Normalizer
import java.util.Locale

object WakePhrase {
    fun matches(text: String): Boolean {
        val words = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT).replace(Regex("[^a-z ]"), " ").trim().split(Regex("\\s+"))
        return words.zipWithNext().any { (first, second) -> first == "hola" && second == "tino" }
    }
}
