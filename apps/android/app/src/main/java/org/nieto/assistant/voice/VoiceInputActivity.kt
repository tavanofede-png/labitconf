package org.nieto.assistant.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.nieto.assistant.MainActivity
import org.nieto.assistant.NietoApp

/** Compatibility entry for shortcuts; all recording belongs to the microphone foreground service. */
class VoiceInputActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as NietoApp
        if (app.state.value.wakeEnabled) app.requestSystemVoice()
        else startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
