package org.nieto.assistant.voice

enum class WakeSetupStep { CONNECTION, CONSENT, MICROPHONE, NOTIFICATIONS, ASSISTANT, ACCESSIBILITY, READY }

data class WakeSetupRequirements(
    val linked: Boolean,
    val consent: Boolean,
    val microphone: Boolean,
    val notifications: Boolean,
    val assistant: Boolean,
    val accessibility: Boolean,
)

/** A foreground microphone must only start after the complete setup, never midway through it. */
object WakeSetupPolicy {
    fun next(value: WakeSetupRequirements): WakeSetupStep = when {
        !value.linked -> WakeSetupStep.CONNECTION
        !value.consent -> WakeSetupStep.CONSENT
        !value.microphone -> WakeSetupStep.MICROPHONE
        !value.notifications -> WakeSetupStep.NOTIFICATIONS
        !value.assistant -> WakeSetupStep.ASSISTANT
        !value.accessibility -> WakeSetupStep.ACCESSIBILITY
        else -> WakeSetupStep.READY
    }
}
