package org.nieto.assistant.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.nieto.assistant.AppState
import org.nieto.assistant.MainActivity
import org.nieto.assistant.NietoApp
import org.nieto.assistant.R
import org.vosk.Model
import org.vosk.Recognizer
import java.io.IOException

/**
 * Opt-in microphone foreground service. Vosk processes wake audio on the device;
 * only the subsequent, separately authorized assistant turn can send audio remotely.
 * No PCM, recognized ambient speech, or wake transcripts are stored.
 */
class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val policy = WakeWordPolicy()
    private val captureRetry = WakeCaptureRetryPolicy()
    private lateinit var app: NietoApp
    private var controller: Job? = null
    private var stateObserver: Job? = null
    private var recorderJob: Job? = null
    private var timer: Job? = null
    private var model: Model? = null
    private var captureGeneration = 0L
    private enum class CaptureMode { WAKE, WAKE_AND_END, END_ONLY }
    private data class CaptureResult(val text: String, val end: Boolean)
    private var captureMode: CaptureMode? = null
    private var foreground = false
    private var destroyed = false
    private var lastStatus = ""

    private sealed interface Event {
        data object Changed : Event
        data object Talk : Event
        data class Control(val action: WakeControlAction) : Event
        data class Healthy(val generation: Long) : Event
        data class Captured(val generation: Long, val phrase: String, val end: Boolean) : Event
        data class Failed(val generation: Long) : Event
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { events.trySend(Event.Changed) }
    }

    override fun onCreate() {
        super.onCreate()
        app = application as NietoApp
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Activación por voz de TINO", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Indica cuándo el micrófono está habilitado para escuchar Hola Tino."; setSound(null, null) }
        )
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>?) {
        val state = app.state.value
        writer.println("wakeOptIn=" + runCatching { app.settings.wakeOptIn }.getOrNull())
        writer.println("wakeEnabled=" + state.wakeEnabled)
        writer.println("foreground=" + foreground)
        writer.println("captureJobActive=" + (recorderJob?.isActive == true))
        writer.println("captureMode=" + (captureMode?.name ?: "PAUSED"))
        writer.println("conversationActive=" + app.hasActiveConversation)
        writer.println("taskMemoryPresent=" + app.taskMemoryPresent)
        writer.println("transportReady=" + app.transportReady)
        writer.println("answerPresent=" + state.answer.isNotBlank())
        writer.println("pointerPresent=" + (state.screenStep != null))
        writer.println("idleRemainingMs=" + app.idleRemainingMs)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action in listOf(ACTION_STOP, ACTION_END_CONVERSATION, ACTION_DISMISS)) {
            val action = if (intent?.action == ACTION_DISMISS) WakeControlAction.DISMISS_NOTIFICATION else WakeControlAction.END_CONVERSATION
            if (foreground && controller != null) events.trySend(Event.Control(action))
            else { app.handleWakeControl(action); stopSelf() }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TALK) {
            // A gesture cannot silently create a new microphone foreground service.
            if (foreground && controller != null && app.state.value.wakeEnabled) events.trySend(Event.Talk)
            else stopSelf()
            return START_NOT_STICKY
        }
        // Android must never resurrect microphone capture without another visible user action.
        if (intent?.action != ACTION_START || !app.state.value.wakeEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!microphoneGranted()) {
            stopListening("El permiso de micrófono está apagado. Activá TINO desde la aplicación.")
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification("Preparando la activación local…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(NOTIFICATION_ID, notification("Preparando la activación local…"))
            foreground = true
        } catch (_: RuntimeException) {
            stopListening("Android no permitió iniciar el micrófono. Activá TINO con la aplicación abierta.")
            return START_NOT_STICKY
        }
        if (controller == null) {
            controller = scope.launch {
                for (event in events) {
                    when (event) {
                        is Event.Captured -> handleWake(event)
                        is Event.Failed -> if (event.generation == captureGeneration) {
                            recorderJob?.join(); recorderJob = null
                            captureRetry.failed(SystemClock.elapsedRealtime())
                        }
                        Event.Talk -> handleTalk()
                        is Event.Control -> handleControl(event.action)
                        is Event.Healthy -> if (event.generation == captureGeneration) captureRetry.reset()
                        Event.Changed -> Unit
                    }
                    if (!destroyed) reconcile()
                }
            }
            stateObserver = scope.launch { app.state.collect { events.trySend(Event.Changed) } }
        }
        events.trySend(Event.Changed)
        return START_NOT_STICKY
    }

    private suspend fun reconcile() {
        val state = app.state.value
        if (!state.wakeEnabled) { stopCapture(); stopSelf(); return }
        if (!microphoneGranted()) {
            stopCapture()
            stopListening("Se revocó el permiso de micrófono. La escucha se detuvo.")
            return
        }
        if (!state.assistanceEnabled ||
            !app.settings.openAiConsent || !app.settings.remoteScreenConsent) {
            stopCapture()
            stopListening("La escucha se detuvo. Revisá el permiso de asistencia en TINO.")
            return
        }
        val now = SystemClock.elapsedRealtime()
        policy.updateVoice(state.voice.active || state.busy || state.recordingVoiceNote || state.screenCapturePending, now)
        scheduleCheck(now)
        val conversation = app.hasActiveConversation
        val endAllowed = conversation && state.accessibilityConnected && !state.busy && !state.recordingVoiceNote &&
            !state.screenCapturePending && !state.ttsSpeaking && state.voice.phase !in listOf(VoicePhase.CONNECTING, VoicePhase.LISTENING)
        val desiredMode = if (policy.canListen(conditions(state), now)) {
            if (conversation) CaptureMode.WAKE_AND_END else CaptureMode.WAKE
        } else if (endAllowed) CaptureMode.END_ONLY else null
        if (desiredMode == null) {
            stopCapture()
            publishStatus(when {
                state.voice.active || state.busy || state.recordingVoiceNote || state.screenCapturePending -> "TINO está atendiendo tu consulta."
                state.ttsSpeaking -> "TINO está hablando."
                !state.accessibilityConnected -> "Esperando la conexión del servicio de accesibilidad…"
                else -> "Esperando para volver a escuchar Hola Tino…"
            })
            return
        }
        if (captureMode != null && captureMode != desiredMode) stopCapture()
        if (captureRetry.remaining(now) != null) {
            publishStatus("Preparando el micrófono para volver a escuchar Hola Tino…")
            return
        }
        if (model == null) {
            publishStatus("Preparando el modelo local de voz…")
            try { model = OfflineVoiceModel.load(this) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: LinkageError) {
                stopListening("La biblioteca de voz local no es compatible con este dispositivo.")
                return
            }
            catch (_: Exception) {
                stopListening("Falta el modelo de voz local o no se pudo abrir. Reinstalá la versión completa de TINO.")
                return
            }
            // Loading may take a moment; permissions/screen may have changed in the meantime.
            events.trySend(Event.Changed)
            return
        }
        if (recorderJob == null) {
            val generation = ++captureGeneration
            val currentModel = model ?: return
            captureMode = desiredMode
            recorderJob = scope.launch(Dispatchers.IO) {
                try {
                    val phrase = captureWake(currentModel, generation, desiredMode)
                    // captureWake has released AudioRecord before a handoff event is sent.
                    events.trySend(Event.Captured(generation, phrase.text, phrase.end))
                } catch (_: CancellationException) { }
                catch (_: Exception) { events.trySend(Event.Failed(generation)) }
            }
        }
        publishStatus("Escuchando Hola Tino · reconocimiento local.")
    }

    private suspend fun handleWake(event: Event.Captured) {
        if (event.generation != captureGeneration) return
        // Join also makes the microphone ownership boundary explicit to RealtimeVoice.
        recorderJob?.join()
        recorderJob = null
        if (event.end) {
            if (app.hasActiveConversation) handleControl(WakeControlAction.END_CONVERSATION)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!microphoneGranted() || !policy.accept(event.phrase, conditions(app.state.value), now)) return
        publishStatus("Te escucho. Contame qué querés hacer.")
        try { app.startVoiceFromWake() }
        catch (_: RuntimeException) { publishStatus("No pude empezar la consulta. Podés volver a decir Hola Tino.") }
        scheduleCheck(now)
    }

    private suspend fun handleTalk() {
        stopCapture()
        val now = SystemClock.elapsedRealtime()
        if (!microphoneGranted() || LocalMicrophoneLease.occupied ||
            !policy.requestHandoff(conditions(app.state.value), now)) return
        publishStatus("Te escucho. Contame qué querés hacer.")
        try { app.startVoiceFromWake() }
        catch (_: RuntimeException) { publishStatus("No pude empezar la consulta. Podés volver a activar TINO.") }
        scheduleCheck(now)
    }

    private suspend fun handleControl(action: WakeControlAction) {
        val decision = WakeControlPolicy.decide(action)
        if (decision.clearConversation) {
            stopCapture()
            policy.reset(); captureRetry.reset()
            app.handleWakeControl(action)
            publishStatus("Decí Hola Tino para otra consulta.")
        } else if (foreground) {
            // Dismissing the notification never withdraws microphone opt-in.
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(lastStatus))
        }
    }

    private fun conditions(state: AppState) = WakeWordConditions(
        enabled = state.wakeEnabled,
        assistanceEnabled = state.assistanceEnabled,
        accessibilityConnected = state.accessibilityConnected,
        voiceBusy = state.voice.active || state.busy || state.recordingVoiceNote || state.screenCapturePending,
        ttsSpeaking = state.ttsSpeaking,
        nativeSurfaceVisible = state.nativeSurfaceVisible,
    )

    private fun scheduleCheck(now: Long) {
        timer?.cancel()
        val wait = listOfNotNull(policy.nextCheckDelay(now), captureRetry.remaining(now)).minOrNull()
        timer = wait?.let { delayMs -> scope.launch { delay(delayMs); events.trySend(Event.Changed) } }
    }

    private suspend fun stopCapture() {
        ++captureGeneration
        recorderJob?.cancelAndJoin()
        recorderJob = null
        captureMode = null
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private suspend fun captureWake(currentModel: Model, generation: Long, mode: CaptureMode): CaptureResult {
        check(microphoneGranted())
        if (!LocalMicrophoneLease.acquire("wake")) throw IOException("Microphone already leased")
        try { return captureWithLease(currentModel, generation, mode) }
        finally { LocalMicrophoneLease.release("wake") }
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private suspend fun captureWithLease(currentModel: Model, generation: Long, mode: CaptureMode): CaptureResult {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE.toInt(), AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw IOException("Unsupported microphone format")
        val wake = if (mode != CaptureMode.END_ONLY) Recognizer(currentModel, SAMPLE_RATE, "[\"hola tino\", \"[unk]\"]") else null
        val ending = if (mode != CaptureMode.WAKE) Recognizer(currentModel, SAMPLE_RATE) else null
        var recorder: AudioRecord? = null
        var echo: AcousticEchoCanceler? = null
        val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TINO:LocalWakeRecognition")
            .apply { setReferenceCounted(false) }
        fun finalWords(json: JSONObject): List<WakeRecognitionWord>? {
            val words = json.optJSONArray("result") ?: return null
            if (words.length() !in 1..24) return null
            return (0 until words.length()).map { index ->
                val item = words.optJSONObject(index)
                WakeRecognitionWord(item?.optString("word").orEmpty(), (item?.opt("conf") as? Number)?.toDouble())
            }
        }
        try {
            wake?.setWords(true); ending?.setWords(true)
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE.toInt(),
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6_400))
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IOException("Microphone unavailable")
            val sessionId = recorder.audioSessionId
            if (mode == CaptureMode.END_ONLY) echo = runCatching {
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.also { it.setEnabled(true) } else null
            }.getOrNull()
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("Microphone did not start")
            wakeLock.acquire()
            val buffer = ShortArray(320)
            val startedAt = SystemClock.elapsedRealtime()
            var reportedHealthy = false
            while (currentCoroutineContext().isActive) {
                currentCoroutineContext().ensureActive()
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                if (count < 0) throw IOException("Microphone read failed")
                if (!reportedHealthy && SystemClock.elapsedRealtime() - startedAt >= 30_000) {
                    reportedHealthy = true; events.trySend(Event.Healthy(generation))
                }
                if (count == 0) { delay(20); continue }
                val wakeFinal = wake?.acceptWaveForm(buffer, count) == true
                val endFinal = ending?.acceptWaveForm(buffer, count) == true
                if (endFinal) {
                    val json = JSONObject(ending!!.result)
                    val text = json.optString("text", "")
                    val state = app.state.value
                    if (LocalEndPolicy.accepts(true, text, finalWords(json), app.hasActiveConversation,
                        state.voice.phase == VoicePhase.SPEAKING, state.voice.partialText.ifBlank { state.answer })) return CaptureResult(text, true)
                }
                if (wakeFinal) {
                    val json = JSONObject(wake!!.result)
                    val text = json.optString("text", "")
                    if (WakeActivationPolicy.accepts(true, text, finalWords(json))) return CaptureResult(text, false)
                }
            }
            throw CancellationException("Wake recording stopped")
        } finally {
            runCatching { echo?.release() }
            runCatching { recorder?.stop() }; runCatching { recorder?.release() }
            runCatching { wake?.close() }; runCatching { ending?.close() }
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun microphoneGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun publishStatus(message: String) {
        if (message == lastStatus || destroyed) return
        lastStatus = message
        app.setWakeStatus(message)
        if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    private fun stopListening(message: String) {
        app.stopWakeListening(clearPreference = false)
        app.setWakeStatus(message)
        stopSelf()
    }

    private fun notification(message: String): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_END_CONVERSATION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getService(this, 3, Intent(this, WakeWordService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nieto).setContentTitle("TINO · activación por voz")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open).setDeleteIntent(dismiss).setOngoing(true).setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "Terminar conversación", stop).build()
    }

    override fun onDestroy() {
        destroyed = true
        runCatching { unregisterReceiver(screenReceiver) }
        stateObserver?.cancel()
        timer?.cancel()
        controller?.cancel()
        recorderJob?.cancel()
        events.close()
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        // Cancellation is asynchronous; release the model only after its AudioRecord/decoder job is joined.
        scope.launch(NonCancellable) {
            controller?.join()
            recorderJob?.join()
            withContext(Dispatchers.IO) { model?.close(); model = null }
            scope.cancel()
        }
        if (app.state.value.wakeEnabled) {
            app.stopWakeListening(clearPreference = false)
            app.setWakeStatus("La escucha se detuvo. Activala de nuevo desde TINO.")
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "org.nieto.assistant.voice.START_WAKE"
        const val ACTION_STOP = "org.nieto.assistant.voice.STOP_WAKE"
        const val ACTION_TALK = "org.nieto.assistant.voice.TALK"
        const val ACTION_END_CONVERSATION = "org.nieto.assistant.voice.END_CONVERSATION"
        const val ACTION_DISMISS = "org.nieto.assistant.voice.DISMISS"
        const val MODEL_ASSET = OfflineVoiceModel.ASSET_DIRECTORY
        private const val CHANNEL_ID = "tino_wake_microphone"
        private const val NOTIFICATION_ID = 8201
        private const val SAMPLE_RATE = 16_000f
    }
}
