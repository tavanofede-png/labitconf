package org.nieto.assistant.flow

import org.junit.Assert.*
import org.junit.Test

class GuidanceEngineTest {
    private val box = Box(10, 20, 60, 70)
    private fun screen(vararg targets: Target, sent: Int = 0, version: String = "2.26.11") =
        SafeScreen("com.whatsapp", version, targets.associateWith { box }, sent)

    @Test fun `requires real screen transitions explicit confirmation and new outgoing evidence`() {
        val events = mutableListOf<GuideEvent>()
        val engine = GuidanceEngine(events::add)
        engine.start()
        engine.observe(screen(Target.ATTACH, sent = 2))
        assertEquals(Stage.ATTACH, engine.state.stage)
        engine.observe(screen(Target.ATTACH, sent = 2))
        assertEquals(Stage.ATTACH, engine.state.stage)
        engine.observe(screen(Target.LOCATION))
        assertEquals(Stage.LOCATION, engine.state.stage)
        engine.observe(screen(Target.SEND_LOCATION))
        assertEquals(Stage.CONFIRM, engine.state.stage)
        assertNull(engine.state.target)
        engine.observe(screen(Target.ATTACH, sent = 3))
        assertFalse(engine.state.completed)
        engine.observe(screen(Target.SEND_LOCATION))
        engine.confirm()
        assertEquals(Stage.SHARE, engine.state.stage)
        engine.observe(screen(Target.ATTACH, sent = 2))
        assertEquals(Stage.VERIFY, engine.state.stage)
        engine.observe(screen(Target.ATTACH, sent = 3))
        assertTrue(engine.state.completed)
        assertTrue(events.any { it.type == "flow_completed" })
    }

    @Test fun `foreign sensitive and unsupported windows cannot highlight or advance`() {
        val engine = GuidanceEngine()
        engine.start()
        for (screen in listOf(screen(Target.ATTACH).copy(packageName = "bank.app"),
            screen(Target.ATTACH).copy(sensitive = true), screen(Target.ATTACH, version = "9.99.0"))) {
            engine.observe(screen)
            assertEquals(Stage.OPEN_CHAT, engine.state.stage)
            assertNull(engine.state.target)
            assertNotNull(engine.state.notice)
        }
    }

    @Test fun `practice loses autonomy credit after requesting a hint`() {
        val events = mutableListOf<GuideEvent>()
        val engine = GuidanceEngine(events::add)
        engine.start(practice = true)
        engine.observe(screen(Target.ATTACH))
        assertNull(engine.state.target)
        engine.lost()
        engine.observe(screen(Target.LOCATION))
        engine.observe(screen(Target.SEND_LOCATION))
        engine.confirm()
        engine.observe(screen(Target.ATTACH, sent = 1))
        assertTrue(engine.state.completed)
        assertFalse(events.any { it.type == "autonomy_completed" })
    }

    @Test fun `unassisted practice is verified and cancelled guides ignore observations`() {
        val events = mutableListOf<GuideEvent>()
        val engine = GuidanceEngine(events::add)
        engine.start(practice = true)
        engine.observe(screen(Target.ATTACH))
        engine.observe(screen(Target.LOCATION))
        engine.observe(screen(Target.SEND_LOCATION))
        engine.confirm()
        engine.observe(screen(Target.ATTACH, sent = 1))
        assertTrue(events.any { it.type == "autonomy_completed" })
        engine.start()
        engine.cancel()
        engine.observe(screen(Target.ATTACH))
        assertEquals(Stage.CANCELLED, engine.state.stage)
    }

    @Test fun `requesting repeat in practice restores actionable local hints`() {
        val engine = GuidanceEngine()
        engine.start(practice = true)
        engine.observe(screen(Target.ATTACH))
        assertNull(engine.state.target)
        engine.repeat()
        assertTrue(engine.state.assistedPractice)
        assertTrue(engine.state.instruction.contains("Adjuntar"))
        assertEquals(box, engine.state.target)
    }
}
