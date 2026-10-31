package org.nieto.assistant.voice

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

data class WakeTimedWord(val word: String, val confidence: Double?, val startSeconds: Double?, val endSeconds: Double?)
data class WakeInvocationPCM(val pcm24k: ByteArray, val startSample16k: Long, val endSample16k: Long)

/** Independent constrained-decoder evidence; valid only for the ring generation that observed it. */
class WakePrefixProof internal constructor(internal val generation: Any, internal val ranges: List<Pair<Long, Long>>)

/** A bounded local buffer sharing the recognizer's sample clock. Nothing before a verified wake is exported. */
class WakePcmRingBuffer(private val capacitySamples: Int = MAX_SAMPLES) {
    private val samples: ShortArray
    private var stored = 0
    private var writeAt = 0
    private var deliveredSpeechEnd: Long? = null
    private var generation = Any()
    var totalSamples = 0L; private set
    val oldestSample: Long get() = totalSamples - stored

    init {
        require(capacitySamples in 1..MAX_SAMPLES)
        samples = ShortArray(capacitySamples)
    }

    @Synchronized fun append(input: ShortArray, count: Int): Boolean {
        if (count <= 0 || count > input.size || totalSamples > Long.MAX_VALUE - count) return false
        val first = if (count >= capacitySamples) count - capacitySamples else 0
        if (count >= capacitySamples) { writeAt = 0; stored = 0 }
        for (index in first until count) {
            samples[writeAt] = input[index]
            writeAt = (writeAt + 1) % capacitySamples
        }
        stored = (stored + count - first).coerceAtMost(capacitySamples)
        totalSamples += count
        return true
    }

    @Synchronized fun appendPcm16(bytes: ByteArray, count: Int = bytes.size): Boolean {
        if (count <= 0 || count > bytes.size || count % 2 != 0) return false
        val sampleCount = count / 2
        if (totalSamples > Long.MAX_VALUE - sampleCount) return false
        val first = if (sampleCount >= capacitySamples) sampleCount - capacitySamples else 0
        if (sampleCount >= capacitySamples) { writeAt = 0; stored = 0 }
        for (index in first until sampleCount) {
            samples[writeAt] = ((bytes[index * 2].toInt() and 255) or (bytes[index * 2 + 1].toInt() shl 8)).toShort()
            writeAt = (writeAt + 1) % capacitySamples
        }
        stored = (stored + sampleCount - first).coerceAtMost(capacitySamples)
        totalSamples += sampleCount
        return true
    }

    /** Call only with an actual FINAL from the independent constrained wake recognizer. */
    @Synchronized fun verifyWakePrefix(isFinal: Boolean, text: String, words: List<WakeTimedWord>?): WakePrefixProof? {
        if (!isFinal || words == null || words.size !in 2..64 || text.length > 1024 || stored == 0) return null
        val tokens = wordTokens(text, words) ?: return null
        if (tokens.take(2) != listOf("hola", "tino") || !strongPrefix(words)) return null
        val ranges = timedRanges(words) ?: return null
        if (ranges.first().first < oldestSample) return null
        return WakePrefixProof(generation, ranges.take(2))
    }

    /** Both recognizers must receive identical PCM and share the ring's cumulative sample clock. */
    @Synchronized fun extractInvocation(isFinal: Boolean, text: String, words: List<WakeTimedWord>?, wakeProof: WakePrefixProof? = null): WakeInvocationPCM? {
        if (!isFinal || words == null || words.size !in 2..64 || text.length > 1024 || stored == 0) return null
        val tokens = wordTokens(text, words) ?: return null
        if (tokens.take(2) != listOf("hola", "tino")) return null
        val ranges = timedRanges(words) ?: return null
        if (wakeProof != null) {
            if (wakeProof.generation !== generation || wakeProof.ranges.size != 2) return null
            if (ranges.take(2).zip(wakeProof.ranges).any { (free, verified) ->
                abs(free.first - verified.first) > PREFIX_ALIGNMENT_SAMPLES || abs(free.second - verified.second) > PREFIX_ALIGNMENT_SAMPLES
            }) return null
        } else if (!strongPrefix(words)) return null
        if (ranges.first().first < oldestSample || wakeProof?.ranges?.first()?.first?.let { it < oldestSample } == true) return null
        // Independent evidence never rewrites free-decoder confidence or truncates its following query.
        // Round forward and respect both starts, so no sample precedes either reported Hola.
        val start = maxOf(ranges.first().first, wakeProof?.ranges?.first()?.first ?: 0L)
        val speechEnd = ranges.last().second
        if (speechEnd <= start) return null
        if (start < oldestSample || speechEnd - start > capacitySamples) return null
        if (deliveredSpeechEnd?.let { start < it || speechEnd <= it } == true) return null
        val end = minOf(totalSamples, speechEnd + POST_PADDING_SAMPLES)
        val size = end - start
        if (size <= 0 || size > capacitySamples) return null
        val pcm16 = ByteArray(size.toInt() * 2)
        val oldestIndex = if (stored == capacitySamples) writeAt else 0
        val relativeStart = (start - oldestSample).toInt()
        for (offset in 0 until size.toInt()) {
            val value = samples[(oldestIndex + relativeStart + offset) % capacitySamples].toInt()
            pcm16[offset * 2] = value.toByte()
            pcm16[offset * 2 + 1] = (value shr 8).toByte()
        }
        val pcm24 = try { WakePcmResampler.to24k(pcm16) } finally { pcm16.fill(0) }
        if (pcm24 == null) return null
        deliveredSpeechEnd = speechEnd
        return WakeInvocationPCM(pcm24, start, end)
    }

    @Synchronized fun clear() {
        samples.fill(0)
        stored = 0; writeAt = 0; totalSamples = 0; deliveredSpeechEnd = null
        generation = Any()
    }

    private fun strongPrefix(words: List<WakeTimedWord>) = words.take(2).all { word ->
        word.confidence?.let { it.isFinite() && it >= WakeActivationPolicy.MIN_CONFIDENCE && it <= 1.0 } == true
    }

    private fun wordTokens(text: String, words: List<WakeTimedWord>): List<String>? {
        val decoded = words.map { normalized(it.word) }
        if (decoded.any { it.size != 1 }) return null
        val tokens = decoded.map { it.single() }
        return tokens.takeIf { normalized(text) == it }
    }

    private fun timedRanges(words: List<WakeTimedWord>): List<Pair<Long, Long>>? {
        fun sampleTime(seconds: Double?): Long? {
            if (seconds == null || !seconds.isFinite() || seconds < 0) return null
            val value = seconds * SAMPLE_RATE
            if (!value.isFinite() || value > totalSamples.toDouble()) return null
            return ceil(value).toLong().takeIf { it in 0..totalSamples }
        }
        val ranges = words.map { word ->
            val start = sampleTime(word.startSeconds) ?: return null
            val end = sampleTime(word.endSeconds) ?: return null
            if (end <= start) return null
            start to end
        }
        return ranges.takeIf { it.zipWithNext().none { (first, second) -> second.first < first.second } }
    }

    private fun normalized(value: String): List<String> = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[\\p{P}\\p{S}\\p{Z}\\p{Cc}\\p{Cf}]+"), " ").trim().split(Regex("\\s+"))

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SAMPLES = SAMPLE_RATE * 10
        const val POST_PADDING_SAMPLES = SAMPLE_RATE * 120 / 1000
        const val PREFIX_ALIGNMENT_SAMPLES = SAMPLE_RATE * 200 / 1000
    }
}

/** Linear 3:2 upsampling of PCM16 little-endian mono, bounded to the ten-second handoff. */
object WakePcmResampler {
    fun to24k(pcm16: ByteArray): ByteArray? {
        if (pcm16.isEmpty() || pcm16.size % 2 != 0 || pcm16.size > WakePcmRingBuffer.MAX_SAMPLES * 2) return null
        val inputSamples = pcm16.size / 2
        val outputSamples = (inputSamples * 3 + 1) / 2
        val output = ByteArray(outputSamples * 2)
        fun sample(index: Int): Int = (pcm16[index * 2].toInt() and 255) or (pcm16[index * 2 + 1].toInt() shl 8)
        for (index in 0 until outputSamples) {
            val position = index * 2
            val left = (position / 3).coerceAtMost(inputSamples - 1)
            val right = (left + 1).coerceAtMost(inputSamples - 1)
            val fraction = position % 3
            val value = ((sample(left) * (3 - fraction) + sample(right) * fraction) / 3.0).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[index * 2] = value.toByte()
            output[index * 2 + 1] = (value shr 8).toByte()
        }
        return output
    }
}
