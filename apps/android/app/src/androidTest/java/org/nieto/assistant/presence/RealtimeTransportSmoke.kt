package org.nieto.assistant.presence

import android.Manifest
import android.app.Instrumentation
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.nieto.assistant.NietoApp
import org.nieto.assistant.voice.RealtimeVoice
import org.nieto.assistant.voice.VoicePhase
import org.nieto.assistant.voice.VoiceState
import org.nieto.assistant.voice.WakeInvocationPCM

/** Optional native transport component test, not an App/accessibility/wake end-to-end test. */
internal fun realtimeTransportSmoke(instrumentation: Instrumentation): Bundle {
    val app = instrumentation.targetContext.applicationContext as NietoApp
    fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    fun permitted() = main {
        val value = app.state.value
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        app.settings.openAiConsent && app.settings.remoteScreenConsent && app.settings.token.isNotBlank() &&
            app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            !(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked &&
            audio.mode == AudioManager.MODE_NORMAL && !audio.isMicrophoneMute &&
            !value.voice.active && !value.busy && !value.ttsSpeaking && !value.recordingVoiceNote && !value.screenCapturePending &&
            !app.hasActiveConversation && !app.transportReady && !app.taskMemoryPresent
    }
    if (!permitted()) return Bundle().apply {
        putBoolean("success", false); putBoolean("precondition", false); putInt("tests", 0)
        putString("stream", "\nSKIP realtime-transport: precondition=false; turns=0; component_only=true. No configuration changed.\n")
    }
    val identity = main { app.settings.baseUrl to app.settings.token }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var observed = VoiceState()
    var answer = false
    var pointer = false
    var endedCallback = false
    var liveInputObserved = false
    var errorObserved = false
    val realtime = main { RealtimeVoice(scope, onState = { observed = it; liveInputObserved = liveInputObserved || it.phase == VoicePhase.LISTENING; errorObserved = errorObserved || it.phase == VoicePhase.ERROR }, onAnswer = { answer = it.isNotBlank() },
        onGuidanceOffer = {}, onStep = { pointer = true }, onConversationEnded = { answer = false; pointer = false; endedCallback = true }) }
    fun clean() = main { !answer && !pointer && endedCallback && !realtime.taskMemoryPresent && !realtime.transportReady &&
        !realtime.hasActiveConversation && realtime.idleRemainingMs <= 0 && observed.phase == VoicePhase.IDLE }
    var fixture: WakeInvocationPCM? = null
    var started = 0
    var answered = 0
    var ended = 0
    var stage = "synthetic_decode"
    var success = false
    var cleared = false
    try {
        val synthetic = decodeProductionFixture(instrumentation)
        fixture = synthetic
        for (turn in 1..2) {
            stage = "turn_${turn}_start"
            check(permitted()) { "precondition_changed" }
            val pcm = synthetic.pcm24k.copyOf()
            main {
                answer = false; pointer = false; endedCallback = false
                realtime.start(identity.first, identity.second, inputAudio = pcm)
            }
            started++
            stage = "turn_${turn}_response"
            val deadline = SystemClock.elapsedRealtime() + 60_000L
            var received = false
            var progressed = false
            do {
                val state = main { Triple(observed.phase, answer, realtime.transportReady) }
                check(main { !errorObserved }) { "voice_error" }
                check(main { !liveInputObserved }) { "unexpected_live_microphone" }
                if (state.first in listOf(VoicePhase.WAITING, VoicePhase.SPEAKING)) progressed = true
                if (progressed && state.second && state.third) { received = true; break }
                SystemClock.sleep(50)
            } while (SystemClock.elapsedRealtime() < deadline)
            check(received) { "response_timeout" }
            answered++
            stage = "turn_${turn}_end"
            main { realtime.cancel(clearTaskContext = true) }
            check(clean()) { "end_not_clean" }
            ended++
            // Immediately start the next turn: the production component must await the owner-close acknowledgement.
        }
        stage = "complete"
        success = true
    } catch (_: Throwable) {
        // Static stage/counts only: no provider transcript, credentials, screen content or microphone data.
    } finally {
        fixture?.pcm24k?.fill(0)
        runCatching { main { realtime.cancel(clearTaskContext = true) }; cleared = clean() }
        scope.cancel()
    }
    success = success && cleared && main { !errorObserved && !liveInputObserved }
    return Bundle().apply {
        putBoolean("success", success); putBoolean("precondition", true); putBoolean("component_only", true)
        putInt("tests", if (success) 2 else answered); putInt("turns_started", started)
        putInt("answers_received", answered); putInt("ends_verified", ended); putBoolean("local_state_clear", cleared)
        putBoolean("live_input_observed", liveInputObserved); putBoolean("voice_error_observed", errorObserved)
        putString("stream", "\n${if (success) "PASS" else "FAIL"} realtime-transport: component_only=true; stage=$stage; started=$started; answered=$answered; ended=$ended; local_state_clear=$cleared.\n")
    }
}
