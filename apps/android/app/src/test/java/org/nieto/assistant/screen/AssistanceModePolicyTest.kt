package org.nieto.assistant.screen

import org.junit.Assert.*
import org.junit.Test
import org.nieto.assistant.flow.GuidanceEngine
import org.nieto.assistant.flow.SafeScreen
import org.nieto.assistant.flow.Stage

class AssistanceModePolicyTest {
    @Test fun `switching to remote detaches local observations and their telemetry`() {
        val events = mutableListOf<String>()
        val engine = GuidanceEngine { events.add(it.type) }
        engine.start()
        assertTrue(AssistanceModePolicy.observeLocal(false, true, engine.state.stage))
        engine.cancel()
        val afterCancellation = events.toList()
        if (AssistanceModePolicy.observeLocal(true, true, engine.state.stage)) {
            engine.observe(SafeScreen("com.other", ""))
        }
        assertEquals(afterCancellation, events)
        assertEquals(1, events.count { it == "cancelled" })
        assertEquals(Stage.CANCELLED, engine.state.stage)
        assertNull(engine.state.notice)
    }

    @Test fun `remote mode takes precedence even over a stale active local stage`() {
        Stage.entries.forEach { stage ->
            assertFalse(AssistanceModePolicy.observeLocal(true, true, stage))
            assertFalse(AssistanceModePolicy.observeLocal(false, false, stage))
        }
        assertTrue(AssistanceModePolicy.observeLocal(false, true, Stage.ATTACH))
        listOf(Stage.IDLE, Stage.CANCELLED, Stage.COMPLETE).forEach { assertFalse(AssistanceModePolicy.observeLocal(false, true, it)) }
    }
}
