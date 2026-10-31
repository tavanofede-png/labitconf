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
 * a verified invocation exports only its bounded PCM from the wake phrase onward.
 * PCM stays in memory and is cleared after handoff/cancellation; nothing is saved to disk.
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
    private enum class CaptureMode { WAKE, CONVERSATION, END_ONLY }
    private data class CaptureResult(val text: String, val end: Boolean, val invocation: WakeInvocationPCM? = null, val conversationTurn: Boolean = false)
    private var captureMode: CaptureMode? = null
    private var foreground = false
    private var destroyed = false
    private var lastStatus = ""
    // Content-free runtime diagnostics: never keep PCM, recognized words or provider text here.
    @Volatile private var lastAudioAt = 0L
    @Volatile private var inputRms = 0
    @Volatile private var constrainedFinals = 0L
    @Volatile private var verifiedWakeCount = 0L
    @Volatile private var freeFinals = 0L
    @Volatile private var freePrefixMatches = false
    @Volatile private var invocationCount = 0L
    @Volatile private var conversationTurnCount = 0L
    private var handoffCount = 0L

    private sealed interface Event {
        data object Changed : Event
        data object Talk : Event
        data class Control(val action: WakeControlAction) : Event
        data class Healthy(val generation: Long) : Event
        data class Captured(val generation: Long, val phrase: String, val end: Boolean, val invocation: WakeInvocationPCM?, val conversationTurn: Boolean) : Event
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
        writer.println("assistantSelected=" + state.assistantSelected)
        writer.println("accessibilityConnected=" + state.accessibilityConnected)
        writer.println("deviceLinked=" + app.settings.token.isNotBlank())
        writer.println("openAiConsent=" + app.settings.openAiConsent)
        writer.println("remoteScreenConsent=" + app.settings.remoteScreenConsent)
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
        writer.println("voicePhase=" + state.voice.phase.name)
        writer.println("voiceMessage=" + state.voice.message)
        writer.println("busy=" + state.busy)
        writer.println("lastAudioAgeMs=" + if (lastAudioAt == 0L) -1 else SystemClock.elapsedRealtime() - lastAudioAt)
        writer.println("inputRms=" + inputRms)
        writer.println("constrainedFinals=" + constrainedFinals)
        writer.println("verifiedWakeCount=" + verifiedWakeCount)
        writer.println("freeFinals=" + freeFinals)
        writer.println("freePrefixMatches=" + freePrefixMatches)
        writer.println("invocationCount=" + invocationCount)
        writer.println("conversationTurnCount=" + conversationTurnCount)
        writer.println("handoffCount=" + handoffCount)
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
        try { if (!foreground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification("Preparando la activación local…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(NOTIFICATION_ID, notification("Preparando la activación local…"))
            foreground = true
        } } catch (_: RuntimeException) {
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
        val conversational = conversation && !state.textOnly && state.voice.phase == VoicePhase.IDLE
        val endAllowed = conversation && state.accessibilityConnected && !state.busy && !state.recordingVoiceNote &&
            !state.screenCapturePending && !state.ttsSpeaking && state.voice.phase !in listOf(VoicePhase.CONNECTING, VoicePhase.LISTENING)
        val desiredMode = if (policy.canListenConversation(conditions(state), conversational, now)) {
            CaptureMode.CONVERSATION
        } else if (policy.canListen(conditions(state), now)) {
            CaptureMode.WAKE
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
                    if (events.trySend(Event.Captured(generation, phrase.text, phrase.end, phrase.invocation, phrase.conversationTurn)).isFailure)
                        phrase.invocation?.pcm24k?.fill(0)
                } catch (_: CancellationException) { }
                catch (_: Exception) { events.trySend(Event.Failed(generation)) }
            }
        }
        publishStatus(if (desiredMode == CaptureMode.CONVERSATION) "Te escucho · conversación activa." else "Escuchando Hola Tino · reconocimiento local.")
    }

    private suspend fun handleWake(event: Event.Captured) {
        if (event.generation != captureGeneration) { event.invocation?.pcm24k?.fill(0); return }
        // Join also makes the microphone ownership boundary explicit to RealtimeVoice.
        recorderJob?.join()
        recorderJob = null
        if (event.end) {
            if (app.hasActiveConversation) handleControl(WakeControlAction.END_CONVERSATION)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val state = app.state.value
        val accepted = if (event.conversationTurn) policy.acceptConversationTurn(conditions(state),
            app.hasActiveConversation && !state.textOnly && state.voice.phase == VoicePhase.IDLE, now)
            else policy.acceptVerifiedWake(conditions(state), now)
        if (event.invocation == null || !microphoneGranted() || !accepted) { event.invocation?.pcm24k?.fill(0); return }
        publishStatus("Preparando la consulta…")
        handoffCount++
        try { app.startVoiceFromWake(event.invocation) }
        catch (_: RuntimeException) { event.invocation?.pcm24k?.fill(0); publishStatus("No pude empezar la consulta. Podés volver a decir Hola Tino.") }
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
        val conversational = app.hasActiveConversation && !app.state.value.textOnly && app.state.value.voice.phase == VoicePhase.IDLE
        val wait = listOfNotNull(policy.nextCheckDelay(now, conversational), captureRetry.remaining(now)).minOrNull()
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
        val wake = if (mode == CaptureMode.WAKE) Recognizer(currentModel, SAMPLE_RATE, "[\"hola tino\", \"[unk]\"]") else null
        val ending = Recognizer(currentModel, SAMPLE_RATE)
        val ring = WakePcmRingBuffer(if (mode == CaptureMode.CONVERSATION) WakePcmRingBuffer.MAX_SAMPLES else WakePcmRingBuffer.DEFAULT_WAKE_SAMPLES)
        val conversationInput = ConversationInputPolicy()
        var lastPartialAt = 0L
        var wakeProof: WakePrefixProof? = null
        var pendingFreeFinal: Triple<String, List<WakeTimedWord>?, Long>? = null
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
        fun timed(source: JSONObject, key: String = "result"): List<WakeTimedWord>? {
            val items = source.optJSONArray(key) ?: return null
            if (items.length() !in 1..128) return null
            return (0 until items.length()).map { index ->
                val item = items.optJSONObject(index)
                WakeTimedWord(item?.optString("word").orEmpty(), (item?.opt("conf") as? Number)?.toDouble(),
                    (item?.opt("start") as? Number)?.toDouble(), (item?.opt("end") as? Number)?.toDouble())
            }
        }
        try {
            wake?.setWords(true); ending.setWords(true)
            if (mode == CaptureMode.CONVERSATION) ending.setPartialWords(true)
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE.toInt(),
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 6_400))
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IOException("Microphone unavailable")
            val sessionId = recorder.audioSessionId
            if (mode != CaptureMode.WAKE) echo = runCatching {
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.also { it.setEnabled(true) } else null
            }.getOrNull()
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("Microphone did not start")
            wakeLock.acquire()
            val buffer = ShortArray(320)
            val startedAt = SystemClock.elapsedRealtime()
            var reportedHealthy = false
            var windowStarted = startedAt
            var windowSamples = 0L
            var windowSquares = 0.0
            while (currentCoroutineContext().isActive) {
                currentCoroutineContext().ensureActive()
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                if (count < 0) throw IOException("Microphone read failed")
                if (!reportedHealthy && SystemClock.elapsedRealtime() - startedAt >= 30_000) {
                    reportedHealthy = true; events.trySend(Event.Healthy(generation))
                }
                if (count == 0) { delay(20); continue }
                lastAudioAt = SystemClock.elapsedRealtime()
                for (index in 0 until count) { val value = buffer[index].toDouble(); windowSquares += value * value }
                windowSamples += count
                if (lastAudioAt - windowStarted >= 1000L) {
                    inputRms = kotlin.math.sqrt(windowSquares / windowSamples).toInt()
                    windowSamples = 0; windowSquares = 0.0; windowStarted = lastAudioAt
                }
                if (!ring.append(buffer, count)) throw IOException("Invalid local audio buffer")
                val wakeFinal = wake?.acceptWaveForm(buffer, count) == true
                val endFinal = ending.acceptWaveForm(buffer, count)
                if (mode == CaptureMode.CONVERSATION && !endFinal && lastAudioAt - lastPartialAt >= 250L) {
                    lastPartialAt = lastAudioAt
                    val partial = JSONObject(ending.partialResult)
                    val first = timed(partial, "partial_result")?.firstOrNull()?.startSeconds
                        ?.takeIf { it.isFinite() && it >= 0 }?.let { (it * WakePcmRingBuffer.SAMPLE_RATE).toLong() }
                    if (conversationInput.partial(partial.optString("partial"), ring.totalSamples, first)) {
                        withContext(Dispatchers.Main.immediate) {
                            if (generation == captureGeneration) app.onConversationSpeechActivity()
                        }
                    }
                }
                if (wakeFinal) {
                    constrainedFinals++
                    val json = JSONObject(wake!!.result)
                    // The constrained decoder proves only the wake prefix; never export its shorter audio.
                    ring.verifyWakePrefix(true, json.optString("text"), timed(json))?.let { wakeProof = it; verifiedWakeCount++ }
                }
                if (endFinal) {
                    freeFinals++
                    val json = JSONObject(ending.result)
                    val text = json.optString("text", "")
                    freePrefixMatches = WakeWordPolicy.matches(text)
                    val state = app.state.value
                    val words = finalWords(json)
                    if (LocalEndPolicy.accepts(true, text, words, app.hasActiveConversation,
                        state.voice.phase == VoicePhase.SPEAKING, state.voice.partialText.ifBlank { state.answer })) return CaptureResult(text, true)
                    if (mode == CaptureMode.CONVERSATION) {
                        val first = timed(json)?.firstOrNull()?.startSeconds?.takeIf { it.isFinite() && it >= 0 }
                            ?.let { (it * WakePcmRingBuffer.SAMPLE_RATE).toLong() }
                        val start = conversationInput.finalStart(text, ring.totalSamples, first)
                        val invocation = start?.let { ring.extractConversationInvocation(it, ring.totalSamples) }
                        if (invocation != null) {
                            conversationTurnCount++
                            return CaptureResult("", false, invocation, conversationTurn = true)
                        }
                    } else if (mode == CaptureMode.WAKE) {
                        // Either decoder can finalize first; replace metadata with the latest free utterance.
                        pendingFreeFinal = Triple(text, timed(json), ring.totalSamples)
                    }
                }
                if (mode == CaptureMode.CONVERSATION && conversationInput.atLimit(ring.totalSamples)) {
                    val invocation = conversationInput.startSample?.let { ring.extractConversationInvocation(it, ring.totalSamples) }
                    if (invocation != null) { conversationTurnCount++; return CaptureResult("", false, invocation, conversationTurn = true) }
                }
                if (mode == CaptureMode.WAKE && (wakeFinal || endFinal)) {
                    pendingFreeFinal?.let { (text, words, finalSample) ->
                        // Keep the full endpoint's PCM; do not require the general ASR to spell the proper name again.
                        val invocation = wakeProof?.let { ring.extractVerifiedInvocation(it, finalSample, words) }
                        if (invocation != null) { invocationCount++; return CaptureResult(text, false, invocation) }
                    }
                }
            }
            throw CancellationException("Wake recording stopped")
        } finally {
            runCatching { echo?.release() }
            runCatching { recorder?.stop() }; runCatching { recorder?.release() }
            ring.clear()
            runCatching { wake?.close() }; runCatching { ending.close() }
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
            while (true) {
                val pending = events.tryReceive().getOrNull() ?: break
                if (pending is Event.Captured) pending.invocation?.pcm24k?.fill(0)
            }
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
