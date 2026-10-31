package org.nieto.assistant.voice

/** Maps a single AudioTrack's frame position onto the provider item actually heard. */
class AudioPlaybackTimeline {
    data class Checkpoint(val turnId: String, val itemId: String, val contentIndex: Int, val audioEndMs: Long)
    private data class Segment(val itemId: String, val index: Int, val start: Long, var end: Long)
    private var turn: String? = null
    private val segments = mutableListOf<Segment>()
    private var totalFrames = 0L
    val hasAudio get() = segments.isNotEmpty()

    fun start(turnId: String) { clear(); turn = turnId }
    fun append(itemId: String, contentIndex: Int, bytes: Int): Boolean {
        if (turn == null || !itemId.matches(Regex("[A-Za-z0-9_-]{1,128}")) || contentIndex !in 0..10 || bytes <= 0 || bytes % 2 != 0) return false
        val last = segments.lastOrNull()
        if (last?.itemId == itemId && last.index == contentIndex) last.end += bytes / 2
        else {
            if (segments.size >= 8 || segments.any { it.itemId == itemId && it.index == contentIndex }) return false
            segments.add(Segment(itemId, contentIndex, totalFrames, totalFrames + bytes / 2))
        }
        totalFrames += bytes / 2
        return true
    }
    fun checkpoint(playedFrames: Long): Checkpoint? {
        val turnId = turn ?: return null
        val played = playedFrames.coerceIn(0, totalFrames)
        val segment = segments.lastOrNull { played >= it.start } ?: return null
        val localFrames = (played - segment.start).coerceIn(0, segment.end - segment.start)
        return Checkpoint(turnId, segment.itemId, segment.index, localFrames * 1000 / RealtimePolicy.SAMPLE_RATE)
    }
    fun clear() { turn = null; segments.clear(); totalFrames = 0 }
}
