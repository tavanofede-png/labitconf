package org.nieto.assistant.voice

import org.junit.Assert.*
import org.junit.Test

class GuideAnnouncementPolicyTest {
    @Test fun blockingNoticeOverridesOldInstructionAndRepeatedEventsStaySilent() {
        val policy = GuideAnnouncementPolicy()
        assertEquals("Tocá Ubicación", policy.next("LOCATION", "Tocá Ubicación", null))
        assertEquals("Saliste de WhatsApp", policy.next("LOCATION", "Tocá Ubicación", "Saliste de WhatsApp"))
        assertNull(policy.next("LOCATION", "Tocá Ubicación", "Saliste de WhatsApp"))
        assertEquals("Pantalla sensible", policy.next("LOCATION", "Tocá Ubicación", "Pantalla sensible"))
    }
    @Test fun returningToSafeScreenAnnouncesTheCurrentStepAndExplicitRepeatIsAllowed() {
        val policy = GuideAnnouncementPolicy()
        policy.next("ATTACH", "Tocá Adjuntar", "Pantalla incompatible")
        assertEquals("Tocá Adjuntar", policy.next("ATTACH", "Tocá Adjuntar", null))
        assertNull(policy.next("ATTACH", "Tocá Adjuntar", null))
        assertEquals("Tocá Adjuntar", policy.next("ATTACH", "Tocá Adjuntar", null, force = true))
    }
    @Test fun relevantStageChangeAndManualReportAreSpokenWithoutClaimingVerification() {
        val policy = GuideAnnouncementPolicy()
        policy.next("VERIFY", "Revisá si se envió", null)
        val report = "Registré tu reporte. No pude verificar un mensaje nuevo ni acreditar autonomía."
        assertEquals(report, policy.next("COMPLETE", report, null))
        assertNull(policy.next("COMPLETE", report, null))
        assertEquals("Guía terminada", policy.next("CANCELLED", "Guía terminada", null))
    }
    @Test fun switchingAwayAndStartingAnotherGuideDoesNotSuppressItsFirstInstruction() {
        val policy = GuideAnnouncementPolicy()
        assertEquals("Abrí tu conversación", policy.next("OPEN_CHAT", "Abrí tu conversación", null))
        assertNull(policy.next("OPEN_CHAT", "Abrí tu conversación", null))
        policy.reset()
        assertEquals("Abrí tu conversación", policy.next("OPEN_CHAT", "Abrí tu conversación", null))
    }
}
