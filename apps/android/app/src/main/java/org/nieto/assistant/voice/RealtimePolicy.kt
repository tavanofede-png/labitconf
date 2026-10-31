package org.nieto.assistant.voice

import java.net.URI
import java.util.Base64

/** The public NIETO wire format, deliberately independent of Android and OpenAI events. */
object RealtimePolicy {
    const val MODEL = "gpt-realtime-2.1-mini"
    const val SAMPLE_RATE = 24_000
    const val CHANNELS = 1
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2
    const val CHUNK_BYTES = 4_800
    const val MAX_INPUT_SECONDS = 45
    const val MIN_INPUT_BYTES = 4_800
    const val MAX_INPUT_BYTES = BYTES_PER_SECOND * MAX_INPUT_SECONDS
    const val MAX_OUTPUT_BYTES = BYTES_PER_SECOND * 90
    const val MAX_SOCKET_QUEUE_BYTES = 1_000_000L // One bounded JPEG context plus PCM; never an unbounded audio backlog.

    fun socketUrl(baseUrl: String, debug: Boolean): String {
        val uri = URI(baseUrl.trim().trimEnd('/'))
        require(uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.path in listOf("", "/") && (uri.scheme == "https" || (debug && uri.scheme == "http"))) {
            "La conexión de voz necesita una dirección HTTPS válida; HTTP solo se permite en depuración."
        }
        return URI(if (uri.scheme == "https") "wss" else "ws", null, uri.host, uri.port,
            "/api/assistant/realtime", null, null).toString()
    }

    fun encodeInput(bytes: ByteArray, length: Int): String {
        require(length in 2..12_000 && length <= bytes.size && length % 2 == 0) { "Bloque PCM inválido." }
        return Base64.getEncoder().encodeToString(bytes.copyOf(length))
    }

    fun decodeOutput(encoded: String): ByteArray {
        require(encoded.length <= 160_000) { "El bloque de audio es demasiado grande." }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.isNotEmpty() && bytes.size <= 120_000 && bytes.size % 2 == 0) { "Audio PCM inválido." }
        return bytes
    }
}

/** A cancellation invalidates every previous response, including delayed output from the network. */
class RealtimeTurnGuard {
    private var current: String? = null
    private val retired = linkedSetOf<String>()
    fun begin(turnId: String): Boolean {
        if (turnId.isBlank() || turnId.length > 128 || turnId in retired) return false
        if (turnId == current) return false
        current?.let { retire(it) }
        current = turnId
        return true
    }
    fun accepts(turnId: String): Boolean = current != null && current == turnId && turnId !in retired
    fun cancel() { current?.let { retire(it) }; current = null }
    private fun retire(turnId: String) {
        retired.add(turnId)
        while (retired.size > 64) retired.remove(retired.first())
    }
}
