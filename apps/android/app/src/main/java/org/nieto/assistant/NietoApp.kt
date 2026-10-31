package org.nieto.assistant

import android.app.Application
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.content.Intent
import org.nieto.assistant.presence.FloatingBubbleService
import org.nieto.assistant.presence.WakeVoiceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import org.nieto.assistant.data.LocalSettings
import org.nieto.assistant.data.NietoApi
import org.nieto.assistant.flow.*
import java.util.Locale

data class ChatLine(val fromUser: Boolean, val text: String)
data class AppState(
    val guide: GuideState = GuideState(),
    val chat: List<ChatLine> = listOf(ChatLine(false, "Hola, soy TINO. Contame qué necesitás hacer con tu teléfono. Podés escribirme o hablarme.")),
    val status: String = "Tu ritmo. Tus decisiones.",
    val busy: Boolean = false,
    val suggestedFlow: String? = null,
    val task: GuidedTask? = null, val emailDraft: EmailDraft? = null,
    val accessibilityConnected: Boolean = false,
    val overlayEnabled: Boolean = false,
    val bubbleEnabled: Boolean = false,
    val wakePhase: String = "off",
    val presenceNotice: String? = null,
    val foreground: Boolean = false,
    val speaking: Boolean = false,
    val openChatEpoch: Int = 0,
    val recordingVoiceNote: Boolean = false,
    val familyInbox: List<JSONObject> = emptyList(),
    val familyName: String? = null,
    val shareProgress: Boolean = false,
    val allowHelp: Boolean = false,
    val telemetryPending: Int = 0,
    val screenPackage: String = "",
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
    private var activeUtterance: String? = null
    private data class QueuedEvent(val sessionId: String, val body: JSONObject, val serverUrl: String, val installationToken: String)
    private val events = Channel<QueuedEvent>(Channel.UNLIMITED)
    private var voiceRequestedFromOverlay = false
    private var conversationId = UUID.randomUUID().toString()
    private var taskStartedAt = 0L

    override fun onCreate() {
        super.onCreate()
        settings = LocalSettings(this)
        api = NietoApi(settings)
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
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { scope.launch { if (utteranceId == activeUtterance) mutable.value = mutable.value.copy(speaking = true) } }
            override fun onDone(utteranceId: String?) { finishVoice(utteranceId) }
            @Deprecated("Required Android callback") override fun onError(utteranceId: String?) { finishVoice(utteranceId) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { finishVoice(utteranceId) }
        })
    }
    private fun finishVoice(id: String?) { scope.launch { if (id == activeUtterance) { activeUtterance = null; mutable.value = mutable.value.copy(speaking = false) } } }

    fun status(text: String) { mutable.value = mutable.value.copy(status = text) }
    fun speak(text: String = state.value.guide.notice ?: state.value.guide.instruction) {
        if (ttsReady) {
            activeUtterance = UUID.randomUUID().toString()
            mutable.value = mutable.value.copy(speaking = true)
            if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, activeUtterance) == TextToSpeech.ERROR) finishVoice(activeUtterance)
        }
        else status("La voz no está lista. Podés leer las instrucciones en pantalla.")
    }
    fun stopSpeaking() { activeUtterance = null; tts?.stop(); mutable.value = mutable.value.copy(speaking = false) }
    fun foreground(visible: Boolean) { mutable.value = mutable.value.copy(foreground = visible) }
    fun openChat() { mutable.value = mutable.value.copy(openChatEpoch = mutable.value.openChatEpoch + 1) }
    fun pauseWakeForRecording() { mutable.value = mutable.value.copy(recordingVoiceNote = true) }
    fun resumeWakeAfterRecording() { mutable.value = mutable.value.copy(recordingVoiceNote = false) }
    suspend fun refreshFamilyInbox() {
        if (settings.token.isBlank()) return
        runCatching {
            val messages = api.request("/family/messages").optJSONArray("messages")
            val items = if (messages == null) emptyList() else (0 until messages.length()).map { messages.getJSONObject(it) }
            if (items.map { it.toString() } != state.value.familyInbox.map { it.toString() }) mutable.value = mutable.value.copy(familyInbox = items)
        }
    }
    fun bubbleState(enabled: Boolean) { mutable.value = mutable.value.copy(bubbleEnabled = enabled) }
    fun wakeState(phase: String) { mutable.value = mutable.value.copy(wakePhase = phase) }
    fun presenceNotice(text: String?) { mutable.value = mutable.value.copy(presenceNotice = text) }
    fun stopPresence() {
        stopService(Intent(this, FloatingBubbleService::class.java))
        stopService(Intent(this, WakeVoiceService::class.java))
        mutable.value = mutable.value.copy(bubbleEnabled = false, wakePhase = "off", presenceNotice = null)
    }
    fun requestVoiceFromOverlay() { voiceRequestedFromOverlay = true }
    fun consumeVoiceRequest(): Boolean = voiceRequestedFromOverlay.also { voiceRequestedFromOverlay = false }
    fun screenPackage(value: String) { if (mutable.value.screenPackage != value) mutable.value = mutable.value.copy(screenPackage = value) }
    fun startGuide(practice: Boolean = false, onReady: (() -> Unit)? = null) {
        if (state.value.busy) return
        if (state.value.task?.active == true) { tellUser("Terminá o cancelá la guía actual antes de empezar otra."); return }
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

    fun resetConversation() {
        stopSpeaking()
        sessionId = null
        updateGuide(engine.cancel())
        conversationId = UUID.randomUUID().toString()
        mutable.value = mutable.value.copy(chat = AppState().chat, suggestedFlow = null, task = null, emailDraft = null, familyInbox = emptyList())
    }
    fun sendMessage(text: String) {
        val clean = text.trim().take(1000)
        if (clean.isBlank() || state.value.busy) return
        mutable.value = mutable.value.copy(chat = (state.value.chat + ChatLine(true, clean)).takeLast(30), busy = true, suggestedFlow = null)
        scope.launch {
            try {
                api.register()
                val history = JSONArray()
                state.value.chat.dropLast(1).takeLast(12).forEach { line -> history.put(JSONObject().put("role", if (line.fromUser) "user" else "assistant").put("content", line.text.take(1000))) }
                val body = JSONObject().put("text", clean).put("conversationId", conversationId).put("history", history)
                while (body.toString().toByteArray(Charsets.UTF_8).size > 14_000 && history.length() > 0) history.remove(0)
                (state.value.task?.takeIf { it.active }?.sessionId ?: sessionId)?.let { body.put("sessionId", it) }
                val reply = api.request("/assistant/message", "POST", body)
                var answer = reply.optString("instruction", "No pude encontrar una guía para esa consulta.")
                val sources = reply.optJSONArray("sources")
                if (sources != null) for (index in 0 until sources.length().coerceAtMost(3)) {
                    val source = sources.optJSONObject(index) ?: continue
                    val title = source.optString("title")
                    val url = source.optString("url")
                    if (url.startsWith("https://")) answer += "\nFuente: $title\n$url"
                }
                val draft = reply.optJSONObject("emailDraft")?.let { EmailDraft(it.getString("to"), it.getString("subject"), it.getString("body")) }
                mutable.value = mutable.value.copy(chat = (state.value.chat + ChatLine(false, answer)).takeLast(30), suggestedFlow = reply.optString("flowId").takeIf { it.isNotBlank() }, emailDraft = if (reply.optBoolean("clearEmailDraft")) null else draft ?: state.value.emailDraft)
                speak(answer)
                if (reply.optString("flowId") == GuidanceEngine.FLOW_ID) status("Podés iniciar la guía con el botón Compartir ubicación.")
            } catch (e: Exception) { mutable.value = mutable.value.copy(chat = (state.value.chat + ChatLine(false, "No pude enviar tu mensaje. Probá de nuevo.")).takeLast(30), status = e.message ?: "No pude enviar la consulta.") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    fun tellUser(text: String) { mutable.value = mutable.value.copy(chat = (state.value.chat + ChatLine(false, text)).takeLast(30)); speak(text) }
    fun startTask(flowId: String, onReady: () -> Unit) = work {
        require(flowId in setOf("whatsapp-group", "email-send"))
        require(state.value.task?.active != true) { "Terminá o cancelá la guía actual antes de empezar otra." }
        cancel()
        sessionId = null
        if (flowId != "email-send" || state.value.suggestedFlow != "email-send") mutable.value = mutable.value.copy(emailDraft = null)
        api.register()
        val task = GuidedTask.from(api.request("/assistant/sessions", "POST", JSONObject().put("flowId", flowId)))
        api.request("/assistant/sessions/${task.sessionId}/events", "POST", JSONObject().put("type", "flow_started"))
        api.request("/assistant/sessions/${task.sessionId}/events", "POST", JSONObject().put("type", "step_shown").put("stepId", task.current.id))
        taskStartedAt = android.os.SystemClock.elapsedRealtime()
        mutable.value = mutable.value.copy(task = task, suggestedFlow = null)
        speak(task.current.instruction)
        onReady()
    }
    fun advanceTask() = work {
        val task = state.value.task?.takeIf { it.active } ?: return@work
        api.request("/assistant/sessions/${task.sessionId}/events", "POST", JSONObject().put("type", "step_completed").put("stepId", task.current.id))
        if (task.index == task.steps.lastIndex) {
            val seconds = ((android.os.SystemClock.elapsedRealtime() - taskStartedAt) / 1000).coerceIn(0, 14400)
            api.request("/assistant/sessions/${task.sessionId}/events", "POST", JSONObject().put("type", "flow_completed").put("durationSeconds", seconds).put("verification", "user_confirmed"))
            mutable.value = mutable.value.copy(task = task.copy(completed = true), emailDraft = null)
            tellUser(if (task.flowId == "email-send") "Marcaste que el correo está en Enviados. Completaste la guía." else "Marcaste que ya ves tu grupo creado. Completaste la guía.")
        } else {
            val next = task.copy(index = task.index + 1)
            api.request("/assistant/sessions/${task.sessionId}/events", "POST", JSONObject().put("type", "step_shown").put("stepId", next.current.id))
            mutable.value = mutable.value.copy(task = next)
            speak(next.current.instruction)
        }
    }
    fun repeatTask() { state.value.task?.let { speak(it.current.instruction) } }
    fun cancelTask() = work {
        val task = state.value.task?.takeIf { it.active } ?: return@work
        api.request("/assistant/sessions/${task.sessionId}/events", "POST", JSONObject().put("type", "cancelled"))
        mutable.value = mutable.value.copy(task = task.copy(cancelled = true), emailDraft = null); stopSpeaking()
    }
    fun dismissTask() { if (state.value.task?.active != true) mutable.value = mutable.value.copy(task = null) }
    fun connect() = work {
        api.register()
        api.request("/devices/me", "PATCH", JSONObject().put("name", settings.deviceName).put("territoryId", settings.territoryId))
        api.request("/health", authenticated = false)
        refreshFamilyInternal()
        status("Teléfono conectado. Las credenciales quedan cifradas en este dispositivo.")
    }
    fun saveServer(url: String, name: String) {
        runCatching {
            require(state.value.guide.stage in listOf(Stage.IDLE, Stage.COMPLETE, Stage.CANCELLED)) { "Terminá la guía antes de cambiar de servidor." }
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
            catch (e: Exception) { status(e.message ?: "No pude completar la acción."); if (state.value.task != null) tellUser("No pude completar ese paso. Podés volver a intentarlo.") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}
