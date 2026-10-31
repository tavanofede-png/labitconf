package org.nieto.assistant.voice

import org.junit.Assert.*
import org.junit.Test

class WakePcmRingBufferTest {
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).apply { samples.forEachIndexed { index, value ->
        this[index * 2] = value.toByte(); this[index * 2 + 1] = (value shr 8).toByte()
    } }
    private fun shorts(bytes: ByteArray) = (bytes.indices step 2).map { index -> ((bytes[index].toInt() and 255) or (bytes[index + 1].toInt() shl 8)).toShort().toInt() }
    private fun words(start: Double = 1.0, end: Double = 2.0, inline: Boolean = true): List<WakeTimedWord> {
        val unit = (end - start) / if (inline) 4 else 2
        return listOf(WakeTimedWord("hola", 0.96, start, start + unit), WakeTimedWord("tino", 0.95, start + unit, start + unit * 2)) +
            if (inline) listOf(WakeTimedWord("estas", 0.4, start + unit * 2, start + unit * 3), WakeTimedWord("ahi", null, start + unit * 3, end)) else emptyList()
    }

    @Test fun `inline extraction never includes pre-wake samples and contains the real following query`() {
        val ring = WakePcmRingBuffer()
        val data = ShortArray(48_000) { if (it < 16_000) (-12_345).toShort() else 4321.toShort() }
        assertTrue(ring.append(data, data.size))
        val result = ring.extractInvocation(true, "hola tino estas ahi", words())!!
        assertEquals(16_000L, result.startSample16k)
        assertEquals(32_000L + WakePcmRingBuffer.POST_PADDING_SAMPLES, result.endSample16k)
        assertTrue(shorts(result.pcm24k).all { it == 4321 })
        assertEquals(((result.endSample16k - result.startSample16k) * 3 / 2).toInt() * 2, result.pcm24k.size)
    }

    @Test fun `Hola Tino alone is forwarded as real verified audio rather than fabricated text`() {
        val ring = WakePcmRingBuffer()
        ring.append(ShortArray(32_000) { 750 }, 32_000)
        val result = ring.extractInvocation(true, "¡Holá, Tíno!", words(0.5, 1.0, inline = false))!!
        assertEquals(8_000L, result.startSample16k)
        assertTrue(result.pcm24k.isNotEmpty())
        assertTrue(shorts(result.pcm24k).all { it == 750 })
    }

    @Test fun `the ring follows cumulative recognizer time across many callbacks and wraparound`() {
        val ring = WakePcmRingBuffer()
        repeat(12) { second -> ring.append(ShortArray(16_000) { second.toShort() }, 16_000) }
        assertEquals(192_000L, ring.totalSamples)
        assertEquals(32_000L, ring.oldestSample)
        val result = ring.extractInvocation(true, "hola tino estas ahi", words(10.0, 11.0))!!
        assertEquals(160_000L, result.startSample16k)
        val values = shorts(result.pcm24k)
        assertEquals(10, values.first())
        assertEquals(11, values.last())
    }

    @Test fun `overwritten wakes are rejected without returning a truncated query`() {
        val ring = WakePcmRingBuffer()
        ring.append(ShortArray(192_000) { 123 }, 192_000)
        assertEquals(32_000L, ring.oldestSample)
        assertNull(ring.extractInvocation(true, "hola tino estas ahi", words(1.0, 3.0)))
        assertNotNull(ring.extractInvocation(true, "hola tino estas ahi", words(2.0, 3.0)))
    }

    @Test fun `partial non-prefix and low-confidence guesses never export captured audio`() {
        val ring = WakePcmRingBuffer()
        ring.append(ShortArray(48_000), 48_000)
        assertNull(ring.extractInvocation(false, "hola tino estas ahi", words()))
        assertNull(ring.extractInvocation(true, "le dije hola tino", words()))
        assertNull(ring.extractInvocation(true, "[unk] hola tino estas ahi", listOf(WakeTimedWord("[unk]", 1.0, 0.5, 0.9)) + words()))
        assertNull(ring.extractInvocation(true, "hola tino estas ahi", words().mapIndexed { index, word -> if (index == 0) word.copy(confidence = 0.849) else word }))
        assertNull(ring.extractInvocation(true, "hola tino estas ahi", words().mapIndexed { index, word -> if (index == 1) word.copy(confidence = Double.NaN) else word }))
        assertNull(ring.extractInvocation(true, "hola tino estas ahi", null))
    }

    @Test fun `missing non-finite overlapping reversed and future timestamps fail closed`() {
        val good = words()
        val invalid = listOf(
            good.mapIndexed { index, word -> if (index == 0) word.copy(startSeconds = null) else word },
            good.mapIndexed { index, word -> if (index == 0) word.copy(startSeconds = -0.1) else word },
            good.mapIndexed { index, word -> if (index == 0) word.copy(startSeconds = Double.NaN) else word },
            good.mapIndexed { index, word -> if (index == 1) word.copy(endSeconds = Double.POSITIVE_INFINITY) else word },
            good.mapIndexed { index, word -> if (index == 1) word.copy(startSeconds = 1.1) else word },
            good.mapIndexed { index, word -> if (index == 2) word.copy(endSeconds = word.startSeconds) else word },
            good.mapIndexed { index, word -> if (index == 3) word.copy(endSeconds = 4.0) else word }
        )
        for (value in invalid) {
            val ring = WakePcmRingBuffer(); ring.append(ShortArray(48_000), 48_000)
            assertNull(ring.extractInvocation(true, "hola tino estas ahi", value))
        }
    }

    @Test fun `post-padding is bounded by captured samples and does not invent audio at the end`() {
        val ring = WakePcmRingBuffer()
        ring.append(ShortArray(32_000) { 320 }, 32_000)
        val result = ring.extractInvocation(true, "hola tino estas ahi", words())!!
        assertEquals(32_000L, result.endSample16k)
        assertEquals(48_000, result.pcm24k.size)
    }

    @Test fun `duplicate overlapping callbacks cannot replay an utterance and clear starts a new clock`() {
        val ring = WakePcmRingBuffer()
        ring.append(ShortArray(64_000), 64_000)
        assertNotNull(ring.extractInvocation(true, "hola tino estas ahi", words()))
        assertNull(ring.extractInvocation(true, "hola tino estas ahi", words()))
        assertNull(ring.extractInvocation(true, "hola tino estas ahi", words(1.5, 2.5)))
        assertNotNull(ring.extractInvocation(true, "hola tino estas ahi", words(2.5, 3.0)))
        ring.clear()
        assertEquals(0L, ring.totalSamples); assertEquals(0L, ring.oldestSample)
        assertNull(ring.extractInvocation(true, "hola tino estas ahi", words()))
        ring.append(ShortArray(48_000), 48_000)
        assertNotNull(ring.extractInvocation(true, "hola tino estas ahi", words()))
    }

    @Test fun `unaligned or invalid PCM callback lengths do not advance the recognizer clock`() {
        val ring = WakePcmRingBuffer()
        assertFalse(ring.appendPcm16(byteArrayOf(1, 2, 3)))
        assertFalse(ring.appendPcm16(pcm(123), 3))
        assertFalse(ring.append(shortArrayOf(1), 2))
        assertFalse(ring.append(shortArrayOf(1), 0))
        assertEquals(0L, ring.totalSamples)
        assertTrue(ring.appendPcm16(pcm(123, -321), 4))
        assertEquals(2L, ring.totalSamples)
        assertThrows(IllegalArgumentException::class.java) { WakePcmRingBuffer(0) }
        assertThrows(IllegalArgumentException::class.java) { WakePcmRingBuffer(WakePcmRingBuffer.MAX_SAMPLES + 1) }
    }

    @Test fun `upsampling preserves PCM alignment endpoint values amplitude and the exact three-to-two rate`() {
        assertEquals(listOf(0, 2000, 3000), shorts(WakePcmResampler.to24k(pcm(0, 3000))!!))
        assertEquals(listOf(-3000, -1000, 0), shorts(WakePcmResampler.to24k(pcm(-3000, 0))!!))
        assertEquals(listOf(32767, 32767), shorts(WakePcmResampler.to24k(pcm(32767))!!))
        assertEquals(listOf(-32768, -32768), shorts(WakePcmResampler.to24k(pcm(-32768))!!))
        assertEquals(480_000, WakePcmResampler.to24k(ByteArray(320_000))!!.size)
        assertNull(WakePcmResampler.to24k(byteArrayOf(1)))
        assertNull(WakePcmResampler.to24k(byteArrayOf()))
        assertNull(WakePcmResampler.to24k(ByteArray(320_002)))
    }
}
