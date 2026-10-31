package org.nieto.assistant.screen

import org.nieto.assistant.flow.Stage

object AssistanceModePolicy {
    fun observeLocal(remoteEnabled: Boolean, localConsent: Boolean, stage: Stage): Boolean =
        !remoteEnabled && localConsent && stage !in listOf(Stage.IDLE, Stage.COMPLETE, Stage.CANCELLED)
}
