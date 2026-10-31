package org.nieto.assistant.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Bounded canonical WAV returned by the authenticated Realtime HTTP endpoint. */
object RealtimeSpeechAudio {
    const val MAX_BYTES = 24_000 * 2 * 45 + 44

    fun decode(encoded: String, mimeType: String): ByteArray {
        require(mimeType == "audio/wav") { "Formato de voz no admitido" }
        require(encoded.isNotEmpty() && encoded.length <= ((MAX_BYTES + 2) / 3) * 4) { "Audio fuera de los límites" }
        val bytes = Base64.getDecoder().decode(encoded)
        try {
            require(bytes.size in 46..MAX_BYTES)
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun tag(at: Int, value: String) = value.indices.all { bytes[at + it] == value[it].code.toByte() }
            require(tag(0, "RIFF") && tag(8, "WAVE") && tag(12, "fmt ") && tag(36, "data"))
            require(data.getInt(4) == bytes.size - 8 && data.getInt(16) == 16)
            require(data.getShort(20).toInt() == 1 && data.getShort(22).toInt() == 1)
            require(data.getInt(24) == 24_000 && data.getInt(28) == 48_000)
            require(data.getShort(32).toInt() == 2 && data.getShort(34).toInt() == 16)
            require(data.getInt(40) == bytes.size - 44 && (bytes.size - 44) % 2 == 0)
            return bytes
        } catch (error: Exception) { bytes.fill(0); throw error }
    }
}
