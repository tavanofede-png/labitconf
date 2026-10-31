package org.nieto.assistant.voice

import org.junit.Assert.*
import org.junit.Test

class LocalEndPolicyTest {
    private fun words(text: String, confidence: Double? = 0.96) = text.split(" ").map { WakeRecognitionWord(it, confidence) }
    private fun accepts(text: String) = LocalEndPolicy.accepts(true, text, words(text), activeConversation = true)

    @Test fun `clear common endings silently qualify without saying Hola Tino again`() {
        listOf("ya termine", "eso era todo", "no me hables mas", "dejame", "listo gracias", "ya esta",
            "no quiero seguir", "deja de hablar", "callate", "silencio", "basta", "cancela la tarea", "terminar la consulta",
            "tino ya termine por favor", "bueno eso era todo gracias")
            .forEach { assertTrue(it, accepts(it)) }
    }

    @Test fun `accents and punctuation do not change a clear final request`() {
        assertTrue(LocalEndPolicy.accepts(true, "¡Ya terminé!", words("ya termine"), activeConversation = true))
        assertTrue(LocalEndPolicy.accepts(true, "Déjame, por favor.", words("dejame por favor"), activeConversation = true))
        assertTrue(LocalEndPolicy.accepts(true, "TINO, no me hables más", words("tino no me hables mas"), activeConversation = true))
    }

    @Test fun `free dictation can retain courtesy and contextual ending tails without a closed grammar`() {
        listOf("bueno tino ya termine con esto por hoy", "bueno tino ya termine con esto por hoy gracias",
            "por favor tino no quiero que me hables mas por ahora", "bien ya he terminado con la consulta gracias")
            .forEach { assertTrue(it, accepts(it)) }
        listOf("bueno tino todavia no termine con esto por hoy", "ya hice este paso por ahora gracias",
            "bueno listo para empezar con la tarea", "le dije a tino que ya termine con esto por hoy")
            .forEach { assertFalse(it, accepts(it)) }
    }

    @Test fun `negations incomplete steps and readiness to begin never finish the task`() {
        listOf("todavia no termine", "no termine", "todavia no", "aun no", "no he terminado", "no quiero terminar",
            "no canceles la tarea", "ya hice este paso", "termine este paso", "este paso ya esta", "listo para empezar",
            "estoy listo para empezar", "listo para continuar", "dejame intentar esto", "no me hables de politica", "listo")
            .forEach { assertFalse(it, accepts(it)) }
    }

    @Test fun `ambient mentions unknown tokens and partial guesses cannot become a local end command`() {
        listOf("le dije ya termine", "dijo listo gracias y se fue", "ya termine [unk]", "[unk] ya esta", "ya termine 2")
            .forEach { assertFalse(it, accepts(it)) }
        assertFalse(LocalEndPolicy.accepts(false, "ya termine", words("ya termine", 1.0), activeConversation = true))
        assertFalse(LocalEndPolicy.accepts(true, "ya termine", words("ya termine"), activeConversation = false))
    }

    @Test fun `every final word needs a matching finite confidence at the conservative threshold`() {
        assertTrue(LocalEndPolicy.accepts(true, "ya termine", words("ya termine", 0.85), activeConversation = true))
        for (confidence in listOf(null, -0.1, 0.849, Double.NaN, Double.POSITIVE_INFINITY, 1.001)) {
            assertFalse("score $confidence", LocalEndPolicy.accepts(true, "ya termine", words("ya termine", confidence), activeConversation = true))
        }
        assertFalse(LocalEndPolicy.accepts(true, "ya termine", null, activeConversation = true))
        assertFalse(LocalEndPolicy.accepts(true, "ya termine", words("ya esta"), activeConversation = true))
        assertFalse(LocalEndPolicy.accepts(true, "ya termine", words("ya termine paso"), activeConversation = true))
    }

    @Test fun `a phrase already in the speaking assistant output is rejected as a possible echo`() {
        val output = "Cuando quieras terminar, podés decir ya terminé."
        assertFalse(LocalEndPolicy.accepts(true, "ya termine", words("ya termine"), activeConversation = true,
            assistantSpeaking = true, assistantText = output))
        assertTrue(LocalEndPolicy.accepts(true, "ya termine", words("ya termine"), activeConversation = true,
            assistantSpeaking = false, assistantText = output))
        assertTrue(LocalEndPolicy.accepts(true, "ya termine", words("ya termine"), activeConversation = true,
            assistantSpeaking = true, assistantText = "Abrí Gmail y buscá el botón Redactar."))
    }

    @Test fun `decoder grammar retains explicit negative alternatives instead of forcing a positive ending`() {
        listOf("todavia no termine", "ya hice este paso", "listo para empezar", "no termine")
            .forEach { assertTrue(it, LocalEndPolicy.grammarPhrases.contains(it)); assertFalse(accepts(it)) }
    }

    @Test fun `wake and local end remain distinct controls`() {
        val wake = words("hola tino")
        assertTrue(WakeActivationPolicy.accepts(true, "hola tino", wake))
        assertFalse(LocalEndPolicy.accepts(true, "hola tino", wake, activeConversation = true))
        assertFalse(WakeActivationPolicy.accepts(true, "ya termine", words("ya termine")))
        assertTrue(accepts("ya termine"))
    }
}
