package org.nieto.assistant.presence

import org.json.JSONObject
import org.nieto.assistant.voice.WakeInvocationPCM
import org.nieto.assistant.voice.WakePcmRingBuffer
import org.nieto.assistant.voice.WakePrefixProof
import org.nieto.assistant.voice.WakeTimedWord
import org.vosk.Model
import org.vosk.Recognizer
import java.io.InputStream

internal data class SyntheticWakeUtterance(val text: String, val words: List<WakeTimedWord>?, val invocation: WakeInvocationPCM?)

/** Mirrors the production dual-decoder clock using bundled synthetic PCM only; never opens a microphone. */
internal fun decodeSyntheticWake(model: Model, input: InputStream): List<SyntheticWakeUtterance> {
    data class Candidate(val text: String, val words: List<WakeTimedWord>?, val finalSample: Long, var invocation: WakeInvocationPCM? = null)
    val ring = WakePcmRingBuffer()
    val candidates = mutableListOf<Candidate>()
    var wakeProof: WakePrefixProof? = null
    var completed = false
    fun words(json: JSONObject): List<WakeTimedWord>? = json.optJSONArray("result")?.let { array ->
        (0 until array.length()).map { index ->
            val word = array.optJSONObject(index)
            WakeTimedWord(word?.optString("word").orEmpty(), (word?.opt("conf") as? Number)?.toDouble(),
                (word?.opt("start") as? Number)?.toDouble(), (word?.opt("end") as? Number)?.toDouble())
        }
    }
    fun attachVerifiedAudio() {
        val proof = wakeProof ?: return
        candidates.filter { it.invocation == null }.forEach { candidate ->
            candidate.invocation = ring.extractVerifiedInvocation(proof, candidate.finalSample, candidate.words)
        }
    }
    fun constrainedFinal(raw: String) {
        val json = JSONObject(raw)
        val verified = ring.verifyWakePrefix(true, json.optString("text"), words(json))
        if (verified != null) wakeProof = verified
        // A free endpoint can arrive before the constrained one. Retain its original metadata and retry.
        attachVerifiedAudio()
    }
    fun freeFinal(raw: String) {
        val json = JSONObject(raw)
        candidates += Candidate(json.optString("text"), words(json), ring.totalSamples)
        attachVerifiedAudio()
    }
    try {
        Recognizer(model, 16000f, "[\"hola tino\",\"[unk]\"]").use { wake ->
            Recognizer(model, 16000f).use { free ->
                wake.setWords(true)
                free.setWords(true)
                fun feed(bytes: ByteArray, count: Int) {
                    check(ring.appendPcm16(bytes, count)) { "synthetic_pcm_alignment" }
                    // Both recognizers receive the exact same callback, offset and generation.
                    if (wake.acceptWaveForm(bytes, count)) constrainedFinal(wake.result)
                    if (free.acceptWaveForm(bytes, count)) freeFinal(free.result)
                }
                val chunk = ByteArray(6400)
                try {
                    while (true) { val count = input.read(chunk); if (count < 0) break; feed(chunk, count) }
                } finally { chunk.fill(0) }
                repeat(15) { val silence = ByteArray(6400); feed(silence, silence.size) }
                constrainedFinal(wake.finalResult)
                freeFinal(free.finalResult)
            }
        }
        val results = candidates.map { SyntheticWakeUtterance(it.text, it.words, it.invocation) }
        completed = true
        return results
    } finally {
        if (!completed) candidates.forEach { it.invocation?.pcm24k?.fill(0) }
        ring.clear()
        wakeProof = null
    }
}
