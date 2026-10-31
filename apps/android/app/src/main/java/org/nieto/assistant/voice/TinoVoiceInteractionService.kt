package org.nieto.assistant.voice

import android.service.voice.VoiceInteractionService
import org.nieto.assistant.NietoApp

/** Android binds the assistant selected by the user, including after a restart/unlock. */
class TinoVoiceInteractionService : VoiceInteractionService() {
    private val app get() = application as NietoApp
    override fun onReady() {
        super.onReady()
        app.restoreWakeListening()
    }
    override fun onShutdown() {
        app.stopWakeListening(clearPreference = false)
        super.onShutdown()
    }
}
