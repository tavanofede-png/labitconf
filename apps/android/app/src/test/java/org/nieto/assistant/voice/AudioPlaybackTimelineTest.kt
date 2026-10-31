package org.nieto.assistant.voice

import org.junit.Assert.*
import org.junit.Test

class AudioPlaybackTimelineTest {
    @Test fun `interruption uses played frames rather than audio received from network`() {
        val timeline = AudioPlaybackTimeline()
        timeline.start("turn")
        assertTrue(timeline.append("item-one", 0, 96_000))
        assertEquals(500L, timeline.checkpoint(12_000)!!.audioEndMs)
        assertEquals(0L, timeline.checkpoint(0)!!.audioEndMs)
        assertEquals(2000L, timeline.checkpoint(Long.MAX_VALUE)!!.audioEndMs)
    }

    @Test fun `a tool continuation maps playback to the item actually being heard`() {
        val timeline = AudioPlaybackTimeline()
        timeline.start("turn")
        timeline.append("before-tool", 0, 48_000)
        timeline.append("after-tool", 0, 48_000)
        val first = timeline.checkpoint(12_000)!!
        assertEquals("before-tool", first.itemId)
        assertEquals(500L, first.audioEndMs)
        val second = timeline.checkpoint(36_000)!!
        assertEquals("after-tool", second.itemId)
        assertEquals(500L, second.audioEndMs)
        assertEquals(0L, timeline.checkpoint(24_000)!!.audioEndMs)
    }

    @Test fun `retired audio cannot supply a checkpoint for a newer turn`() {
        val timeline = AudioPlaybackTimeline()
        timeline.start("old")
        timeline.append("item", 0, 4800)
        timeline.clear()
        assertNull(timeline.checkpoint(200))
        timeline.start("new")
        assertNull(timeline.checkpoint(200))
        assertFalse(timeline.append("not a provider id", 0, 4800))
        assertFalse(timeline.append("item", 0, 3))
        assertTrue(timeline.append("item", 0, 4800))
        assertEquals("new", timeline.checkpoint(200)!!.turnId)
    }
}
