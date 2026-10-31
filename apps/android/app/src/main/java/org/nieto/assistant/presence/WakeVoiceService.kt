package org.nieto.assistant.presence

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.*
import org.json.JSONObject
import org.nieto.assistant.NietoApp
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

/** Explicitly enabled local wake-word recognition; ambient audio is neither saved nor uploaded. */
class WakeVoiceService : Service() {
    private val app get() = application as NietoApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var generation = 0
    private var stopped = false
    private var loading = false
    private var commandPending = false
    private var tone: ToneGenerator? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        scope.launch {
            app.state.collect { state ->
                if (model != null && !stopped) {
                    if (state.busy || state.speaking || state.recordingVoiceNote) {
                        if (speech != null) { closeSpeech(); app.wakeState("paused"); updateNotice("En pausa mientras TINO responde.") }
                    } else if (speech == null && state.wakePhase == "paused") startRecognition(command = false)
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop" || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            app.settings.read("accountSession").isBlank()) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == "command" && model != null) {
            if (app.state.value.wakePhase == "command") {
                handler.removeCallbacksAndMessages(null); tone?.release(); tone = null; commandPending = false; startRecognition(false)
            } else activate()
            return START_NOT_STICKY
        }
        if (loading || model != null) return START_NOT_STICKY
        try { showPresence(4202, "Hola TINO", "Preparando la activación por voz…", true) }
        catch (_: Exception) { fail("No pude activar el micrófono. Abrí TINO y revisá sus permisos."); return START_NOT_STICKY }
        loading = true; app.wakeState("starting")
        scope.launch {
            try {
                val path = withContext(Dispatchers.IO) { unpackModel() }
                // Creation is kept in this coroutine so cancellation cannot abandon a native Model.
                if (stopped) return@launch
                model = Model(path)
                loading = false
                if (app.state.value.busy || app.state.value.speaking) app.wakeState("paused") else startRecognition(false)
            } catch (_: Exception) { if (!stopped) fail("No pude iniciar «Hola TINO». Podés volver a activarlo desde el menú.") }
        }
        return START_NOT_STICKY
    }
    private fun unpackModel(): String {
        val destination = File(filesDir, "voice-es-0.42")
        if (File(destination, ".ready").exists()) return destination.absolutePath
        fun copy(path: String, output: File) {
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) { output.parentFile?.mkdirs(); assets.open(path).use { input -> output.outputStream().use { input.copyTo(it) } } }
            else { output.mkdirs(); children.forEach { copy("$path/$it", File(output, it)) } }
        }
        copy("model-es", destination)
        check(File(destination, "am/final.mdl").isFile)
        File(destination, ".ready").writeText("0.42")
        return destination.absolutePath
    }
    private fun startRecognition(command: Boolean) {
        if (stopped || model == null) return
        closeSpeech()
        if (app.state.value.busy || app.state.value.speaking || app.state.value.recordingVoiceNote) { app.wakeState("paused"); return }
        try {
            val current = generation
            val decoder = if (command) Recognizer(model, 16000f) else Recognizer(model, 16000f, "[\"hola tino\",\"[unk]\"]")
            recognizer = decoder
            val input = SpeechService(decoder, 16000f)
            speech = input
            app.wakeState(if (command) "command" else "waiting")
            updateNotice(if (command) "Te escucho. Decí tu consulta después del sonido." else "Esperando «Hola TINO» · detección en este dispositivo.")
            val listener = object : RecognitionListener {
                private fun valid() = !stopped && current == generation
                override fun onPartialResult(hypothesis: String?) {
                    if (!valid() || command) return
                    if (WakePhrase.matches(JSONObject(hypothesis.orEmpty()).optString("partial"))) activate()
                }
                override fun onResult(hypothesis: String?) {
                    if (!valid()) return
                    val text = JSONObject(hypothesis.orEmpty()).optString("text").trim()
                    if (!command) { if (WakePhrase.matches(text)) activate() }
                    else if (text.isNotBlank()) {
                        if (app.settings.read("accountSession").isBlank()) { stopSelf(); return }
                        closeSpeech(); app.wakeState("paused")
                        // Only the explicit command after the wake phrase enters the existing chat API.
                        app.sendMessage(text)
                    }
                }
                override fun onFinalResult(hypothesis: String?) = Unit
                override fun onTimeout() { if (valid()) startRecognition(false) }
                override fun onError(exception: Exception?) { if (valid()) fail("El micrófono se interrumpió. Podés activar «Hola TINO» otra vez.") }
            }
            if (command) input.startListening(listener, 15_000) else input.startListening(listener)
        } catch (_: Exception) { fail("El micrófono está ocupado o no está disponible. Probá de nuevo desde el menú.") }
    }
    private fun activate() {
        if (stopped || commandPending || app.state.value.busy || app.state.value.speaking || app.state.value.recordingVoiceNote) return
        closeSpeech(); commandPending = true; app.wakeState("command")
        tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65) }.getOrNull()
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        handler.postDelayed({ tone?.release(); tone = null; commandPending = false; if (!stopped) startRecognition(true) }, 220)
    }
    private fun closeSpeech() {
        generation++
        speech?.cancel(); speech?.shutdown(); speech = null
        recognizer?.close(); recognizer = null
    }
    private fun updateNotice(text: String) {
        getSystemService(NotificationManager::class.java).notify(4202, presenceNotification(4202, "Hola TINO activo", text))
    }
    private fun fail(message: String) { app.presenceNotice(message); stopSelf() }
    override fun onDestroy() {
        stopped = true; handler.removeCallbacksAndMessages(null); scope.cancel(); closeSpeech()
        tone?.release(); model?.close(); model = null
        app.wakeState("off"); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
}
