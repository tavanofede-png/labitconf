package org.nieto.assistant.flow

import org.junit.Assert.*
import org.junit.Test

@Suppress("DEPRECATION")
class GuidanceEngineTest {
    private val box = Box(10, 20, 60, 70)
    private fun screen(target: Target, sent: Int = 0, context: String? = "chat-a", revision: Long = 0,
        version: String = "2.26.11", scrolled: Boolean = false) = SafeScreen("com.whatsapp", version,
        targets = mapOf(target to box), outgoingLocations = sent, contextToken = context,
        screenToken = target.name, revision = revision, scrolled = scrolled)
    private fun reachConfirm(engine: GuidanceEngine) {
        engine.observe(screen(Target.ATTACH))
        engine.observe(screen(Target.LOCATION))
        engine.observe(screen(Target.SEND_LOCATION))
        assertEquals(Stage.CONFIRM, engine.state.stage)
        assertNull(engine.state.target)
    }
    private fun reachReport(engine: GuidanceEngine) {
        reachConfirm(engine)
        engine.confirm()
        engine.observe(screen(Target.ATTACH))
        assertEquals(Stage.VERIFY, engine.state.stage)
        assertTrue(engine.state.reportAvailable)
    }
    private fun guidedReport(engine: GuidanceEngine) {
        engine.start(); reachReport(engine); engine.reportSent()
    }
    private fun assertNoVerifiedResult(events: List<GuideEvent>) {
        assertFalse(events.any { it.type in listOf("flow_completed", "practice_completed", "autonomy_completed") })
    }

    @Test fun `visible old message counts never complete or prove a new send`() {
        val events = mutableListOf<GuideEvent>()
        val engine = GuidanceEngine(events::add)
        engine.start(); reachConfirm(engine); engine.confirm()
        for (count in listOf(0, 1, 40, 2, 999)) {
            engine.observe(screen(Target.ATTACH, sent = count))
            assertEquals(Stage.VERIFY, engine.state.stage)
            assertFalse(engine.state.completed)
        }
        assertNoVerifiedResult(events)
        assertFalse(events.any { it.type == "flow_reported" })
    }

    @Test fun `only explicit report closes and reports remain unverified`() {
        val events = mutableListOf<GuideEvent>()
        val engine = GuidanceEngine(events::add)
        engine.start(); engine.reportSent(); assertFalse(engine.state.completed)
        reachReport(engine)
        assertFalse(engine.state.completed)
        engine.reportSent(); engine.reportSent()
        assertTrue(engine.state.completed)
        assertEquals("self_reported", engine.state.completionSource)
        assertEquals(1, events.count { it.type == "flow_reported" })
        assertTrue(engine.state.instruction.contains("No pude comprobar"))
        assertNoVerifiedResult(events)
        assertEquals(listOf("open_chat", "attach", "location", "confirm", "share", "verify"),
            events.filter { it.type == "step_completed" }.map { it.stepId })
    }

    @Test fun `switching chats with more old locations invalidates outcome and confirmation`() {
        val events = mutableListOf<GuideEvent>()
        val engine = GuidanceEngine(events::add)
        engine.start(); reachConfirm(engine); engine.confirm()
        engine.observe(screen(Target.ATTACH, sent = 100, context = "chat-b"))
        engine.reportSent()
        assertEquals(Stage.ATTACH, engine.state.stage)
        assertFalse(engine.state.completed)
        assertFalse(engine.state.reportAvailable)
        engine.observe(screen(Target.LOCATION, context = "chat-b"))
        engine.observe(screen(Target.SEND_LOCATION, context = "chat-b"))
        assertEquals(Stage.CONFIRM, engine.state.stage)
        assertNull(engine.state.target)
        assertNoVerifiedResult(events)
        assertFalse(events.any { it.type == "flow_reported" })
    }

    @Test fun `scrolling old history removes report and cannot count as sending`() {
        val engine = GuidanceEngine()
        engine.start(); reachReport(engine)
        engine.observe(screen(Target.ATTACH, sent = 8, revision = 10, scrolled = true))
        engine.reportSent()
        assertEquals(Stage.ATTACH, engine.state.stage)
        assertFalse(engine.state.reportAvailable)
        assertFalse(engine.state.completed)
    }

    @Test fun `foreign sensitive unsupported and unknown screens revoke authorization`() {
        val invalidScreens = listOf(screen(Target.ATTACH).copy(packageName = "bank.app"),
            screen(Target.SEND_LOCATION).copy(sensitive = true),
            screen(Target.SEND_LOCATION, version = "9.99.0"),
            screen(Target.SEND_LOCATION).copy(recognized = false))
        for (invalid in invalidScreens) {
            val engine = GuidanceEngine()
            engine.start(); reachConfirm(engine); engine.confirm()
            engine.observe(invalid)
            assertEquals(Stage.OPEN_CHAT, engine.state.stage)
            assertNull(engine.state.target)
            assertNotNull(engine.state.notice)
            engine.observe(screen(Target.SEND_LOCATION))
            engine.confirm()
            assertEquals(Stage.OPEN_CHAT, engine.state.stage)
            assertNull(engine.state.target)
            engine.observe(screen(Target.ATTACH))
            assertEquals(Stage.ATTACH, engine.state.stage)
        }
    }

    @Test fun `a changed picker revision or control identity needs fresh consent`() {
        for (changed in listOf(screen(Target.SEND_LOCATION, revision = 1),
            screen(Target.SEND_LOCATION).copy(screenToken = "different-window"),
            screen(Target.SEND_LOCATION).copy(targets = mapOf(Target.SEND_LOCATION to Box(20,30,100,90))))) {
            val engine = GuidanceEngine()
            engine.start(); reachConfirm(engine); engine.confirm()
            assertEquals(box, engine.state.target)
            engine.observe(changed)
            assertEquals(Stage.CONFIRM, engine.state.stage)
            assertNull(engine.state.target)
            engine.confirm()
            assertEquals(Stage.SHARE, engine.state.stage)
        }
    }

    @Test fun `recognized backtracking recovers attach and location without restarting`() {
        val engine = GuidanceEngine()
        engine.start()
        engine.observe(screen(Target.ATTACH))
        engine.observe(screen(Target.LOCATION))
        assertEquals(Stage.LOCATION, engine.state.stage)
        engine.observe(screen(Target.ATTACH))
        assertEquals(Stage.ATTACH, engine.state.stage)
        assertEquals(box, engine.state.target)
        engine.lost()
        assertEquals(Stage.ATTACH, engine.state.stage)
        engine.observe(screen(Target.LOCATION))
        engine.observe(screen(Target.SEND_LOCATION))
        engine.confirm()
        engine.observe(screen(Target.LOCATION))
        assertEquals(Stage.LOCATION, engine.state.stage)
        assertEquals(box, engine.state.target)
        engine.observe(screen(Target.SEND_LOCATION))
        assertEquals(Stage.CONFIRM, engine.state.stage)
        assertNull(engine.state.target)
    }

    @Test fun `lost on sensitive send step revokes authorization and reopens confirmation`() {
        val engine = GuidanceEngine()
        engine.start(); reachConfirm(engine); engine.confirm()
        engine.lost()
        assertEquals(Stage.CONFIRM, engine.state.stage)
        assertNull(engine.state.target)
        engine.observe(screen(Target.SEND_LOCATION))
        assertNull(engine.state.target)
        engine.confirm()
        assertEquals(Stage.SHARE, engine.state.stage)
    }

    @Test fun `lost cannot skip the initial conversation and canonical steps`() {
        val engine = GuidanceEngine()
        engine.start()
        engine.observe(screen(Target.SEND_LOCATION))
        engine.lost(); engine.confirm()
        assertEquals(Stage.OPEN_CHAT, engine.state.stage)
        assertNull(engine.state.target)
    }

    @Test fun `practice needs guided report and uses a fresh attempt id`() {
        val events = mutableListOf<GuideEvent>()
        val engine = GuidanceEngine(events::add)
        engine.start(practice = true)
        assertFalse(events.any { it.type == "practice_started" })
        guidedReport(engine)
        events.clear()
        engine.start(practice = true)
        val attempt = engine.state.attemptId
        assertNotNull(attempt)
        engine.observe(screen(Target.ATTACH))
        assertNull(engine.state.target)
        engine.observe(screen(Target.LOCATION))
        engine.observe(screen(Target.SEND_LOCATION)); engine.confirm()
        engine.observe(screen(Target.ATTACH, sent = 100))
        assertFalse(engine.state.completed)
        engine.reportSent()
        assertEquals("self_reported", engine.state.completionSource)
        assertTrue(events.any { it.type == "practice_reported" })
        assertTrue(events.all { it.attemptId == attempt })
        assertNoVerifiedResult(events)
        engine.start(practice = true)
        assertNotEquals(attempt, engine.state.attemptId)
    }

    @Test fun `hinted practice remains reported and cannot claim autonomy`() {
        val events = mutableListOf<GuideEvent>()
        val engine = GuidanceEngine(events::add)
        guidedReport(engine); engine.start(practice = true)
        engine.observe(screen(Target.ATTACH))
        engine.repeat()
        assertTrue(engine.state.assistedPractice)
        assertEquals(box, engine.state.target)
        engine.observe(screen(Target.LOCATION)); engine.lost()
        engine.observe(screen(Target.SEND_LOCATION)); engine.confirm()
        engine.observe(screen(Target.ATTACH)); engine.reportSent()
        assertTrue(engine.state.completed)
        assertTrue(engine.state.assistedPractice)
        assertTrue(events.any { it.type == "practice_reported" })
        assertNoVerifiedResult(events)
    }

    @Test fun `cancelled guides ignore observations reports and lost actions`() {
        val events = mutableListOf<GuideEvent>()
        val engine = GuidanceEngine(events::add)
        engine.start(); reachReport(engine); engine.cancel()
        val count = events.size
        engine.observe(screen(Target.ATTACH, sent = 100)); engine.reportSent(); engine.lost()
        assertEquals(Stage.CANCELLED, engine.state.stage)
        assertFalse(engine.state.completed)
        assertEquals(count, events.size)
    }
}
