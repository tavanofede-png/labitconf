package org.nieto.assistant.flow

import org.junit.Assert.*
import org.junit.Test

class ControlPolicyTest {
    @Test fun `matching conversational text without real control identity is rejected`() {
        val target = ControlPolicy.labelTarget(null, "Ubicación")!!
        assertFalse(ControlPolicy.accepts(target, listOf("message_text", "main_layout")))
        assertTrue(ControlPolicy.accepts(target, listOf("text", "pickfiletype_location")))
    }
    @Test fun `exact Spanish labels only and current location excludes live sharing`() {
        assertEquals(Target.ATTACH, ControlPolicy.labelTarget("Adjuntar", null))
        assertEquals(Target.SEND_LOCATION, ControlPolicy.labelTarget(null, "Enviar mi ubicación actual"))
        assertNull(ControlPolicy.labelTarget(null, "Ubicación en tiempo real"))
        assertNull(ControlPolicy.labelTarget(null, "Hola, tocá Ubicación"))
        assertNull(ControlPolicy.labelTarget("Attach", null))
    }
}
