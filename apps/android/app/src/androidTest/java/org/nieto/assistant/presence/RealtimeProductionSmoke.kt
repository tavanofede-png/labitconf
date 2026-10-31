package org.nieto.assistant.presence

import android.Manifest
import android.app.Instrumentation
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.service.voice.VoiceInteractionService
import org.nieto.assistant.NietoApp
import org.nieto.assistant.voice.TinoVoiceInteractionService
import org.nieto.assistant.voice.VoicePhase
import org.nieto.assistant.voice.WakeInvocationPCM
import org.nieto.assistant.voice.WakeWordService
import org.vosk.Model
import java.io.File

/** Opt-in integration suite: two real provider turns using only bundled synthetic PCM, never a test microphone. */
internal fun realtimeProductionSmoke(instrumentation: Instrumentation, continuous: Boolean = false): Bundle {
    val app = instrumentation.targetContext.applicationContext as NietoApp
    fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    data class State(val allowed: Boolean, val restorable: Boolean, val occupied: Boolean, val error: Boolean, val liveInput: Boolean,
        val phase: VoicePhase, val answer: Boolean, val pointer: Boolean, val task: Boolean,
        val ready: Boolean, val conversation: Boolean, val idleMs: Long, val wake: Boolean, val optIn: Boolean, val busy: Boolean,
        val assistanceEnabled: Boolean, val accessConnected: Boolean, val screenCapturePresent: Boolean,
        val remoteConsent: Boolean, val openAiConsent: Boolean, val tokenPresent: Boolean,
        val micPermission: Boolean, val locked: Boolean, val assistantActive: Boolean)
    fun state() = main {
        val value = app.state.value
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val locked = (app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        val screenCapturePresent = app.screenCapture != null
        val micPermission = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val assistantActive = VoiceInteractionService.isActiveService(app, ComponentName(app, TinoVoiceInteractionService::class.java))
        val restorable = app.settings.wakeOptIn && value.accessibilityConnected &&
            screenCapturePresent && app.settings.openAiConsent && app.settings.remoteScreenConsent && app.settings.token.isNotBlank() &&
            micPermission && !locked && assistantActive
        val allowed = restorable && value.wakeEnabled && value.assistanceEnabled
        State(allowed, restorable, value.voice.active || value.busy || value.ttsSpeaking || value.recordingVoiceNote || value.screenCapturePending ||
            audio.mode != AudioManager.MODE_NORMAL || audio.isMicrophoneMute,
            value.voice.phase == VoicePhase.ERROR, value.voice.phase == VoicePhase.LISTENING, value.voice.phase,
            value.answer.isNotBlank(), value.screenStep != null, app.taskMemoryPresent, app.transportReady,
            app.hasActiveConversation, app.idleRemainingMs, value.wakeEnabled, app.settings.wakeOptIn, value.busy,
            value.assistanceEnabled, value.accessibilityConnected, screenCapturePresent,
            app.settings.remoteScreenConsent, app.settings.openAiConsent, app.settings.token.isNotBlank(),
            micPermission, locked, assistantActive)
    }
    var started = 0
    var answered = 0
    var ended = 0
    var stage = "precondition"
    var initialWake = false
    var initialOptIn = false
    var fixture: WakeInvocationPCM? = null
    var followupFixture: WakeInvocationPCM? = null
    var precondition = false
    var success = false
    fun clean(value: State) = !value.answer && !value.pointer && !value.task && !value.ready && !value.conversation &&
        value.idleMs <= 0 && !value.busy && value.phase == VoicePhase.IDLE
    fun waitClean(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (clean(state())) return true
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        return false
    }
    try {
        // Preserve the real accessibility service. The runner's finish() disconnects this framework-owned connection on every return.
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        var before = state()
        initialOptIn = before.optIn
        // Instrumentation can restart the process. Restore only the user's already persisted choice;
        // never enable opt-in, change consent, request permissions or open an Activity for this suite.
        val restoreDeadline = SystemClock.elapsedRealtime() + 30_000L
        var restoreRequested = false
        var binderWaitEntered = false
        while (before.optIn && !before.allowed && !before.occupied && !before.conversation && !before.task &&
            SystemClock.elapsedRealtime() < restoreDeadline) {
            if (!before.accessConnected || !before.screenCapturePresent || !before.assistantActive) binderWaitEntered = true
            if (before.restorable && !restoreRequested) {
                main { app.restoreWakeListening() }
                restoreRequested = true
            }
            SystemClock.sleep(100)
            before = state()
        }
        initialWake = before.wake
        precondition = before.allowed && !before.occupied && !before.conversation && !before.ready && !before.task
        if (!precondition) return Bundle().apply {
            putBoolean("success", false); putBoolean("precondition", false); putInt("tests", 0)
            val binderReady = before.accessConnected && before.screenCapturePresent && before.assistantActive
            val restoreWaitExpired = SystemClock.elapsedRealtime() >= restoreDeadline
            val conditions = linkedMapOf(
                "optIn" to before.optIn, "wakeEnabled" to before.wake, "assistanceEnabled" to before.assistanceEnabled,
                "accessConnected" to before.accessConnected, "screenCapturePresent" to before.screenCapturePresent,
                "remoteConsent" to before.remoteConsent, "openAiConsent" to before.openAiConsent,
                "tokenPresent" to before.tokenPresent, "micPermission" to before.micPermission,
                "locked" to before.locked, "assistantActive" to before.assistantActive,
                "binderWaitEntered" to binderWaitEntered, "binderReady" to binderReady,
                "restoreRequested" to restoreRequested, "restoreWaitExpired" to restoreWaitExpired
            )
            conditions.forEach { (key, value) -> putBoolean(key, value) }
            putString("stream", "\nSKIP realtime-production: precondition=false; turns=0; state_occupied=${before.occupied}; configured=${before.allowed}; ${conditions.entries.joinToString("; ") { "${it.key}=${it.value}" }}. No configuration changed.\n")
        }
        stage = "synthetic_decode"
        val synthetic = decodeProductionFixture(instrumentation)
        fixture = synthetic
        if (continuous) {
            val model = kotlinx.coroutines.runBlocking { org.nieto.assistant.voice.OfflineVoiceModel.load(app) }
            followupFixture = model.use { loaded ->
                instrumentation.context.assets.open("voice-command.pcm").use { decodeSyntheticConversation(loaded, it) }
            }
            check(followupFixture != null) { "followup_not_decoded" }
        }
        for (turn in 1..2) {
            stage = "turn_${turn}_start"
            val current = state()
            check(current.allowed && !current.occupied) { "precondition_changed" }
            if (continuous && turn == 2) {
                check(current.conversation && current.ready && current.idleMs > 0) { "conversation_not_retained" }
                check(main { app.onConversationSpeechActivity() }) { "speech_not_accepted" }
            } else check(!current.conversation && !current.task) { "previous_conversation_retained" }
            // App owns and zeroes each submitted array. A new copy is required for the second invocation.
            val source = if (continuous && turn == 2) followupFixture!! else synthetic
            val audio = source.copy(pcm24k = source.pcm24k.copyOf())
            main { app.startVoiceFromWake(audio) }
            started++
            val deadline = SystemClock.elapsedRealtime() + 60_000L
            var progressed = false
            var received = false
            stage = "turn_${turn}_response"
            do {
                val value = state()
                check(value.wake && value.optIn) { "wake_changed" }
                check(!value.error) { "voice_error" }
                check(!value.liveInput) { "unexpected_live_microphone" }
                if (value.phase in listOf(VoicePhase.WAITING, VoicePhase.SPEAKING)) progressed = true
                if (progressed && value.answer && !value.busy &&
                    (if (continuous) value.phase == VoicePhase.IDLE && value.idleMs > 0 else value.phase != VoicePhase.CONNECTING)) {
                    received = true; break
                }
                SystemClock.sleep(50)
            } while (SystemClock.elapsedRealtime() < deadline)
            check(received) { "response_timeout" }
            answered++
            if (!continuous || turn == 2) {
                stage = "turn_${turn}_end"
                main { app.endConversation() }
                check(waitClean(5_000)) { "end_not_clean" }
                ended++
            }
            // No lease-release sleep here: immediate second use exercises the production close/owner-ack path.
        }
        stage = "complete"
        success = true
    } catch (_: Throwable) {
        // Report stage and booleans only, never provider text, credentials, screenshots or transcripts.
    } finally {
        fixture?.pcm24k?.fill(0)
        followupFixture?.pcm24k?.fill(0)
        if (started > 0) {
            runCatching { main { app.endConversation() }; waitClean(5_000) }
        }
    }
    val final = state()
    val wakePreserved = final.wake == initialWake && final.optIn == initialOptIn
    val cleared = clean(final)
    success = success && wakePreserved && cleared
    return Bundle().apply {
        putBoolean("success", success); putBoolean("precondition", precondition); putInt("tests", if (success) 2 else answered)
        putInt("turns_started", started); putInt("answers_received", answered); putInt("ends_verified", ended)
        putBoolean("memory_clear", !final.task); putBoolean("transport_ready", final.ready)
        putBoolean("answer_clear", !final.answer); putBoolean("pointer_clear", !final.pointer); putBoolean("wake_preserved", wakePreserved)
        putString("stream", "\n${if (success) "PASS" else "FAIL"} realtime-production: continuous=$continuous; stage=$stage; started=$started; answered=$answered; ended=$ended; memory_clear=${!final.task}; transport_ready=${final.ready}; answer_clear=${!final.answer}; pointer_clear=${!final.pointer}; wake_preserved=$wakePreserved.\n")
    }
}

internal fun decodeProductionFixture(instrumentation: Instrumentation): WakeInvocationPCM {
    val target = instrumentation.targetContext
    val directory = File(target.cacheDir, "realtime-production-synthetic-model")
    check(directory.canonicalFile.parentFile == target.cacheDir.canonicalFile)
    fun copy(source: String, destination: File) {
        val children = target.assets.list(source).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            target.assets.open(source).use { input -> destination.outputStream().use { input.copyTo(it) } }
        } else children.forEach { copy("$source/$it", File(destination, it)) }
    }
    try {
        copy(WakeWordService.MODEL_ASSET, directory)
        return Model(directory.absolutePath).use { model ->
            val decoded = instrumentation.context.assets.open("wake-inline.pcm").use { decodeSyntheticWake(model, it) }
            val complete = decoded.firstOrNull { it.invocation != null && (it.words?.size ?: 0) > 2 }
            var retained: WakeInvocationPCM? = null
            try {
                val utterance = checkNotNull(complete) { "synthetic_inline_not_recognized" }
                val invocation = checkNotNull(utterance.invocation) { "synthetic_inline_not_recognized" }
                val timed = checkNotNull(utterance.words) { "synthetic_timestamps_missing" }
                check(invocation.startSample16k >= kotlin.math.ceil(timed.first().startSeconds!! * 16000).toLong()) { "synthetic_prewake_audio" }
                check(invocation.endSample16k >= kotlin.math.ceil(timed.last().endSeconds!! * 16000).toLong()) { "synthetic_query_truncated" }
                retained = invocation
                invocation
            } finally {
                decoded.forEach { if (it.invocation !== retained) it.invocation?.pcm24k?.fill(0) }
            }
        }
    } finally {
        directory.deleteRecursively()
    }
}
