package org.nieto.assistant.voice

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer

/** Plays the model's original audio in memory, without re-synthesizing it or writing a file. */
class NaturalSpeechPlayer(private val onActive: (Boolean) -> Unit, private val onFailure: () -> Unit) {
    private var player: MediaPlayer? = null
    private var source: MemoryAudio? = null

    fun play(encoded: String, mimeType: String) {
        stop()
        try {
            val audio = MemoryAudio(RealtimeSpeechAudio.decode(encoded, mimeType))
            source = audio
            val current = MediaPlayer()
            player = current
            onActive(true)
            current.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            current.setDataSource(audio)
            current.setOnPreparedListener { prepared ->
                if (player === prepared) runCatching { prepared.start() }.onFailure { stop(); onFailure() }
            }
            current.setOnCompletionListener { completed -> if (player === completed) stop() }
            current.setOnErrorListener { failed, _, _ -> if (player === failed) { stop(); onFailure() }; true }
            current.prepareAsync()
        } catch (_: Exception) { stop(); onFailure() }
    }

    fun stop() {
        val previous = player
        player = null
        runCatching { previous?.setOnPreparedListener(null); previous?.setOnCompletionListener(null); previous?.setOnErrorListener(null) }
        runCatching { previous?.release() }
        source?.close(); source = null
        onActive(false)
    }

    private class MemoryAudio(private val bytes: ByteArray) : MediaDataSource() {
        private var closed = false
        @Synchronized override fun getSize(): Long = if (closed) 0 else bytes.size.toLong()
        @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size && size <= buffer.size - offset)
            if (closed || position >= bytes.size) return -1
            val count = minOf(size, bytes.size - position.toInt())
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }
        @Synchronized override fun close() { closed = true; bytes.fill(0) }
    }
}
