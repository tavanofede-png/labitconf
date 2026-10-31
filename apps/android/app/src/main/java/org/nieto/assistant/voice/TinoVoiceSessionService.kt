package org.nieto.assistant.voice

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import org.nieto.assistant.NietoApp

/** The system assistant gesture uses the same voice path without adding a chat window. */
class TinoVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = TinoVoiceSession(this)
}

private class TinoVoiceSession(context: Context) : VoiceInteractionSession(context) {
    override fun onCreate() { super.onCreate(); setUiEnabled(false) }
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val app = context.applicationContext as NietoApp
        app.restoreWakeListening()
        // WakeWordService owns microphone handoff. A system invocation only requests that handoff.
        app.requestSystemVoice()
        hide()
    }
}
