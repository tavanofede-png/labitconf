package org.nieto.assistant.screen

import org.junit.Assert.*
import org.junit.Test

class WrittenDraftTest {
    @Test fun `a busy or rejected send preserves the query`() {
        val draft = WrittenDraft()
        draft.edit("Quiero aumentar la letra")
        assertNull(draft.begin(available = false))
        assertEquals("Quiero aumentar la letra", draft.value)
        val sent = draft.begin(available = true)!!
        assertTrue(draft.sending)
        assertNull(draft.begin(available = true))
        draft.complete(sent, accepted = false)
        assertFalse(draft.sending)
        assertEquals("Quiero aumentar la letra", draft.value)
    }

    @Test fun `acceptance clears only the exact submitted revision`() {
        val draft = WrittenDraft()
        draft.edit("Primera pregunta")
        val first = draft.begin(true)!!
        draft.edit("Segunda pregunta")
        draft.complete(first, true)
        assertEquals("Segunda pregunta", draft.value)
        val second = draft.begin(true)!!
        draft.complete(first, true)
        assertTrue(draft.sending)
        draft.complete(second, true)
        assertEquals("", draft.value)
        assertFalse(draft.sending)
    }

    @Test fun `closing assistance invalidates an outstanding acknowledgement`() {
        val draft = WrittenDraft()
        draft.edit("Anterior")
        val old = draft.begin(true)!!
        draft.clear()
        draft.edit("Nueva tarea")
        val current = draft.begin(true)!!
        draft.complete(old, true)
        assertEquals("Nueva tarea", draft.value)
        assertTrue(draft.sending)
        draft.complete(current, true)
        assertEquals("", draft.value)
    }
}
