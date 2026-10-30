package org.nieto.assistant.entry

import org.junit.Assert.*
import org.junit.Test

class OnboardingRulesTest {
    @Test fun generatedUsernamesNormalizeAccentsAndRemainValid() {
        assertEquals("jose.perez", suggestedUsername("  José   Pérez  "))
        assertEquals("ab.tino", suggestedUsername("Ab"))
        listOf("", "王", "María del Carmen", "A".repeat(100)).forEach { assertTrue(validUsername(suggestedUsername(it))) }
        assertFalse(validUsername("abc def")); assertFalse(validUsername("ab")); assertFalse(validUsername("a".repeat(31)))
    }
    @Test fun phoneIsRequiredAndAllowsHumanFormatting() {
        listOf("+54 (11) 1234-5678", "01112345678", "12345678").forEach { assertTrue(it, validPhone(it)) }
        listOf("", "123", "+1234567890123456", "call 12345678", "++12345678").forEach { assertFalse(it, validPhone(it)) }
    }
    @Test fun eachRoleCollectsMandatoryIdentityAndVerifiedEmailIsNotRequestedTwice() {
        AccountRole.entries.forEach { role ->
            val fresh = profileStepsFor(role, false)
            assertEquals(listOf(ProfileStep.NAME, ProfileStep.USERNAME, ProfileStep.PHONE), fresh.take(3))
            assertTrue(fresh.contains(ProfileStep.EMAIL))
            assertFalse(profileStepsFor(role, true).contains(ProfileStep.EMAIL))
            assertEquals(ProfileStep.PHOTO, fresh.last())
            assertEquals(role == AccountRole.INSTITUTION, fresh.contains(ProfileStep.ORGANIZATION))
        }
    }
}
