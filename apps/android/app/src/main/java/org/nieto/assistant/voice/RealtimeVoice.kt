package org.nieto.assistant.voice

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.nieto.assistant.BuildConfig
import org.nieto.assistant.screen.ScreenStep
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class VoicePhase { IDLE, CONNECTING, LISTENING, WAITING, SPEAKING, ERROR }
data class VoiceState(val phase: VoicePhase = VoicePhase.IDLE, val message: String = "Voz con TINO", val partialText: String = "", val audibleError: Boolean = true) {
    val active get() = phase in listOf(VoicePhase.CONNECTING, VoicePhase.LISTENING, VoicePhase.WAITING, VoicePhase.SPEAKING)
}

/** All protocol state is main-thread confined; PCM capture/playback runs only on Dispatchers.IO. */
class RealtimeVoice(
    private val scope: CoroutineScope,
    private val onState: (VoiceState) -> Unit,
    private val onAnswer: (String) -> Unit,
    private val onGuidanceOffer: () -> Unit,
    private val onStep: (ScreenStep) -> Unit = {},
    private val onTaskContext: (TaskContext?) -> Unit = {},
    private val onConversationEnded: () -> Unit = {},
    private val onObservationRefresh: () -> Unit = {},
) {
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var connectionGeneration = 0L
    private var ready = false
    private var closingSocket: WebSocket? = null
    private var closingAck: CompletableDeferred<Unit>? = null
    private var closeWait: Job? = null
    private var closingIsUserEnd = false
    private var connectionAttempt = 0
    private var pendingCapture = false
    private var pendingText: String? = null
    private var pendingInputAudio: ByteArray? = null
    private var pendingGuidance = false
    private var quietFollowUp = false
    @Volatile private var inputHadSpeech = false
    private val conversationIdle = ConversationIdlePolicy()
    private val continuity = SessionContinuityPolicy()
    private val taskMemory = TaskContextMemory()
    private var taskContext: TaskContext?
        get() = taskMemory.context
        set(value) { taskMemory.remember(value) }
    private var logicalIdentity: Pair<String, String>? = null
    private var requestEpoch = 0L
    private var connectJob: Job? = null
    private var idleClose: Job? = null
    private var lastClosedAt = 0L
    private var transportStartedAt = 0L
    private var pendingUserActivity: String? = null
    private var lastUserActivityAt = 0L
    private var activityFlush: Job? = null
    private var snapshotFlush: Job? = null
    private var queuedSnapshot: JSONObject? = null
    private var observing = false
    private var observationRequested = false
    private var observationSnapshot: JSONObject? = null
    private var observationActivity: String? = null
    private var observationBaseline: JSONObject? = null
    private var lastObservation: JSONObject? = null
    private var pendingScreen: JSONObject? = null
    private var lastScreenContextAt = 0L
    private var ignoredCancelAcks = 0
    private var connectionKey: Pair<String, String>? = null
    private var watchdog: Job? = null
    private var captureJob: Job? = null
    private var retiringCapture: Job? = null
    private var captureDeadline: Job? = null
    private val captureLock = Any()
    @Volatile private var recording = false
    @Volatile private var commitCapture = false
    @Volatile private var recorder: AudioRecord? = null
    private val turns = RealtimeTurnGuard()
    private var awaitingResponse = false
    private var lastAnswerTurn: String? = null
    private var transcript = ""
    private var current = VoiceState()
    private var outputMode = "audio"
    private val playback = AudioPlaybackTimeline()
    private val player = PcmPlayer(scope, onEnded = { turnId ->
        if (turns.accepts(turnId)) {
            val hadAudio = playback.hasAudio
            if (!hadAudio || send(JSONObject().put("type", "playback.complete").put("turnId", turnId))) {
                playback.clear()
                conversationIdle.turnFinished(SystemClock.elapsedRealtime())
                emit(VoiceState(message = "Decí Hola Tino para otra consulta."))
            }
        }
    }, onFailure = { message -> fail(message) })

    fun start(baseUrl: String, installationToken: String, screen: JSONObject? = null, text: String? = null,
        textOnly: Boolean = false, followUp: Boolean = false, inputAudio: ByteArray? = null) {
        try {
            request(baseUrl to installationToken, screen, text, if (textOnly) "text" else "audio", guidance = false, followUp = followUp, inputAudio = inputAudio)
        } catch (error: Exception) {
            inputAudio?.fill(0)
            throw error
        }
    }

    private fun request(identity: Pair<String, String>, screen: JSONObject?, text: String?, mode: String, guidance: Boolean, followUp: Boolean = false, inputAudio: ByteArray? = null) {
        require(identity.second.isNotBlank()) { "Conectá este teléfono desde Ajustes." }
        noteUserRequest()
        if (taskMemory.bind(identity)) onTaskContext(null)
        logicalIdentity = identity
        ++requestEpoch
        connectJob?.cancel(); idleClose?.cancel()
        if (ready) interruptPlayback(ignoreAcknowledgement = true)
        player.stop(); turns.cancel(); transcript = ""; awaitingResponse = false
        pendingCapture = true; pendingScreen = screen; pendingText = text; pendingGuidance = guidance
        pendingInputAudio?.fill(0); pendingInputAudio = inputAudio
        outputMode = mode; quietFollowUp = followUp; inputHadSpeech = false
        if (ready && connectionKey == identity && !continuity.shouldRenewBeforeTurn(SystemClock.elapsedRealtime())) {
            beginRequested(connectionGeneration)
        } else reopenPending(0)
    }

    /** Reconnect only for a pending explicit request, never because an idle timer fired. */
    private fun reopenPending(attempt: Int) {
        val identity = logicalIdentity ?: return
        if (!pendingCapture) return
        val screen = pendingScreen; val text = pendingText; val guidance = pendingGuidance
        val mode = outputMode; val followUp = quietFollowUp; val epoch = requestEpoch
        val audio = pendingInputAudio; pendingInputAudio = null
        val releasingCapture = captureJob
        connectJob?.cancel()
        closeTransport()
        pendingCapture = true; pendingScreen = screen; pendingText = text; pendingGuidance = guidance
        pendingInputAudio = audio
        outputMode = mode; quietFollowUp = followUp
        emit(VoiceState(VoicePhase.CONNECTING, "Conectando con TINO. El micrófono todavía está apagado."))
        connectJob = scope.launch {
            closingAck?.await()
            releasingCapture?.join()
            val releaseDelay = if (lastClosedAt == 0L) 0L else
                (SessionContinuityPolicy.LEASE_RELEASE_MS - (SystemClock.elapsedRealtime() - lastClosedAt)).coerceAtLeast(0)
            val retryDelay = if (attempt == 0) 0L else SessionContinuityPolicy.retryDelay(attempt, kotlin.random.Random.nextLong(201)) ?: return@launch
            delay(maxOf(releaseDelay, retryDelay))
            if (epoch != requestEpoch || !pendingCapture) return@launch
            openConnection(identity, attempt)
        }
    }

    private fun openConnection(identity: Pair<String, String>, attempt: Int) {
        connectionAttempt = attempt
        transportStartedAt = SystemClock.elapsedRealtime()
        val generation = ++connectionGeneration
        connectionKey = identity
        val request = Request.Builder().url(RealtimePolicy.socketUrl(identity.first, BuildConfig.DEBUG))
            .header("Authorization", "Bearer " + identity.second).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    if (webSocket === closingSocket) {
                        if (runCatching { JSONObject(text).optString("type") }.getOrNull() == "conversation.ended") finishClosing(webSocket, confirmed = true)
                        return@launch
                    }
                    if (generation != connectionGeneration) return@launch
                    if (text.length > 200_000) { fail("La respuesta de voz excedió el tamaño permitido."); return@launch }
                    runCatching { handle(JSONObject(text), generation) }
                        .onFailure { fail("La respuesta de voz no tuvo un formato válido. Podés volver a intentar.") }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.code
                response?.close()
                scope.launch {
                    if (webSocket === closingSocket) { finishClosing(webSocket); return@launch }
                    if (generation != connectionGeneration) return@launch
                    if (observing) {
                        if (attempt < SessionContinuityPolicy.MAX_RETRIES && status in listOf(null, 409, 502, 504)) reopenObservation(attempt + 1)
                        else closeTransport()
                        return@launch
                    }
                    if (pendingCapture && attempt < SessionContinuityPolicy.MAX_RETRIES && status in listOf(null, 409, 502, 504)) {
                        reopenPending(attempt + 1)
                    } else fail(when (status) {
                        401, 403 -> "El servidor no autorizó la voz. Revisá la conexión del teléfono desde Ajustes."
                        503 -> "La asistencia de voz no está configurada o disponible en el servidor."
                        429 -> "La asistencia no pudo autorizar otra consulta en este momento. Probá nuevamente más tarde."
                        else -> "Se interrumpió la conexión de voz. Podés volver a decir Hola Tino para continuar la tarea."
                    })
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    if (webSocket === closingSocket) { finishClosing(webSocket); return@launch }
                    if (generation != connectionGeneration) return@launch
                    if (observing) { closeTransport(); return@launch }
                    if (pendingCapture && attempt < SessionContinuityPolicy.MAX_RETRIES) reopenPending(attempt + 1)
                    else if (current.active) fail("Se interrumpió la respuesta. Podés volver a decir Hola Tino para continuar.")
                    else closeTransport()
                }
            }
        })
        if (observing) {
            watchdog?.cancel()
            watchdog = scope.launch { delay(25_000); if (generation == connectionGeneration) closeTransport() }
        } else armTimeout(25_000, generation, "La conexión de voz tardó demasiado. El micrófono no se activó.")
    }

    fun finishInput() {
        if (!recording) return
        captureDeadline?.cancel(); captureDeadline = null
        commitCapture = true
        recording = false
        emit(current.copy(phase = VoicePhase.WAITING, message = "Terminando de enviar tu consulta…"))
        synchronized(captureLock) { runCatching { recorder?.stop() } }
    }

    fun skipFollowUp() {
        quietFollowUp = false
        if (!recording && current.phase == VoicePhase.CONNECTING) emit(VoiceState())
    }

    fun selectOutputMode(textOnly: Boolean) {
        val selected = if (textOnly) "text" else "audio"
        if (selected == outputMode) return
        if (ready && current.active) cancelResponseForScreenChange()
        outputMode = selected
        if (ready) send(JSONObject().put("type", "output.mode").put("mode", selected))
    }

    fun sendText(text: String): Boolean {
        if (!ready || current.active || text.isBlank()) return false
        turns.cancel(); player.stop(); transcript = ""
        awaitingResponse = true
        emit(VoiceState(VoicePhase.WAITING, "Consulta enviada. Esperando la respuesta de TINO…"))
        if (!send(JSONObject().put("type", "text").put("text", text.take(1000)))) return true
        armTimeout(110_000, connectionGeneration, "La respuesta de voz tardó demasiado. Podés intentar otra vez.")
        return true
    }

    fun cancel(clearTaskContext: Boolean = true, remoteEnded: Boolean = false): Job? {
        val release = captureJob ?: retiringCapture
        val wasActive = current.active
        ++requestEpoch; connectJob?.cancel(); connectJob = null; idleClose?.cancel()
        interruptPlayback(ignoreAcknowledgement = false)
        closeTransport(immediate = clearTaskContext && !remoteEnded)
        if (clearTaskContext) { forgetTaskContext(); logicalIdentity = null; conversationIdle.clear(); lastObservation = null }
        else if (wasActive) conversationIdle.turnFinished(SystemClock.elapsedRealtime())
        emit(VoiceState(message = "Decí Hola Tino para otra consulta."))
        if (clearTaskContext) onConversationEnded()
        return release
    }

    fun disconnect() { cancel() }

    private fun forgetTaskContext() { taskMemory.clear(); onTaskContext(null) }

    /** Closing a technical transport preserves the bounded task and never schedules a new socket. */
    private fun closeTransport(immediate: Boolean = false) {
        ++connectionGeneration
        pendingCapture = false; pendingGuidance = false
        pendingText = null; pendingScreen = null; ignoredCancelAcks = 0
        pendingInputAudio?.fill(0); pendingInputAudio = null
        observing = false; observationRequested = false
        observationSnapshot = null; observationActivity = null; observationBaseline = null
        activityFlush?.cancel(); activityFlush = null; pendingUserActivity = null
        snapshotFlush?.cancel(); snapshotFlush = null; queuedSnapshot = null
        lastScreenContextAt = 0L; ready = false; awaitingResponse = false
        commitCapture = false; recording = false
        turns.cancel(); watchdog?.cancel(); watchdog = null
        captureDeadline?.cancel(); captureDeadline = null
        synchronized(captureLock) { runCatching { recorder?.stop() } }
        captureJob?.let { retiringCapture = it; it.cancel() }; captureJob = null
        player.stop(); playback.clear()
        socket?.let { previous ->
            lastClosedAt = SystemClock.elapsedRealtime()
            beginClosing(previous, endConversation = immediate)
        }
        socket = null; connectionKey = null; transcript = ""
        continuity.transportClosed()
    }

    private fun beginClosing(previous: WebSocket, endConversation: Boolean) {
        closingSocket = previous
        closingIsUserEnd = endConversation
        closingAck = CompletableDeferred()
        closeWait?.cancel()
        val accepted = if (endConversation) previous.send(JSONObject().put("type", "close").toString())
            else previous.close(1000, "transport released")
        if (!accepted) {
            previous.cancel()
            if (!endConversation) { finishClosing(previous); return }
        }
        closeWait = scope.launch {
            delay(SessionContinuityPolicy.OWNER_RELEASE_TIMEOUT_MS)
            if (closingSocket === previous) { previous.cancel(); finishClosing(previous, confirmed = true) }
        }
    }

    private fun finishClosing(previous: WebSocket, confirmed: Boolean = false) {
        if (closingSocket !== previous) return
        if (closingIsUserEnd && !confirmed) return
        closeWait?.cancel(); closeWait = null
        closingSocket = null
        lastClosedAt = SystemClock.elapsedRealtime()
        closingAck?.complete(Unit)
        if (!previous.close(1000, "conversation ended")) previous.cancel()
    }

    fun noteUserRequest() {
        if (conversationIdle.isRequestActive) return
        val now = SystemClock.elapsedRealtime()
        if (conversationIdle.expired(now)) cancel(clearTaskContext = true)
        else if (ready) closeTransport()
        conversationIdle.beginRequest(now)
        idleClose?.cancel(); idleClose = null
    }

    val hasActiveConversation: Boolean get() = conversationIdle.hasConversation(SystemClock.elapsedRealtime())
    val taskMemoryPresent: Boolean get() = taskContext != null
    val transportReady: Boolean get() = ready
    val idleRemainingMs: Long get() = conversationIdle.remaining(SystemClock.elapsedRealtime()) ?: -1L

    fun noteUserActivity(kind: String) {
        if (kind !in setOf("tap", "scroll", "text", "navigation")) return
        val now = SystemClock.elapsedRealtime()
        if (conversationIdle.expired(now)) { cancel(clearTaskContext = true); return }
        if (!conversationIdle.screenAction(now)) return
        armIdleClose()
        if (!ready) {
            if (ObservationResumePolicy.allowed(taskContext != null, hasActiveConversation, conversationIdle.isRequestActive, true)) {
                observationRequested = true
                observationActivity = kind
                observationBaseline = lastObservation
                onObservationRefresh()
            }
            return
        }
        pendingUserActivity = kind
        val wait = (300L - (now - lastUserActivityAt)).coerceAtLeast(0)
        if (wait == 0L) flushUserActivity()
        else if (activityFlush?.isActive != true) activityFlush = scope.launch { delay(wait); flushUserActivity() }
    }

    fun resumeObservation(screen: JSONObject) {
        if (!ObservationResumePolicy.allowed(taskContext != null, hasActiveConversation, conversationIdle.isRequestActive, observationRequested || observing)) return
        observationSnapshot = screen
        if (ready) { sendScreenUpdate(JSONObject().put("type", "screen.context").put("screen", screen).put("requestGuidance", false)); return }
        if (observing && socket != null) return
        if (connectJob?.isActive == true) return
        ++requestEpoch
        reopenObservation(0)
    }

    private fun reopenObservation(attempt: Int) {
        if (!ObservationResumePolicy.allowed(taskContext != null, hasActiveConversation, conversationIdle.isRequestActive, observationRequested || observing)) return
        val identity = logicalIdentity ?: return
        val snapshot = observationSnapshot ?: return
        val kind = observationActivity; val baseline = observationBaseline; val epoch = requestEpoch
        connectJob?.cancel()
        closeTransport()
        observing = true; observationRequested = true; observationSnapshot = snapshot
        observationActivity = kind; observationBaseline = baseline
        connectJob = scope.launch {
            closingAck?.await()
            val release = (SessionContinuityPolicy.LEASE_RELEASE_MS - (SystemClock.elapsedRealtime() - lastClosedAt)).coerceAtLeast(0)
            val retry = if (attempt == 0) 0L else SessionContinuityPolicy.retryDelay(attempt, kotlin.random.Random.nextLong(201)) ?: return@launch
            delay(maxOf(release, retry))
            if (epoch != requestEpoch || !hasActiveConversation || taskContext == null || conversationIdle.isRequestActive) return@launch
            openConnection(identity, attempt)
        }
    }

    private fun reducedObservation(screen: JSONObject): JSONObject = JSONObject()
        .put("id", screen.optString("id")).put("packageName", screen.optString("packageName"))
        .put("width", screen.optInt("width")).put("height", screen.optInt("height")).put("controls", screen.optJSONArray("controls"))

    private fun sendObserved(message: JSONObject): Boolean {
        val accepted = send(message)
        if (accepted && message.optString("type") == "screen.context") message.optJSONObject("screen")?.let {
            lastObservation = reducedObservation(it)
            if (observing) observationSnapshot = it
        }
        return accepted
    }

    private fun flushUserActivity() {
        val kind = pendingUserActivity ?: return
        pendingUserActivity = null
        if (!ready || !hasActiveConversation) return
        if (send(JSONObject().put("type", "user.activity").put("kind", kind), false)) lastUserActivityAt = SystemClock.elapsedRealtime()
    }

    /** Preserve activity-before-observation ordering while retaining only the newest pending snapshot. */
    private fun sendScreenUpdate(message: JSONObject): Boolean {
        if (!ready) return false
        if (pendingUserActivity == null) {
            snapshotFlush?.cancel(); snapshotFlush = null; queuedSnapshot = null
            return sendObserved(message)
        }
        queuedSnapshot = message
        snapshotFlush?.cancel()
        val generation = connectionGeneration
        snapshotFlush = scope.launch {
            delay((300L - (SystemClock.elapsedRealtime() - lastUserActivityAt)).coerceAtLeast(0))
            if (generation != connectionGeneration || !ready) return@launch
            flushUserActivity()
            val snapshot = queuedSnapshot; queuedSnapshot = null
            if (snapshot != null) sendObserved(snapshot)
        }
        return true
    }

    fun abandonPreparation() {
        if (conversationIdle.isRequestActive && !current.active && !pendingCapture) {
            requestedAttemptFinished()
            emit(VoiceState())
        }
    }

    fun requestedAttemptFinished() {
        conversationIdle.turnFinished(SystemClock.elapsedRealtime())
        armIdleClose()
    }

    private fun armIdleClose() {
        idleClose?.cancel()
        val remaining = conversationIdle.remaining(SystemClock.elapsedRealtime()) ?: return
        idleClose = scope.launch {
            delay(remaining)
            if (conversationIdle.expired(SystemClock.elapsedRealtime())) cancel(clearTaskContext = true)
        }
    }

    private fun emit(value: VoiceState) {
        current = value; onState(value)
        if (value.active) idleClose?.cancel() else armIdleClose()
    }
    private fun fail(message: String) {
        val requested = current.active
        val audible = SessionContinuityPolicy.shouldSpeakError(quietFollowUp, inputHadSpeech)
        closeTransport()
        if (requested) conversationIdle.turnFinished(SystemClock.elapsedRealtime())
        emit(VoiceState(VoicePhase.ERROR, message.take(220), audibleError = audible))
    }
    private fun send(message: JSONObject, failOnError: Boolean = true): Boolean {
        val ws = socket
        val accepted = ws != null && ws.queueSize() <= RealtimePolicy.MAX_SOCKET_QUEUE_BYTES && ws.send(message.toString())
        if (!accepted && failOnError) fail("La conexión no pudo enviar el audio a tiempo. El micrófono quedó apagado.")
        return accepted
    }
    private fun armTimeout(ms: Long, generation: Long, message: String) {
        watchdog?.cancel()
        watchdog = scope.launch { delay(ms); if (generation == connectionGeneration) fail(message) }
    }
    private fun beginRequested(generation: Long) {
        if (!send(JSONObject().put("type", "output.mode").put("mode", outputMode))) return
        val guidance = pendingGuidance; pendingGuidance = false
        if (guidance) {
            pendingCapture = false; awaitingResponse = true
            emit(VoiceState(VoicePhase.WAITING, "La pantalla cambió. TINO prepara el próximo paso…"))
        }
        val screen = pendingScreen; pendingScreen = null
        if (screen != null) {
            if (!sendScreenUpdate(JSONObject().put("type", "screen.context").put("screen", screen).put("requestGuidance", guidance))) return
            lastScreenContextAt = SystemClock.elapsedRealtime()
        }
        if (screen == null) sendScreenUpdate(JSONObject().put("type", "screen.clear"))
        if (guidance) {
            armTimeout(110_000, generation, "No pude preparar el siguiente paso. Podés volver a decir Hola Tino.")
            return
        }
        val text = pendingText; pendingText = null
        if (text != null) {
            pendingInputAudio?.fill(0); pendingInputAudio = null
            pendingCapture = false
            emit(VoiceState())
            sendText(text)
        } else {
            val audio = pendingInputAudio; pendingInputAudio = null
            if (audio != null) sendVerifiedAudio(audio, generation) else beginCapture(generation)
        }
    }

    /** Only PCM clipped after a verified local wake reaches this path. No new microphone is opened. */
    private fun sendVerifiedAudio(audio: ByteArray, generation: Long) {
        pendingCapture = false
        if (audio.size !in RealtimePolicy.MIN_INPUT_BYTES..RealtimePolicy.MAX_INPUT_BYTES || audio.size % 2 != 0) {
            audio.fill(0); fail("No pude preparar el audio de la consulta."); return
        }
        if (!send(JSONObject().put("type", "audio.start"))) { audio.fill(0); return }
        inputHadSpeech = true
        emit(VoiceState(VoicePhase.WAITING, "Enviando tu consulta…"))
        val ws = socket
        captureJob = scope.launch(Dispatchers.IO) {
            var problem = false
            var uploaded = false
            try {
                var offset = 0
                val startedAt = SystemClock.elapsedRealtime()
                while (isActive && generation == connectionGeneration && offset < audio.size) {
                    while (isActive && ws != null && ws.queueSize() > RealtimePolicy.MAX_SOCKET_QUEUE_BYTES / 2) {
                        if (SystemClock.elapsedRealtime() - startedAt > 20_000) throw IllegalStateException("Audio upload timeout")
                        delay(20)
                    }
                    if (!isActive || generation != connectionGeneration) return@launch
                    val end = minOf(audio.size, offset + RealtimePolicy.CHUNK_BYTES)
                    val chunk = audio.copyOfRange(offset, end)
                    val payload = JSONObject().put("type", "audio.append").put("audio", RealtimePolicy.encodeInput(chunk, chunk.size))
                    chunk.fill(0)
                    if (ws == null || !ws.send(payload.toString())) throw IllegalStateException("Audio upload stopped")
                    offset = end
                }
                uploaded = offset == audio.size
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { problem = true }
            finally {
                audio.fill(0)
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
                    if (generation == connectionGeneration) {
                        if (problem) fail("No pude enviar el audio de la consulta.")
                        else if (uploaded) {
                            while (snapshotFlush?.isActive == true) snapshotFlush?.join()
                            if (generation == connectionGeneration && isActive) {
                                awaitingResponse = true
                                if (send(JSONObject().put("type", "audio.commit"))) armTimeout(110_000, generation, "La respuesta tardó demasiado.")
                            }
                        }
                    }
                }
            }
        }
    }
    fun updateScreen(screen: JSONObject, requestGuidance: Boolean): Boolean {
        if (current.active && requestGuidance) return false
        if (!requestGuidance && !ready && (observationRequested || observing)) { resumeObservation(screen); return true }
        if (requestGuidance) noteUserRequest()
        if (requestGuidance && (!ready || continuity.shouldRenewBeforeTurn(SystemClock.elapsedRealtime()))) {
            val identity = logicalIdentity ?: return false
            request(identity, screen, null, outputMode, guidance = true)
            return true
        }
        if (!ready) return false
        if (requestGuidance) {
            turns.cancel(); player.stop(); transcript = ""; awaitingResponse = true
            emit(VoiceState(VoicePhase.WAITING, "La pantalla cambió. TINO prepara el próximo paso…"))
        }
        val accepted = sendScreenUpdate(JSONObject().put("type", "screen.context").put("screen", screen).put("requestGuidance", requestGuidance))
        if (accepted) lastScreenContextAt = SystemClock.elapsedRealtime()
        if (accepted && requestGuidance) armTimeout(110_000, connectionGeneration, "No pude preparar el siguiente paso. Tocá Actualizar pantalla para intentar otra vez.")
        return accepted
    }
    /** Refresh the observation without ending this user's audio input or starting a model response. */
    fun updateScreenWhileListening(screen: JSONObject?): Boolean {
        if (current.phase !in listOf(VoicePhase.CONNECTING, VoicePhase.LISTENING)) return false
        pendingScreen = screen
        if (!ready) return current.phase == VoicePhase.CONNECTING
        if (screen == null) return sendScreenUpdate(JSONObject().put("type", "screen.clear"))
        val now = SystemClock.elapsedRealtime()
        if (lastScreenContextAt > 0 && now - lastScreenContextAt < 750L) {
            // Removing the previous snapshot is always safe; never keep an old target because of throttling.
            sendScreenUpdate(JSONObject().put("type", "screen.clear"))
            return false
        }
        val accepted = sendScreenUpdate(JSONObject().put("type", "screen.context").put("screen", screen).put("requestGuidance", false))
        if (accepted) lastScreenContextAt = now
        return accepted
    }
    private fun interruptPlayback(ignoreAcknowledgement: Boolean) {
        if (!ready) return
        val checkpoint = playback.checkpoint(player.playedFrames())
        val message = JSONObject().put("type", "cancel")
        checkpoint?.let { message.put("playback", JSONObject().put("turnId", it.turnId).put("itemId", it.itemId)
            .put("contentIndex", it.contentIndex).put("audioEndMs", it.audioEndMs)) }
        if (send(message, false) && ignoreAcknowledgement) ++ignoredCancelAcks
        playback.clear()
    }
    fun cancelResponseForScreenChange() {
        if (!ready) return
        val hadActiveReply = current.phase in listOf(VoicePhase.WAITING, VoicePhase.SPEAKING)
        if (awaitingResponse || current.phase in listOf(VoicePhase.WAITING, VoicePhase.SPEAKING)) {
            interruptPlayback(ignoreAcknowledgement = true)
        }
        awaitingResponse = false; turns.cancel(); player.stop(); watchdog?.cancel()
        sendScreenUpdate(JSONObject().put("type", "screen.clear"))
        if (hadActiveReply) conversationIdle.turnFinished(SystemClock.elapsedRealtime())
        emit(VoiceState(message = "Pantalla actualizada."))
    }
    fun clearScreen() {
        // Observation may disappear while audio continues (navigation, overlays, unavailable frame).
        // Only explicit user cancellation/new input interrupts the answer or recording.
        pendingScreen = null
        sendScreenUpdate(JSONObject().put("type", "screen.clear"))
    }
    private fun handle(event: JSONObject, generation: Long) {
        when (event.optString("type")) {
            "ready" -> {
                val format = event.optJSONObject("audio")
                if (event.optString("model") != RealtimePolicy.MODEL || format?.optString("encoding") != "pcm16" ||
                    format.optInt("sampleRate") != RealtimePolicy.SAMPLE_RATE || format.optInt("channels") != 1) {
                    fail("El servidor no ofrece el modelo o formato de audio esperado."); return
                }
                ready = true; watchdog?.cancel()
                val session = event.optJSONObject("session")
                continuity.ready(transportStartedAt,
                    session?.optLong("maxSessionMs", SessionContinuityPolicy.DEFAULT_MAX_MS) ?: SessionContinuityPolicy.DEFAULT_MAX_MS,
                    session?.optLong("renewAfterMs", SessionContinuityPolicy.DEFAULT_RENEW_MS) ?: SessionContinuityPolicy.DEFAULT_RENEW_MS,
                    session?.optBoolean("renewable", false) == true)
                taskContext?.let { context ->
                    if (!send(JSONObject().put("type", "resume.context").put("context", JSONObject()
                        .put("goal", context.goal).put("targetApp", context.targetApp).put("progress", context.progress)))) return
                }
                if (observing) {
                    observationActivity?.let { kind ->
                        val activity = JSONObject().put("type", "user.activity").put("kind", kind)
                        observationBaseline?.let { activity.put("baseline", it) }
                        send(activity, false)
                    }
                    observationSnapshot?.let { sendScreenUpdate(JSONObject().put("type", "screen.context").put("screen", it).put("requestGuidance", false)) }
                    observationActivity = null; observationBaseline = null
                    return
                }
                if (pendingCapture) beginRequested(generation)
            }
            "conversation.ended" -> { cancel(clearTaskContext = true, remoteEnded = true) }
            "renewal.required" -> {
                continuity.requireRenewal()
                if (!current.active && continuity.renewable) {
                    val alive = taskContext != null && hasActiveConversation
                    closeTransport()
                    if (alive) { observationRequested = true; onObservationRefresh() }
                }
            }
            "task.context" -> {
                val context = event.optJSONObject("context") ?: return
                // Empty goal is the server's end signal; lease release/ACK may follow later.
                // Clear visible/RAM state now and reserve the closing owner before another wake.
                if (context.optString("goal").isBlank()) {
                    cancel(clearTaskContext = true)
                    return
                }
                taskContext = TaskContext.bounded(context.optString("goal"), context.optString("targetApp"), context.optString("progress"))
                onTaskContext(taskContext)
            }
            "response.started" -> {
                if (!awaitingResponse) return
                val turnId = event.optString("turnId")
                val responseMode = event.optString("outputMode", outputMode)
                if (responseMode != outputMode) { fail("El servidor no respetó el modo de respuesta elegido. Volvé a intentar."); return }
                if (turns.begin(turnId)) {
                    transcript = ""
                    playback.start(turnId)
                    if (outputMode == "audio") player.start(turnId)
                    emit(VoiceState(VoicePhase.WAITING, "TINO está preparando la respuesta…"))
                }
            }
            "audio.delta" -> {
                val turnId = event.optString("turnId")
                if (!turns.accepts(turnId) || outputMode == "text") return
                val bytes = RealtimePolicy.decodeOutput(event.getString("audio"))
                if (!playback.append(event.optString("itemId"), event.optInt("contentIndex", -1), bytes.size)) {
                    fail("La respuesta de voz no identificó correctamente su audio. Podés usar una consulta escrita."); return
                }
                if (!player.append(turnId, bytes)) { fail("La respuesta de voz superó el límite de reproducción. Podés volver a preguntar."); return }
                emit(current.copy(phase = VoicePhase.SPEAKING, message = "TINO te está respondiendo. Podés cancelar cuando quieras."))
            }
            "text.delta" -> {
                if (!turns.accepts(event.optString("turnId"))) return
                transcript = (transcript + event.optString("delta")).take(12_000)
                emit(current.copy(partialText = transcript))
            }
            "text.done" -> {
                val turnId = event.optString("turnId")
                if (!turns.accepts(turnId) || lastAnswerTurn == turnId) return
                transcript = event.optString("text").take(12_000)
                if (transcript.isNotBlank()) { lastAnswerTurn = turnId; onAnswer(transcript) }
                emit(current.copy(partialText = ""))
            }
            "guidance.offer" -> if (turns.accepts(event.optString("turnId")) && event.optString("flowId") == "whatsapp-location") onGuidanceOffer()
            "guidance.step" -> if (turns.accepts(event.optString("turnId"))) {
                val controlId = event.optString("controlId").takeIf { it.isNotBlank() && it != "null" }
                onStep(ScreenStep(event.optString("screenId"), controlId, event.optString("instruction").take(2000)))
            }
            "response.done" -> {
                val turnId = event.optString("turnId")
                if (!turns.accepts(turnId)) return
                awaitingResponse = false; watchdog?.cancel()
                if (event.optString("status") != "completed") {
                    player.stop(); turns.cancel()
                    conversationIdle.turnFinished(SystemClock.elapsedRealtime())
                    emit(VoiceState(VoicePhase.ERROR, "La respuesta no se completó. Podés intentar otra vez."))
                    return
                }
                if (lastAnswerTurn != turnId && transcript.isNotBlank()) { lastAnswerTurn = turnId; onAnswer(transcript) }
                if (outputMode == "audio") player.finish(turnId)
                else {
                    playback.clear()
                    conversationIdle.turnFinished(SystemClock.elapsedRealtime())
                    emit(VoiceState(message = "Respuesta escrita lista. El audio está apagado."))
                }
            }
            "cancelled" -> {
                if (ignoredCancelAcks > 0) { --ignoredCancelAcks; return }
                if (recording) return // Delayed acknowledgement of audio.start cancelling a prior response.
                if (current.active) conversationIdle.turnFinished(SystemClock.elapsedRealtime())
                player.stop(); playback.clear(); turns.cancel(); awaitingResponse = false; watchdog?.cancel()
                emit(VoiceState(message = "Respuesta cancelada."))
            }
            "error" -> {
                if (SessionContinuityPolicy.leaseRetryAllowed(event.optString("code"), observing, recording, connectionAttempt)) { reopenObservation(connectionAttempt + 1); return }
                if (observing) { closeTransport(); return }
                if (SessionContinuityPolicy.leaseRetryAllowed(event.optString("code"), pendingCapture, recording, connectionAttempt)) {
                    reopenPending(connectionAttempt + 1)
                    return
                }
                // This string is the TINO server's sanitized public error, never the upstream object.
                fail(event.optString("message").takeIf { it.isNotBlank() } ?: "La voz no está disponible. Podés usar texto.")
            }
        }
    }

    @SuppressLint("MissingPermission") // The foreground voice owner obtains RECORD_AUDIO before starting this capture.
    private fun beginCapture(generation: Long) {
        if (!pendingCapture || generation != connectionGeneration || !ready) return
        pendingCapture = false
        commitCapture = false
        turns.cancel()
        if (!send(JSONObject().put("type", "audio.start"))) return
        recording = true
        // A short answer is allowed inside the current task, but capture still requires Hola Tino or an explicit mic action.
        val activity = VoiceActivityPolicy(followUp = taskContext != null)
        val silentTimeout = AtomicBoolean(false)
        emit(VoiceState(VoicePhase.CONNECTING, "Preparando el micrófono. Hablá después de la señal."))
        val captureSocket = socket
        captureJob = scope.launch(Dispatchers.IO) {
            var captured = 0
            var problem: String? = null
            var local: AudioRecord? = null
            var leased = false
            try {
                if (!isActive || !recording || generation != connectionGeneration) return@launch
                if (!LocalMicrophoneLease.acquire("realtime")) { problem = "El micrófono todavía se está liberando. Volvé a decir Hola Tino."; return@launch }
                leased = true
                // This cue is emitted only after the server is ready. No microphone exists yet,
                // so neither the greeting nor the tone can be uploaded as the person's speech.
                try { playListeningCue() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: RuntimeException) {
                    problem = "No pude reproducir la señal para empezar a escuchar. Volvé a activar TINO."
                    return@launch
                }
                if (!isActive || !recording || generation != connectionGeneration) return@launch
                val minimum = AudioRecord.getMinBufferSize(RealtimePolicy.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                local = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RealtimePolicy.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, RealtimePolicy.CHUNK_BYTES * 4))
                check(local.state == AudioRecord.STATE_INITIALIZED)
                synchronized(captureLock) {
                    if (!isActive || !recording || generation != connectionGeneration) return@launch
                    recorder = local
                    local.startRecording()
                }
                check(local.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                withContext(Dispatchers.Main.immediate) {
                    if (generation != connectionGeneration || !recording) throw CancellationException("Voice input cancelled")
                    emit(VoiceState(VoicePhase.LISTENING, "Te escucho. Hablá con naturalidad; envío tu consulta cuando hacés una pausa."))
                    captureDeadline = scope.launch captureClock@ {
                        delay(VoiceActivityPolicy.NO_SPEECH_TIMEOUT_MS)
                        if (generation == connectionGeneration && recording && !activity.hasSpeech) {
                            silentTimeout.set(true)
                            commitCapture = false
                            recording = false
                            synchronized(captureLock) { runCatching { recorder?.stop() } }
                            return@captureClock
                        }
                        delay(RealtimePolicy.MAX_INPUT_SECONDS * 1000L - VoiceActivityPolicy.NO_SPEECH_TIMEOUT_MS)
                        if (generation == connectionGeneration && recording) finishInput()
                    }
                }
                val chunk = ByteArray(RealtimePolicy.CHUNK_BYTES)
                while (isActive && recording && generation == connectionGeneration && captured < RealtimePolicy.MAX_INPUT_BYTES) {
                    val size = minOf(chunk.size, RealtimePolicy.MAX_INPUT_BYTES - captured)
                    val read = local.read(chunk, 0, size, AudioRecord.READ_BLOCKING)
                    if (read < 0) { if (recording) problem = "El teléfono no pudo capturar el audio."; break }
                    if (read == 0) continue
                    if (!recording || generation != connectionGeneration) break
                    if (read % 2 != 0) { problem = "El micrófono entregó un bloque de audio incompleto. Volvé a hablar."; break }
                    val aligned = read
                    val message = JSONObject().put("type", "audio.append").put("audio", RealtimePolicy.encodeInput(chunk, aligned))
                    val ws = captureSocket
                    if (ws == null || ws.queueSize() > RealtimePolicy.MAX_SOCKET_QUEUE_BYTES || !ws.send(message.toString())) {
                        problem = "La conexión es demasiado lenta para enviar el audio."; break
                    }
                    captured += aligned
                    val decision = activity.observe(chunk, aligned)
                    inputHadSpeech = activity.hasSpeech
                    when (decision) {
                        VoiceActivityPolicy.Decision.END_OF_SPEECH, VoiceActivityPolicy.Decision.MAX_DURATION -> {
                            commitCapture = true
                            recording = false
                            break
                        }
                        VoiceActivityPolicy.Decision.NO_SPEECH -> {
                            silentTimeout.set(true)
                            recording = false
                            break
                        }
                        VoiceActivityPolicy.Decision.CONTINUE -> Unit
                    }
                }
                if (generation == connectionGeneration && captured >= RealtimePolicy.MAX_INPUT_BYTES) commitCapture = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == connectionGeneration) problem = "No pude iniciar el micrófono. Revisá su permiso o usá texto." }
            finally {
                if (generation == connectionGeneration) recording = false
                synchronized(captureLock) {
                    runCatching { local?.stop() }; runCatching { local?.release() }
                    if (recorder === local) recorder = null
                }
                if (leased) LocalMicrophoneLease.release("realtime")
                scope.launch {
                    if (generation != connectionGeneration) return@launch
                    captureDeadline?.cancel(); captureDeadline = null
                    if (problem != null) { fail(problem!!); return@launch }
                    if (silentTimeout.get() || (commitCapture && !activity.hasSpeech)) {
                        // Drop only this input: an empty attempt must not consume another connection reservation.
                        if (send(JSONObject().put("type", "cancel"), failOnError = false)) ++ignoredCancelAcks
                        commitCapture = false
                        awaitingResponse = false
                        turns.cancel()
                        val quiet = quietFollowUp
                        quietFollowUp = false
                        conversationIdle.turnFinished(SystemClock.elapsedRealtime())
                        if (quiet) emit(VoiceState())
                        else emit(VoiceState(VoicePhase.ERROR, "No escuché voz con claridad. Podés volver a decir Hola Tino."))
                        return@launch
                    }
                    if (!commitCapture) return@launch
                    commitCapture = false
                    if (captured < RealtimePolicy.MIN_INPUT_BYTES) { fail("La consulta fue muy breve. Volvé a hablar con TINO."); return@launch }
                    while (snapshotFlush?.isActive == true) {
                        snapshotFlush?.join()
                        if (generation != connectionGeneration) return@launch
                    }
                    awaitingResponse = true
                    emit(VoiceState(VoicePhase.WAITING, "Consulta enviada. Esperando la respuesta de TINO…"))
                    if (send(JSONObject().put("type", "audio.commit"))) armTimeout(110_000, generation, "La respuesta de voz tardó demasiado. Podés intentar otra vez.")
                }
            }
        }
    }

    private suspend fun playListeningCue() {
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
        try {
            check(tone.startTone(ToneGenerator.TONE_PROP_BEEP, 100))
            delay(100)
        } finally { tone.release() }
        delay(150)
    }
}

private class PcmPlayer(
    private val scope: CoroutineScope,
    private val onEnded: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private data class Packet(val pcm: ByteArray? = null)
    private var job: Job? = null
    private var queue: Channel<Packet>? = null
    @Volatile private var track: AudioTrack? = null
    private val trackLock = Any()
    @Volatile private var generation = 0L
    private var turn: String? = null
    private var totalBytes = 0
    @Volatile private var completedFrames = 0L
    fun playedFrames(): Long = synchronized(trackLock) {
        runCatching { track?.playbackHeadPosition?.toLong()?.and(0xffffffffL) }.getOrNull() ?: completedFrames
    }
    fun start(turnId: String) {
        stop()
        turn = turnId
        totalBytes = 0
        completedFrames = 0L
        val localGeneration = generation
        val packets = Channel<Packet>(2048); queue = packets
        job = scope.launch(Dispatchers.IO) {
            var local: AudioTrack? = null
            var framesWritten = 0L
            try {
                val minimum = AudioTrack.getMinBufferSize(RealtimePolicy.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                local = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(RealtimePolicy.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum * 2, 9_600)).setTransferMode(AudioTrack.MODE_STREAM).build()
                check(local.state == AudioTrack.STATE_INITIALIZED)
                synchronized(trackLock) {
                    if (!isActive || generation != localGeneration) return@launch
                    track = local
                    local.play()
                }
                for (packet in packets) {
                    if (!isActive || generation != localGeneration) break
                    val pcm = packet.pcm
                    if (pcm == null) {
                        // Generation can finish before the already queued PCM has played.
                        val deadline = SystemClock.elapsedRealtime() + 95_000
                        while (isActive && generation == localGeneration && (local.playbackHeadPosition.toLong() and 0xffffffffL) < framesWritten && SystemClock.elapsedRealtime() < deadline) delay(20)
                        val played = local.playbackHeadPosition.toLong() and 0xffffffffL
                        synchronized(trackLock) {
                            if (generation == localGeneration) completedFrames = played
                        }
                        if (generation == localGeneration && isActive) scope.launch {
                            if (generation == localGeneration) {
                                if (played >= framesWritten) onEnded(turnId)
                                else onFailure("La reproducción se interrumpió antes de terminar. Podés intentar una consulta escrita.")
                            }
                        }
                        break
                    }
                    var offset = 0
                    while (offset < pcm.size && isActive && generation == localGeneration) {
                        val written = local.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                        check(written > 0)
                        offset += written; framesWritten += written / 2
                    }
                }
            } catch (_: Exception) {
                if (generation == localGeneration) scope.launch { if (generation == localGeneration) onFailure("No pude reproducir la voz. Podés leer la respuesta y volver a intentar.") }
            } finally {
                synchronized(trackLock) {
                    runCatching { local?.pause() }; runCatching { local?.flush() }; runCatching { local?.release() }
                    if (track === local) track = null
                }
                packets.close()
            }
        }
    }
    fun append(turnId: String, pcm: ByteArray): Boolean {
        if (turn != turnId || totalBytes + pcm.size > RealtimePolicy.MAX_OUTPUT_BYTES) return false
        totalBytes += pcm.size
        return queue?.trySend(Packet(pcm))?.isSuccess == true
    }
    fun finish(turnId: String) {
        if (turn == turnId && queue?.trySend(Packet())?.isSuccess != true) onFailure("No pude completar la reproducción de la respuesta.")
    }
    fun stop() {
        ++generation
        turn = null
        queue?.cancel(); queue = null
        synchronized(trackLock) {
            runCatching { track?.pause() }; runCatching { track?.flush() }; runCatching { track?.release() }
            track = null
        }
        job?.cancel(); job = null
    }
}
