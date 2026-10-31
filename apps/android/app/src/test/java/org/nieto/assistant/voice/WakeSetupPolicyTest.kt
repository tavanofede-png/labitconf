package org.nieto.assistant.voice

import org.junit.Assert.*
import org.junit.Test

class WakeSetupPolicyTest {
    private val ready = WakeSetupRequirements(true, true, true, true, true, true)
    @Test fun `a fresh install cannot start a microphone with missing connection or consent`() {
        assertEquals(WakeSetupStep.CONNECTION, WakeSetupPolicy.next(ready.copy(linked = false)))
        assertEquals(WakeSetupStep.CONSENT, WakeSetupPolicy.next(ready.copy(consent = false)))
    }
    @Test fun `runtime grants alone do not bypass assistant selection and accessibility`() {
        var setup = ready.copy(microphone = false, notifications = false, assistant = false, accessibility = false)
        assertEquals(WakeSetupStep.MICROPHONE, WakeSetupPolicy.next(setup))
        setup = setup.copy(microphone = true)
        assertEquals(WakeSetupStep.NOTIFICATIONS, WakeSetupPolicy.next(setup))
        setup = setup.copy(notifications = true)
        assertEquals(WakeSetupStep.ASSISTANT, WakeSetupPolicy.next(setup))
        setup = setup.copy(assistant = true)
        assertEquals(WakeSetupStep.ACCESSIBILITY, WakeSetupPolicy.next(setup))
        assertEquals(WakeSetupStep.READY, WakeSetupPolicy.next(setup.copy(accessibility = true)))
    }
    @Test fun `revoking any prerequisite makes the activation incomplete again`() {
        assertEquals(WakeSetupStep.READY, WakeSetupPolicy.next(ready))
        val incomplete = listOf(ready.copy(linked=false), ready.copy(consent=false), ready.copy(microphone=false),
            ready.copy(notifications=false), ready.copy(assistant=false), ready.copy(accessibility=false))
        incomplete.forEach { assertNotEquals(WakeSetupStep.READY, WakeSetupPolicy.next(it)) }
    }
}
