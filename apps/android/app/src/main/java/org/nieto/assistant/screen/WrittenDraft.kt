package org.nieto.assistant.screen

/** Keep a draft until the application accepts it; a late acknowledgement never clears newer writing. */
class WrittenDraft {
    data class Submission internal constructor(val id: Long, val revision: Long, val text: String)
    var value: String = ""; private set
    private var revision = 0L
    private var nextId = 0L
    private var pending: Submission? = null
    val sending get() = pending != null
    fun edit(text: String) {
        val bounded = text.take(1000)
        if (bounded != value) { value = bounded; ++revision }
    }
    fun begin(available: Boolean): Submission? {
        if (!available || sending || value.isBlank()) return null
        return Submission(++nextId, revision, value.trim()).also { pending = it }
    }
    fun complete(submission: Submission, accepted: Boolean) {
        if (pending?.id != submission.id) return
        pending = null
        if (accepted && revision == submission.revision) { value = ""; ++revision }
    }
    fun clear() { pending = null; value = ""; ++revision }
}
