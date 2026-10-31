package org.nieto.assistant.voice

import kotlin.math.max
import kotlin.math.sqrt

/** Energy-based endpointing for one PCM16 mono utterance. Keeps counters only, never PCM or transcripts. */
class VoiceActivityPolicy(private val sampleRate: Int = RealtimePolicy.SAMPLE_RATE, followUp: Boolean = false) {
    enum class Decision { CONTINUE, END_OF_SPEECH, NO_SPEECH, MAX_DURATION }

    companion object {
        const val NO_SPEECH_TIMEOUT_MS = 8_000L
        const val END_SILENCE_MS = 1_200L
        const val MAX_CAPTURE_MS = 45_000L
        private const val MIN_VOICE_MS = 300L
        private const val MIN_VOICE_RUN_MS = 200L
        private const val MIN_VOICE_RMS = 450.0
    }

    init { require(sampleRate > 0) }
    private val minimumVoiceMs = if (followUp) 160L else MIN_VOICE_MS
    private val minimumRunMs = if (followUp) 120L else MIN_VOICE_RUN_MS
    @Volatile var hasSpeech = false
        private set
    private var totalSamples = 0L
    private var voicedSamples = 0L
    private var currentRunSamples = 0L
    private var longestRunSamples = 0L
    private var lastVoiceSample = 0L
    private var noiseFloor = 100.0
    private var terminal: Decision? = null
    val elapsedMs get() = totalSamples * 1000 / sampleRate

    fun observe(pcm: ByteArray, byteCount: Int = pcm.size): Decision {
        require(byteCount in 0..pcm.size && byteCount % 2 == 0) { "Se necesita un bloque PCM16 completo." }
        terminal?.let { return it }
        if (byteCount == 0) return Decision.CONTINUE
        val samples = byteCount / 2
        var squareSum = 0.0
        var offset = 0
        while (offset < byteCount) {
            val sample = ((pcm[offset + 1].toInt() shl 8) or (pcm[offset].toInt() and 0xff)).toDouble()
            squareSum += sample * sample
            offset += 2
        }
        val rms = sqrt(squareSum / samples)
        totalSamples += samples
        // Learn quiet ambient energy before voice; never raise the floor from candidate speech.
        val threshold = if (hasSpeech) max(MIN_VOICE_RMS * 0.7, noiseFloor * 2.2) else max(MIN_VOICE_RMS, noiseFloor * 3)
        if (rms >= threshold) {
            voicedSamples += samples
            currentRunSamples += samples
            longestRunSamples = max(longestRunSamples, currentRunSamples)
            lastVoiceSample = totalSamples
            if (voicedSamples * 1000 / sampleRate >= minimumVoiceMs && longestRunSamples * 1000 / sampleRate >= minimumRunMs) hasSpeech = true
        } else {
            currentRunSamples = 0
            if (!hasSpeech && rms < MIN_VOICE_RMS) noiseFloor = noiseFloor * 0.9 + rms * 0.1
        }
        val decision = when {
            !hasSpeech && elapsedMs >= NO_SPEECH_TIMEOUT_MS -> Decision.NO_SPEECH
            elapsedMs >= MAX_CAPTURE_MS -> Decision.MAX_DURATION
            hasSpeech && (totalSamples - lastVoiceSample) * 1000 / sampleRate >= END_SILENCE_MS -> Decision.END_OF_SPEECH
            else -> Decision.CONTINUE
        }
        if (decision != Decision.CONTINUE) terminal = decision
        return decision
    }
}
