package org.nieto.assistant.entry

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BirthDateTest {
    @Test fun validatesCalendarDatesWithoutAcceptingFutureBirthdays() {
        val today = LocalDate.of(2026, 10, 30)
        assertTrue(validBirthDate("2000-02-29", today))
        assertFalse(validBirthDate("2001-02-29", today))
        assertFalse(validBirthDate("2026-10-31", today))
        assertFalse(validBirthDate("", today))
    }
}
