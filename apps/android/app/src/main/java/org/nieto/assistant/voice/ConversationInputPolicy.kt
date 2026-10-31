package org.nieto.assistant.voice

/** Ephemeral local endpoint tracking. Silence and an unchanged ASR partial are not user activity. */
class ConversationInputPolicy {
    var startSample: Long? = null; private set
    private var lastPartial = ""

    fun partial(text: String, totalSamples: Long, firstWordSample: Long? = null): Boolean {
        val value = text.trim()
        if (value.isEmpty() || value == "[unk]" || value == lastPartial) return false
        lastPartial = value
        val start = firstWordSample?.takeIf { it in 0 until totalSamples }
            ?: (totalSamples - PRE_ROLL_SAMPLES).coerceAtLeast(0)
        startSample = minOf(startSample ?: start, start)
        return true
    }

    fun finalStart(text: String, totalSamples: Long, firstWordSample: Long?): Long? {
        if (text.isBlank() && startSample == null) return null
        val first = firstWordSample?.takeIf { it in 0 until totalSamples }
        return listOfNotNull(startSample, first).minOrNull()
    }

    fun atLimit(totalSamples: Long): Boolean = startSample?.let {
        totalSamples - it >= WakePcmRingBuffer.MAX_SAMPLES - BLOCK_SAMPLES
    } == true

    companion object {
        const val PRE_ROLL_SAMPLES = WakePcmRingBuffer.SAMPLE_RATE
        const val BLOCK_SAMPLES = 320
    }
}
