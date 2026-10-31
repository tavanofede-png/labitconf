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
data class VoiceState(val phase: VoicePhase = VoicePhase.IDLE, val message: String = "Voz con TINO", val partialText: String = "") {
    val active get() = phase in listOf(VoicePhase.CONNECTING, VoicePhase.LISTENING, VoicePhase.WAITING, VoicePhase.SPEAKING)
}

/** All protocol state is main-thread confined; PCM capture/playback runs only on Dispatchers.IO. */
class RealtimeVoice(
    private val scope: CoroutineScope,
    private val onState: (VoiceState) -> Unit,
    private val onAnswer: (String) -> Unit,
    private val onGuidanceOffer: () -> Unit,
    private val onStep: (ScreenStep) -> Unit = {},
) {
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var connectionGeneration = 0L
    private var ready = false
    private var pendingCapture = false
    private var pendingText: String? = null
    private var pendingScreen: JSONObject? = null
    private var lastScreenContextAt = 0L
    private var ignoredCancelAcks = 0
    private var connectionKey: Pair<String, String>? = null
    private var watchdog: Job? = null
    private var captureJob: Job? = null
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
                emit(VoiceState(message = "Respuesta terminada. Podés volver a hablar."))
            }
        }
    }, onFailure = { message -> fail(message) })

    fun start(baseUrl: String, installationToken: String, screen: JSONObject? = null, text: String? = null, textOnly: Boolean = false) {
        require(installationToken.isNotBlank()) { "Conectá este teléfono desde Ajustes." }
        if (ready) interruptPlayback(ignoreAcknowledgement = true)
        player.stop()
        turns.cancel()
        transcript = ""
        awaitingResponse = false
        pendingCapture = true
        outputMode = if (textOnly) "text" else "audio"
        if (ready && connectionKey == (baseUrl to installationToken)) {
            pendingScreen = screen; pendingText = text
            beginRequested(connectionGeneration)
            return
        }
        disconnect()
        outputMode = if (textOnly) "text" else "audio"
        pendingCapture = true
        pendingScreen = screen; pendingText = text
        val generation = ++connectionGeneration
        connectionKey = baseUrl to installationToken
        emit(VoiceState(VoicePhase.CONNECTING, "Conectando con OpenAI Realtime. El micrófono todavía está apagado."))
        val request = Request.Builder().url(RealtimePolicy.socketUrl(baseUrl, BuildConfig.DEBUG))
            .header("Authorization", "Bearer $installationToken").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    if (generation != connectionGeneration) return@launch
                    if (text.length > 200_000) { fail("La respuesta de voz excedió el tamaño permitido."); return@launch }
                    runCatching { handle(JSONObject(text), generation) }
                        .onFailure { fail("La respuesta de voz no tuvo un formato válido. Podés volver a intentar.") }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Never display throwable messages, response bodies, headers or credentials.
                val status = response?.code
                response?.close()
                scope.launch { if (generation == connectionGeneration) fail(when (status) {
                    401, 403 -> "El servidor no autorizó la voz. Revisá la conexión del teléfono desde Ajustes."
                    503 -> "OpenAI Realtime no está configurado o disponible en el servidor."
                    429 -> "Hay muchas sesiones de voz. Esperá un momento y volvé a intentar."
                    else -> "No pude mantener la conexión de voz. El micrófono quedó apagado. Podés usar texto."
                }) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { if (generation == connectionGeneration) {
                    val active = current.active
                    disconnect()
                    emit(VoiceState(if (active) VoicePhase.ERROR else VoicePhase.IDLE,
                        if (active) "La sesión de voz se cerró. El micrófono quedó apagado." else "Sesión de voz cerrada."))
                } }
            }
        })
        armTimeout(25_000, generation, "La conexión de voz tardó demasiado. El micrófono no se activó.")
    }

    fun finishInput() {
        if (!recording) return
        captureDeadline?.cancel(); captureDeadline = null
        commitCapture = true
        recording = false
        emit(current.copy(phase = VoicePhase.WAITING, message = "Terminando de enviar tu consulta…"))
        synchronized(captureLock) { runCatching { recorder?.stop() } }
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

    fun cancel() {
        interruptPlayback(ignoreAcknowledgement = false)
        disconnect()
        emit(VoiceState(message = "Voz cancelada. Micrófono y reproducción apagados."))
    }

    fun disconnect() {
        ++connectionGeneration
        pendingCapture = false
        pendingText = null; pendingScreen = null; ignoredCancelAcks = 0
        lastScreenContextAt = 0L
        ready = false
        awaitingResponse = false
        commitCapture = false
        recording = false
        turns.cancel()
        watchdog?.cancel(); watchdog = null
        captureDeadline?.cancel(); captureDeadline = null
        synchronized(captureLock) { runCatching { recorder?.stop() } }
        captureJob?.cancel(); captureJob = null
        player.stop()
        playback.clear()
        socket?.cancel(); socket = null
        connectionKey = null
        transcript = ""
    }

    private fun emit(value: VoiceState) { current = value; onState(value) }
    private fun fail(message: String) {
        disconnect()
        emit(VoiceState(VoicePhase.ERROR, message.take(220)))
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
        val screen = pendingScreen; pendingScreen = null
        if (screen != null) {
            if (!send(JSONObject().put("type", "screen.context").put("screen", screen).put("requestGuidance", false))) return
            lastScreenContextAt = SystemClock.elapsedRealtime()
        }
        if (screen == null) send(JSONObject().put("type", "screen.clear"))
        val text = pendingText; pendingText = null
        if (text != null) {
            pendingCapture = false
            emit(VoiceState())
            sendText(text)
        } else beginCapture(generation)
    }
    fun updateScreen(screen: JSONObject, requestGuidance: Boolean): Boolean {
        if (!ready || current.active) return false
        if (requestGuidance) {
            turns.cancel(); player.stop(); transcript = ""; awaitingResponse = true
            emit(VoiceState(VoicePhase.WAITING, "La pantalla cambió. TINO prepara el próximo paso…"))
        }
        val accepted = send(JSONObject().put("type", "screen.context").put("screen", screen).put("requestGuidance", requestGuidance))
        if (accepted) lastScreenContextAt = SystemClock.elapsedRealtime()
        if (accepted && requestGuidance) armTimeout(110_000, connectionGeneration, "No pude preparar el siguiente paso. Tocá Actualizar pantalla para intentar otra vez.")
        return accepted
    }
    /** Refresh the observation without ending this user's audio input or starting a model response. */
    fun updateScreenWhileListening(screen: JSONObject?): Boolean {
        if (current.phase !in listOf(VoicePhase.CONNECTING, VoicePhase.LISTENING)) return false
        pendingScreen = screen
        if (!ready) return current.phase == VoicePhase.CONNECTING
        if (screen == null) return send(JSONObject().put("type", "screen.clear"))
        val now = SystemClock.elapsedRealtime()
        if (lastScreenContextAt > 0 && now - lastScreenContextAt < 750L) {
            // Removing the previous snapshot is always safe; never keep an old target because of throttling.
            send(JSONObject().put("type", "screen.clear"))
            return false
        }
        val accepted = send(JSONObject().put("type", "screen.context").put("screen", screen).put("requestGuidance", false))
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
        if (awaitingResponse || current.phase in listOf(VoicePhase.WAITING, VoicePhase.SPEAKING)) {
            interruptPlayback(ignoreAcknowledgement = true)
        }
        awaitingResponse = false; turns.cancel(); player.stop(); watchdog?.cancel()
        send(JSONObject().put("type", "screen.clear"), false)
        emit(VoiceState(message = "La pantalla cambió. Actualizando el próximo paso…"))
    }
    fun clearScreen() {
        cancelResponseForScreenChange()
        send(JSONObject().put("type", "screen.clear"), false)
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
                if (pendingCapture) beginRequested(generation)
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
                    emit(VoiceState(VoicePhase.ERROR, "La respuesta no se completó. Podés intentar otra vez."))
                    return
                }
                if (lastAnswerTurn != turnId && transcript.isNotBlank()) { lastAnswerTurn = turnId; onAnswer(transcript) }
                if (outputMode == "audio") player.finish(turnId)
                else {
                    playback.clear()
                    emit(VoiceState(message = "Respuesta escrita lista. El audio está apagado."))
                }
            }
            "cancelled" -> {
                if (ignoredCancelAcks > 0) { --ignoredCancelAcks; return }
                if (recording) return // Delayed acknowledgement of audio.start cancelling a prior response.
                player.stop(); playback.clear(); turns.cancel(); awaitingResponse = false; watchdog?.cancel()
                emit(VoiceState(message = "Respuesta cancelada."))
            }
            "error" -> {
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
        val activity = VoiceActivityPolicy()
        val silentTimeout = AtomicBoolean(false)
        emit(VoiceState(VoicePhase.CONNECTING, "Preparando el micrófono. Hablá después de la señal."))
        val captureSocket = socket
        captureJob = scope.launch(Dispatchers.IO) {
            var captured = 0
            var problem: String? = null
            var local: AudioRecord? = null
            try {
                if (!isActive || !recording || generation != connectionGeneration) return@launch
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
                    when (activity.observe(chunk, aligned)) {
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
                        emit(VoiceState(VoicePhase.ERROR, "No escuché voz con claridad. Volvé a hablar o escribile a TINO."))
                        return@launch
                    }
                    if (!commitCapture) return@launch
                    commitCapture = false
                    if (captured < RealtimePolicy.MIN_INPUT_BYTES) { fail("La consulta fue muy breve. Volvé a hablar con TINO."); return@launch }
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
