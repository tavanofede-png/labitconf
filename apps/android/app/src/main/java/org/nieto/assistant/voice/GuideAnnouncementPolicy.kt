package org.nieto.assistant.voice

/** Notice takes priority over a now-inapplicable instruction. Clearing a notice re-announces the safe step. */
class GuideAnnouncementPolicy {
    private var previous: String? = null
    fun reset() { previous = null }
    fun next(stage: String, instruction: String, notice: String?, force: Boolean = false): String? {
        val text = notice?.takeIf { it.isNotBlank() } ?: instruction
        val key = "$stage|${notice.orEmpty()}|$instruction"
        val changed = previous != key
        previous = key
        return text.takeIf { it.isNotBlank() && (force || changed) }
    }
}
