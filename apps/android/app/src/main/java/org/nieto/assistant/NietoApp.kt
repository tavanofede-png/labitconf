package org.nieto.assistant

import android.app.Application
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.nieto.assistant.data.LocalSettings
import org.nieto.assistant.data.NietoApi
import org.nieto.assistant.flow.*
import org.nieto.assistant.voice.RealtimeVoice
import org.nieto.assistant.voice.VoicePhase
import org.nieto.assistant.voice.VoiceState
import java.util.Locale

data class ChatLine(val fromUser: Boolean, val text: String)
data class AppState(
    val guide: GuideState = GuideState(),
    val chat: List<ChatLine> = listOf(ChatLine(false, "Hola, soy NIETO. Te acompaño a aprender a usar tu teléfono, de a un paso.")),
    val status: String = "Tu ritmo. Tus decisiones.",
    val busy: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val overlayEnabled: Boolean = false,
    val familyName: String? = null,
    val shareProgress: Boolean = false,
    val allowHelp: Boolean = false,
    val telemetryPending: Int = 0,
    val screenPackage: String = "",
    val voice: VoiceState = VoiceState(),
    val guidanceOffered: Boolean = false,
)

class NietoApp : Application() {
    lateinit var settings: LocalSettings; private set
    lateinit var api: NietoApi; private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    private lateinit var engine: GuidanceEngine
    private var sessionId: String? = null
    private var startedAt = 0L
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private data class QueuedEvent(val sessionId: String, val body: JSONObject, val serverUrl: String, val installationToken: String)
    private val events = Channel<QueuedEvent>(Channel.UNLIMITED)
    private var voiceRequestedFromOverlay = false
    private lateinit var realtime: RealtimeVoice
    private var voiceStartJob: Job? = null
    private var voiceStartRequest = 0L
    private var messageJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        settings = LocalSettings(this)
        api = NietoApi(settings)
        realtime = RealtimeVoice(scope,
            onState = { voice -> mutable.value = mutable.value.copy(voice = voice) },
            onAnswer = { answer -> mutable.value = mutable.value.copy(chat = (state.value.chat + ChatLine(false, answer)).takeLast(30)) },
            onGuidanceOffer = { mutable.value = mutable.value.copy(guidanceOffered = true) })
        engine = GuidanceEngine { event ->
            val id = sessionId
            if (id != null) {
                val body = JSONObject().put("type", event.type)
                event.stepId?.let { body.put("stepId", it) }
                if (!engine.state.practice && event.type in listOf("flow_completed", "cancelled")) {
                    body.put("durationSeconds", ((android.os.SystemClock.elapsedRealtime() - startedAt) / 1000).coerceIn(0, 14400))
                }
                mutable.value = mutable.value.copy(telemetryPending = mutable.value.telemetryPending + 1)
                events.trySend(QueuedEvent(id, body, settings.baseUrl, settings.token))
            }
        }
        scope.launch {
            for ((id, body, serverUrl, installationToken) in events) {
                try {
                    api.request("/assistant/sessions/$id/events", "POST", body, serverUrl = serverUrl, installationToken = installationToken)
                    mutable.value = mutable.value.copy(telemetryPending = (mutable.value.telemetryPending - 1).coerceAtLeast(0))
                } catch (_: Exception) {
                    mutable.value = mutable.value.copy(status = "Un evento de progreso no llegó al servidor. La guía local sigue disponible.")
                    // Do not claim synchronized metrics or silently retry to a different identity/server.
                }
            }
        }
        tts = TextToSpeech(this) { result ->
            ttsReady = result == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale.forLanguageTag("es-AR")
        }
    }

    fun status(text: String) { mutable.value = mutable.value.copy(status = text) }
    fun speak(text: String = state.value.guide.notice ?: state.value.guide.instruction) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nieto_instruction")
        else status("La voz no está lista. Podés leer las instrucciones en pantalla.")
    }
    fun stopSpeaking() { tts?.stop() }
    fun startRealtimeVoice() {
        if (!settings.openAiConsent) { status("Aceptá el envío de voz y consultas a OpenAI antes de hablar."); return }
        if (state.value.busy) return
        stopSpeaking()
        val request = ++voiceStartRequest
        mutable.value = mutable.value.copy(busy = true, guidanceOffered = false,
            voice = VoiceState(VoicePhase.CONNECTING, "Conectando la voz. El micrófono todavía está apagado."))
        voiceStartJob = scope.launch {
            try {
                api.register()
                if (request != voiceStartRequest) return@launch
                mutable.value = mutable.value.copy(chat = (state.value.chat + ChatLine(true, "Consulta por voz")).takeLast(30))
                realtime.start(settings.baseUrl, settings.token)
            } catch (_: CancellationException) { /* A foreground voice request was cancelled. Never start the mic later. */ }
            catch (_: Exception) {
                if (request == voiceStartRequest) mutable.value = mutable.value.copy(voice = VoiceState(VoicePhase.ERROR,
                    "No pude conectar la voz. Revisá la conexión del teléfono desde Ajustes."))
            } finally { if (request == voiceStartRequest) mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun finishRealtimeInput() { realtime.finishInput() }
    fun cancelRealtimeVoice() {
        ++voiceStartRequest
        voiceStartJob?.cancel(); voiceStartJob = null
        realtime.cancel()
        mutable.value = mutable.value.copy(busy = false)
    }
    fun onAssistantBackground() {
        val assistantWasBusy = voiceStartJob?.isActive == true || messageJob?.isActive == true
        ++voiceStartRequest
        voiceStartJob?.cancel(); voiceStartJob = null
        messageJob?.cancel(); messageJob = null
        realtime.cancel()
        if (assistantWasBusy) mutable.value = mutable.value.copy(busy = false)
        stopSpeaking()
    }
    fun revokeOpenAiConsent() {
        onAssistantBackground()
        settings.openAiConsent = false
        status("Consentimiento OpenAI revocado. Podés seguir usando las guías locales y Familia.")
    }
    fun requestVoiceFromOverlay() { voiceRequestedFromOverlay = true }
    fun consumeVoiceRequest(): Boolean = voiceRequestedFromOverlay.also { voiceRequestedFromOverlay = false }
    fun screenPackage(value: String) { if (mutable.value.screenPackage != value) mutable.value = mutable.value.copy(screenPackage = value) }
    fun startGuide(practice: Boolean = false, onReady: (() -> Unit)? = null) {
        if (state.value.busy) return
        cancelRealtimeVoice()
        mutable.value = mutable.value.copy(guidanceOffered = false)
        mutable.value = mutable.value.copy(busy = true)
        scope.launch {
            try {
                api.register()
                if (!practice || sessionId == null) {
                    val response = api.request("/assistant/sessions", "POST", JSONObject().put("flowId", GuidanceEngine.FLOW_ID))
                    sessionId = response.getString("id")
                }
                startedAt = android.os.SystemClock.elapsedRealtime()
                updateGuide(engine.start(practice))
                status(if (state.value.accessibilityConnected && settings.screenConsent) "Guía iniciada. Solo observo WhatsApp mientras esté activa." else "Activá la guía en pantalla desde Ajustes para que pueda verificar cada paso.")
                onReady?.invoke()
            } catch (e: Exception) { status(e.message ?: "No pude iniciar la guía.") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun observe(screen: SafeScreen) {
        if (!settings.screenConsent) return
        updateGuide(engine.observe(screen))
    }
    private fun updateGuide(next: GuideState) {
        val previous = state.value.guide
        mutable.value = mutable.value.copy(guide = next)
        if (next.instruction != previous.instruction) speak(next.instruction)
    }
    fun confirmShare() { updateGuide(engine.confirm()) }
    fun repeat() { updateGuide(engine.repeat()); speak() }
    fun lost() { updateGuide(engine.lost()); speak() }
    fun cancel() { updateGuide(engine.cancel()); tts?.stop() }
    fun screenConnection(connected: Boolean) { mutable.value = mutable.value.copy(accessibilityConnected = connected) }
    fun setOverlay(enabled: Boolean) { mutable.value = mutable.value.copy(overlayEnabled = enabled) }

    fun sendMessage(text: String) {
        val clean = text.trim().take(1000)
        if (!settings.openAiConsent) { status("Aceptá el envío de consultas a OpenAI antes de usar el chat."); return }
        if (clean.isBlank() || state.value.busy || state.value.voice.active) return
        stopSpeaking()
        mutable.value = mutable.value.copy(guidanceOffered = false)
        if (realtime.sendText(clean)) {
            mutable.value = mutable.value.copy(chat = (state.value.chat + ChatLine(true, clean)).takeLast(30))
            return
        }
        mutable.value = mutable.value.copy(chat = (state.value.chat + ChatLine(true, clean)).takeLast(30), busy = true)
        messageJob = scope.launch {
            try {
                api.register()
                val body = JSONObject().put("text", clean)
                sessionId?.let { body.put("sessionId", it) }
                val reply = api.request("/assistant/message", "POST", body)
                var answer = reply.optString("instruction", "No pude encontrar una guía para esa consulta.")
                val sources = reply.optJSONArray("sources")
                if (sources != null) for (index in 0 until sources.length().coerceAtMost(3)) {
                    val source = sources.optJSONObject(index) ?: continue
                    val title = source.optString("title")
                    val url = source.optString("url")
                    if (url.startsWith("https://")) answer += "\nFuente: $title\n$url"
                }
                mutable.value = mutable.value.copy(chat = (state.value.chat + ChatLine(false, answer)).takeLast(30))
                speak(answer)
                if (reply.optString("flowId") == GuidanceEngine.FLOW_ID) {
                    mutable.value = mutable.value.copy(guidanceOffered = true)
                    status("NIETO te propone la guía de ubicación. Iniciarla sigue siendo tu decisión.")
                }
            } catch (_: CancellationException) { /* Do not append a response after leaving the assistant. */
            } catch (e: Exception) { status(e.message ?: "No pude enviar la consulta.") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    fun connect() = work {
        api.register()
        api.request("/devices/me", "PATCH", JSONObject().put("name", settings.deviceName).put("territoryId", "AR-C"))
        api.request("/health", authenticated = false)
        refreshFamilyInternal()
        status("Teléfono conectado. Las credenciales quedan cifradas en este dispositivo.")
    }
    fun saveServer(url: String, name: String) {
        runCatching {
            require(state.value.guide.stage in listOf(Stage.IDLE, Stage.COMPLETE, Stage.CANCELLED)) { "Terminá la guía antes de cambiar de servidor." }
            onAssistantBackground()
            settings.deviceName = name
            settings.baseUrl = url
            sessionId = null
            mutable.value = mutable.value.copy(familyName = null, shareProgress = false, allowHelp = false)
        }.onSuccess { connect() }.onFailure { status(it.message ?: "Revisá la dirección.") }
    }
    fun refreshFamily() = work { api.register(); refreshFamilyInternal() }
    private suspend fun refreshFamilyInternal() {
        val response = api.request("/family/me")
        val group = response.optJSONObject("group")
        val permissions = response.optJSONObject("permissions")
        mutable.value = mutable.value.copy(familyName = group?.optString("name"),
            shareProgress = permissions?.optBoolean("progress") == true, allowHelp = permissions?.optBoolean("help") == true)
    }
    fun acceptFamily(code: String, progress: Boolean, help: Boolean) = work {
        require(code.isNotBlank()) { "Ingresá el código que te compartió tu familiar." }
        api.register()
        api.request("/family/link", "POST", JSONObject().put("invitationCode", code.trim())
            .put("permissions", JSONObject().put("progress", progress).put("help", help)))
        refreshFamilyInternal()
        status("Aceptaste la invitación y elegiste qué compartir. Podés revocar el acceso cuando quieras.")
    }
    fun revokeFamily() = work {
        api.request("/family/link", "DELETE")
        refreshFamilyInternal()
        status("Acceso familiar revocado.")
    }
    fun requestHelp() = work {
        if (!state.value.allowHelp) { refreshFamilyInternal() }
        require(state.value.allowHelp && state.value.familyName != null) { "Primero vinculá un grupo y autorizá las solicitudes de ayuda en Familia." }
        api.request("/family/help", "POST", JSONObject())
        status("Tu solicitud de ayuda llegó al grupo familiar. No compartí tu conversación ni la pantalla.")
        speak("Tu solicitud de ayuda llegó al grupo familiar.")
    }
    private fun work(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        scope.launch {
            try { block() }
            catch (e: Exception) { status(e.message ?: "No pude completar la acción.") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}
