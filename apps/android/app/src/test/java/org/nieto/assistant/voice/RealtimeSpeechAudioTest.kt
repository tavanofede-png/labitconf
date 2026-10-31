package org.nieto.assistant.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class RealtimeSpeechAudioTest {
    private fun wave(size: Int = 4800) = ByteArray(44 + size).also { bytes ->
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int, value: String) { value.forEachIndexed { index, char -> bytes[at + index] = char.code.toByte() } }
        tag(0, "RIFF"); tag(8, "WAVE"); tag(12, "fmt "); tag(36, "data")
        buffer.putInt(4, bytes.size - 8); buffer.putInt(16, 16); buffer.putShort(20, 1); buffer.putShort(22, 1)
        buffer.putInt(24, 24000); buffer.putInt(28, 48000); buffer.putShort(32, 2); buffer.putShort(34, 16); buffer.putInt(40, size)
        for (index in 44 until bytes.size) bytes[index] = (index % 127).toByte()
    }
    private fun encoded(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test fun `original speech samples and WAV metadata are preserved without resynthesis`() {
        val input = wave()
        assertArrayEquals(input, RealtimeSpeechAudio.decode(encoded(input), "audio/wav"))
    }
    @Test fun `only the bounded authenticated PCM WAV contract is accepted`() {
        val original = wave()
        for (offset in listOf(0, 4, 8, 12, 16, 20, 22, 24, 28, 32, 34, 36, 40)) {
            val invalid = original.copyOf(); invalid[offset] = (invalid[offset].toInt() xor 1).toByte()
            assertThrows(IllegalArgumentException::class.java) { RealtimeSpeechAudio.decode(encoded(invalid), "audio/wav") }
        }
        assertThrows(IllegalArgumentException::class.java) { RealtimeSpeechAudio.decode(encoded(original), "audio/mp3") }
        assertThrows(IllegalArgumentException::class.java) { RealtimeSpeechAudio.decode(encoded(wave(0)), "audio/wav") }
        assertThrows(IllegalArgumentException::class.java) { RealtimeSpeechAudio.decode(encoded(wave(3)), "audio/wav") }
    }
    @Test fun `malformed base64 truncation oversized audio and invalid lengths fail before playback`() {
        for (input in listOf("", "%%%", encoded(ByteArray(43)), encoded(wave().dropLast(2).toByteArray()), encoded(wave(RealtimeSpeechAudio.MAX_BYTES)))) {
            assertThrows(IllegalArgumentException::class.java) { RealtimeSpeechAudio.decode(input, "audio/wav") }
        }
        val longest = wave(RealtimeSpeechAudio.MAX_BYTES - 44)
        assertArrayEquals(longest, RealtimeSpeechAudio.decode(encoded(longest), "audio/wav"))
    }
}
