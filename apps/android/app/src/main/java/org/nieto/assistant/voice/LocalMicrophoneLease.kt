package org.nieto.assistant.voice

import java.util.concurrent.atomic.AtomicReference

/** Wake detection and the system's offline dictation service cannot record concurrently. */
internal object LocalMicrophoneLease {
    private val owner = AtomicReference<String?>(null)
    fun acquire(name: String): Boolean = owner.compareAndSet(null, name)
    fun release(name: String) { owner.compareAndSet(name, null) }
    val occupied: Boolean get() = owner.get() != null
}
