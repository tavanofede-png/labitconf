package org.nieto.assistant.presence

/** Playback and recording each release only their own pause of the wake listener. */
class AudioUseGate {
    private val owners=mutableSetOf<String>()
    val active: Boolean get()=owners.isNotEmpty()
    fun acquire(owner: String) { owners.add(owner) }
    fun release(owner: String) { owners.remove(owner) }
}
