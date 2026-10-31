package org.nieto.assistant.voice

import java.text.Normalizer
import java.util.Locale

/** Local control grammar. Accepted audio ends a conversation silently; it never creates a remote turn. */
object LocalEndPolicy {
    const val MIN_CONFIDENCE = 0.85
    private val endings = listOf(
        "ya termine", "termine", "ya he terminado", "ya terminamos", "hemos terminado", "ya esta", "eso era todo", "eso es todo", "eso fue todo", "listo gracias",
        "no me hables mas", "no hables mas", "no quiero que hables", "no quiero que hables mas", "no quiero que me hables", "no quiero que me hables mas", "no quiero hablar mas",
        "no quiero seguir", "no sigas", "dejame", "dejame tranquilo", "dejame tranquila", "dejame en paz", "deja de hablar", "callate",
        "silencio", "basta", "cancelar", "cancela", "cancela la tarea", "cancela la consulta", "cancelar la tarea",
        "cancelar la consulta", "termina la tarea", "termina la consulta", "terminar la tarea", "terminar la consulta",
        "ya no necesito ayuda", "ya no quiero ayuda", "no necesito mas ayuda", "no quiero mas ayuda", "no me respondas mas", "deja de responder"
    )
    private val nonEndings = listOf(
        "no termine", "todavia no termine", "todavia no", "aun no", "no he terminado", "no termine todavia",
        "no quiero terminar", "no canceles", "no canceles la tarea", "no me dejes", "ya hice este paso",
        "termine este paso", "este paso ya esta", "listo para empezar", "estoy listo para empezar", "listo para continuar"
    )
    private fun variants(phrases: List<String>): Set<String> = phrases.flatMap { phrase ->
        listOf("", "tino ", "bueno ").flatMap { prefix ->
            listOf("", " gracias", " por favor").map { suffix -> (prefix + phrase + suffix).trim() }
        }
    }.toSet()
    private val acceptedPhrases = variants(endings)
    private val prefixes = listOf(listOf("hola", "tino"), listOf("por", "favor"), listOf("bueno"), listOf("bien"), listOf("tino"))
    private val suffixes = listOf(listOf("muchas", "gracias"), listOf("por", "favor"), listOf("por", "hoy"), listOf("por", "ahora"),
        listOf("con", "esto"), listOf("con", "la", "tarea"), listOf("con", "la", "consulta"), listOf("con", "el", "tramite"),
        listOf("gracias"), listOf("tino"))

    // Optional grammar fallback for callers that need it. Free END dictation is preferred:
    // it preserves negatives and paraphrases instead of forcing ambient speech into this list.
    val grammarPhrases: List<String> = (acceptedPhrases + variants(nonEndings)).toList()

    fun accepts(isFinal: Boolean, text: String, words: List<WakeRecognitionWord>?, activeConversation: Boolean,
        assistantSpeaking: Boolean = false, assistantText: String? = null): Boolean {
        if (!activeConversation || !isFinal || text.length > 256 || text.contains("[unk]", ignoreCase = true)) return false
        val tokens = normalized(text)
        if (!ending(tokens)) return false
        val observed = words ?: return false
        if (observed.size != tokens.size || observed.size > 16) return false
        if (!observed.zip(tokens).all { (word, expected) ->
                val confidence = word.confidence
                normalized(word.word) == listOf(expected) && confidence != null && confidence.isFinite() &&
                    confidence >= MIN_CONFIDENCE && confidence <= 1.0
            }) return false
        // Known output text can identify an echo candidate without storing ambient audio or identifying a speaker.
        // AEC and this guard reduce self-interruption; neither proves who spoke a phrase.
        if (assistantSpeaking && assistantText != null) {
            val output = normalized(assistantText.takeLast(12_000))
            if (output.windowed(tokens.size).any { it == tokens }) return false
        }
        return true
    }

    private fun ending(tokens: List<String>): Boolean {
        if ("paso" in tokens || nonEndings.any { phrase ->
                val negative = phrase.split(" ")
                tokens.windowed(negative.size).any { it == negative }
            }) return false
        if (tokens.joinToString(" ") in acceptedPhrases) return true
        var candidate = tokens
        repeat(3) { prefixes.firstOrNull { candidate.take(it.size) == it }?.let { candidate = candidate.drop(it.size) } }
        repeat(3) { suffixes.firstOrNull { candidate.takeLast(it.size) == it }?.let { candidate = candidate.dropLast(it.size) } }
        return candidate.joinToString(" ") in endings
    }

    private fun normalized(value: String): List<String> = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\p{P}\\p{S}\\p{Z}\\p{Cc}\\p{Cf}]+"), " ")
        .trim().split(Regex("\\s+"))
}
