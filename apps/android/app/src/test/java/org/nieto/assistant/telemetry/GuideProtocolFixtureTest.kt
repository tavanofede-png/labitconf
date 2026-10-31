package org.nieto.assistant.telemetry

import org.junit.Assert.*
import org.junit.Test
import org.nieto.assistant.flow.*
import org.nieto.assistant.flow.Target
import java.io.File

/** Exports only synthetic closed events so the actual Android engine can be checked against the API graph. */
class GuideProtocolFixtureTest {
    @Test fun actualEngineEmitsGuidedAndSeparatePracticeReportsWithoutVerifiedOutcomes() {
        val events = mutableListOf<GuideEvent>()
        val engine = GuidanceEngine { events.add(it) }
        val box = Box(10, 20, 80, 90)
        fun screen(target: Target, revision: Long) = SafeScreen("com.whatsapp", "2.26.1", mapOf(target to box),
            contextToken = "synthetic-conversation", screenToken = "synthetic-$revision", revision = revision)
        fun finishAttempt() {
            engine.observe(screen(Target.ATTACH, 1)); engine.observe(screen(Target.LOCATION, 2))
            engine.observe(screen(Target.SEND_LOCATION, 3)); engine.confirm()
            engine.observe(screen(Target.ATTACH, 4)); assertTrue(engine.state.reportAvailable)
            engine.reportSent(); assertEquals("self_reported", engine.state.completionSource)
        }
        engine.start(); finishAttempt()
        engine.start(practice = true); finishAttempt()
        engine.start(practice = true); engine.repeat(); finishAttempt()
        assertEquals(1, events.count { it.type == "flow_reported" })
        assertEquals(2, events.count { it.type == "practice_reported" })
        val attempts = events.filter { it.type == "practice_started" }.mapNotNull { it.attemptId }
        assertEquals(2, attempts.toSet().size)
        assertTrue(events.none { it.type in setOf("flow_completed", "autonomy_completed", "practice_completed") })
        val fixture = File("build/reports/guide-telemetry-contract.tsv")
        fixture.parentFile.mkdirs()
        fixture.writeText(events.joinToString("\n") { event ->
            listOf(event.type, event.stepId.orEmpty(), event.attemptId.orEmpty(),
                if (event.type == "flow_reported") "42" else if (event.type == "practice_reported") "17" else "").joinToString("\t")
        })
    }
}
