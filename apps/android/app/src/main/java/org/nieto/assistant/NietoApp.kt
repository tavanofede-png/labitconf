package org.nieto.assistant

import android.app.Application
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.nieto.assistant.data.LocalSettings
import org.nieto.assistant.data.NietoApi
import org.nieto.assistant.data.ApiFailure
import org.nieto.assistant.flow.*
import org.nieto.assistant.voice.RealtimeVoice
import org.nieto.assistant.voice.VoicePhase
import org.nieto.assistant.voice.VoiceState
import org.nieto.assistant.voice.GuideAnnouncementPolicy
import org.nieto.assistant.telemetry.TelemetrySession
import org.nieto.assistant.telemetry.TelemetrySync
import org.nieto.assistant.screen.*
import java.util.Locale

data class ChatLine(val fromUser: Boolean, val text: String)
data class AppState(
    val guide: GuideState = GuideState(),
    val answer: String = "Contame qué querés hacer y te acompaño en la pantalla actual.",
    val status: String = "Tu ritmo. Tus decisiones.",
    val busy: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val overlayEnabled: Boolean = false,
    val familyName: String? = null,
    val shareProgress: Boolean = false,
    val allowHelp: Boolean = false,
    val telemetryPending: Int = 0,
    val telemetryPaused: Int = 0,
    val telemetryError: String = "",
    val ttsNotice: String = "",
    val screenPackage: String = "",
    val voice: VoiceState = VoiceState(),
    val guidanceOffered: Boolean = false,
    val assistanceEnabled: Boolean = false,
    val screen: ScreenContext? = null,
    val screenValid: Boolean = false,
    val screenStep: ScreenStep? = null,
    val institutionMetricsConsent: Boolean = false,
    val deviceSettingsLoaded: Boolean = false,
    val territoryId: String = "AR-C",
    val nativeSurfaceVisible: Boolean = false,
    val voiceSurfaceVisible: Boolean = false,
    val textOnly: Boolean = false,
    val realtimeApproved: Boolean = false,
)

class NietoApp : Application() {
    lateinit var settings: LocalSettings; private set
    lateinit var api: NietoApi; private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    private lateinit var engine: GuidanceEngine
    private var guideSession: TelemetrySession? = null
    private var startedAt = 0L
    private var practiceStartedAt = 0L
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitialized = false
    private var pendingSpeech: String? = null
    private val guideAnnouncements = GuideAnnouncementPolicy()
    private var lastScreenNotice: String? = null
    private var voiceRequestedFromOverlay = false
    private lateinit var realtime: RealtimeVoice
    private var voiceStartJob: Job? = null
    private var voiceStartRequest = 0L
    private var messageJob: Job? = null
    var screenCapture: ScreenCaptureBridge? = null
    private var preparedVoiceScreen: ScreenContext? = null
    private var hasTask = false
    private var followUp: Job? = null
    private var lastScreenSent: String? = null
    private var lastFollowUpAt = 0L
    private var automaticSteps = 0
    private var assistantSession: TelemetrySession? = null
    private var assistantStartedAt = 0L
    private var assistantRequest = 0L
    private var lastSnapshotSentAt = 0L
    private var deviceSettingsJob: Job? = null
    private val visibleSurfaces = mutableSetOf<String>()
    fun surfaceVisible(name: String, visible: Boolean) {
        if (visible) visibleSurfaces.add(name) else visibleSurfaces.remove(name)
        mutable.value = mutable.value.copy(nativeSurfaceVisible = visibleSurfaces.isNotEmpty(), voiceSurfaceVisible = "voice" in visibleSurfaces)
    }

    override fun onCreate() {
        super.onCreate()
        settings = LocalSettings(this)
        api = NietoApi(settings)
        TelemetrySync.initialize(this)
        scope.launch { TelemetrySync.status.collect { queue ->
            mutable.value = mutable.value.copy(telemetryPending = queue.pending, telemetryPaused = queue.paused, telemetryError = queue.error)
        } }
        mutable.value = mutable.value.copy(territoryId = settings.territoryId)
        realtime = RealtimeVoice(scope,
            onState = { voice -> mutable.value = mutable.value.copy(voice = voice); if (!voice.active) scheduleScreenFollowUp() },
            onAnswer = { answer -> if (state.value.screen == null || state.value.screenValid) mutable.value = mutable.value.copy(answer = answer) },
            onGuidanceOffer = {},
            onStep = { step ->
                if (state.value.screenValid && ScreenPolicy.target(step, state.value.screen, android.os.SystemClock.elapsedRealtime()) != null) {
                    mutable.value = mutable.value.copy(screenStep = step, answer = step.instruction)
                    assistantEvent("step_shown")
                } else status("La pantalla cambió. Actualizá la pantalla para que pueda señalar el control correcto.")
            })
        engine = GuidanceEngine { event ->
            if (event.type == "practice_started") practiceStartedAt = android.os.SystemClock.elapsedRealtime()
            guideSession?.let { session ->
                val clock = if (event.attemptId == null) startedAt else practiceStartedAt
                val duration = if (event.type in setOf("flow_reported", "practice_reported", "cancelled"))
                    ((android.os.SystemClock.elapsedRealtime() - clock) / 1000).coerceIn(0, 14400).toInt() else null
                TelemetrySync.enqueue(this, session, event.type, event.stepId, duration, event.attemptId)
            }
        }
        tts = TextToSpeech(this) { result ->
            scope.launch { runCatching { initializeSpeech(result) }.onFailure {
                ttsInitialized = true; ttsReady = false
                speechNotice("No pude comprobar el motor y el idioma de voz. Las instrucciones siguen visibles en pantalla.")
            } }
        }
    }

    fun status(text: String) { mutable.value = mutable.value.copy(status = text) }
    fun speak(text: String = state.value.guide.notice ?: state.value.guide.instruction) {
        if (text.isBlank() || (state.value.assistanceEnabled && state.value.textOnly)) return
        if (!ttsReady) {
            if (!ttsInitialized) { pendingSpeech = text; speechNotice("La voz se está preparando. La instrucción sigue disponible en pantalla.") }
            else if (state.value.ttsNotice.isBlank()) speechNotice("La lectura por voz no está disponible. Podés leer la instrucción en pantalla.")
            return
        }
        pendingSpeech = null
        if (runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nieto_instruction") ?: TextToSpeech.ERROR }.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.ERROR)
            speechNotice("Android no pudo iniciar la lectura. Revisá el motor de voz y el idioma español; la instrucción sigue en pantalla.")
    }
    private fun initializeSpeech(result: Int) {
        ttsInitialized = true
        val engine = tts
        if (result != TextToSpeech.SUCCESS || engine == null) {
            ttsReady = false; speechNotice("No pude iniciar el motor de voz de Android. Las instrucciones siguen visibles."); return
        }
        val language = Locale.forLanguageTag("es-AR")
        val available = engine.isLanguageAvailable(language)
        if (available < TextToSpeech.LANG_AVAILABLE || engine.setLanguage(language) < TextToSpeech.LANG_AVAILABLE) {
            ttsReady = false; speechNotice(if (available == TextToSpeech.LANG_MISSING_DATA)
                "Faltan los datos de voz en español. Instalalos en los ajustes de Android; las instrucciones siguen visibles."
                else "El motor de voz no ofrece español. Elegí uno compatible en Android; las instrucciones siguen visibles.")
            return
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { }
            override fun onDone(utteranceId: String?) { }
            @Deprecated("Android compatibility callback") override fun onError(utteranceId: String?) { scope.launch { speechNotice("La lectura por voz falló. Podés repetir el paso o leerlo en pantalla.") } }
            override fun onError(utteranceId: String?, errorCode: Int) { onError(utteranceId) }
        })
        ttsReady = true; speechNotice("")
        pendingSpeech?.let { speak(it) }
    }
    private fun speechNotice(message: String) { mutable.value = mutable.value.copy(ttsNotice = message) }
    fun stopSpeaking() { pendingSpeech = null; runCatching { tts?.stop() } }
    fun retryTelemetry() { TelemetrySync.retryRetained(this) }
    fun enableAssistance() {
        if (!settings.remoteScreenConsent || !settings.openAiConsent) { status("Aceptá el permiso nuevo de asistencia con OpenAI."); return }
        if (state.value.busy) { status("Esperá a que termine la acción actual antes de cambiar de modo."); return }
        // Cancel while the original session is still bound, then detach it before remote events can arrive.
        engine.cancel()
        guideSession = null
        guideAnnouncements.reset()
        stopSpeaking()
        lastScreenNotice = null
        mutable.value = mutable.value.copy(guide = engine.state, assistanceEnabled = true, overlayEnabled = true,
            answer = "Abrí la app que querés usar y tocá la burbuja N para contarme tu tarea.")
        status("Asistencia activa. Vos hacés cada toque. El micrófono sigue apagado.")
    }
    fun prepareVoice(onReady: () -> Unit) {
        if (!state.value.assistanceEnabled || !settings.remoteScreenConsent) return
        mutable.value = mutable.value.copy(textOnly = false)
        stopSpeaking()
        realtime.selectOutputMode(textOnly = false)
        val request = assistantRequest
        val ready: (ScreenContext?) -> Unit = { screen ->
            if (request == assistantRequest && state.value.assistanceEnabled) {
                preparedVoiceScreen = screen?.takeUnless { it.blocked }
                if (screen?.blocked == true) status(screen.notice) else onReady()
            }
        }
        screenCapture?.capture(ready) ?: ready(null)
    }
    fun startRealtimeVoice() {
        if (!settings.openAiConsent || !settings.remoteScreenConsent || !state.value.assistanceEnabled || state.value.busy) return
        submitAssistant(null, preparedVoiceScreen.also { preparedVoiceScreen = null })
    }
    private fun submitAssistant(text: String?, screen: ScreenContext?) {
        stopSpeaking()
        val request = ++assistantRequest
        voiceStartRequest = request
        mutable.value = mutable.value.copy(busy = true, screenStep = null, answer = "", voice = VoiceState(VoicePhase.CONNECTING, "Conectando. El micrófono todavía está apagado."))
        voiceStartJob = scope.launch {
            try {
                api.register()
                if (!state.value.deviceSettingsLoaded || !state.value.realtimeApproved) readDeviceSettings()
                if (!state.value.realtimeApproved) throw ApiFailure("Para usar la asistencia, pedí un código de conexión al equipo e ingresalo en Ajustes.")
                if (assistantSession == null) {
                    assistantSession = TelemetrySync.session(settings.baseUrl, settings.token, "screen-assistance")
                    assistantStartedAt = android.os.SystemClock.elapsedRealtime()
                    assistantEvent("flow_started", withStep = false)
                }
                if (request != assistantRequest || !state.value.assistanceEnabled) return@launch
                delay((1000L - (android.os.SystemClock.elapsedRealtime() - lastSnapshotSentAt)).coerceAtLeast(0))
                if (request != assistantRequest || !state.value.assistanceEnabled || !settings.remoteScreenConsent) return@launch
                hasTask = true
                val currentScreen = screen?.takeIf { !it.blocked && state.value.screenValid && state.value.screen?.id == it.id &&
                    state.value.screenPackage == it.packageName && android.os.SystemClock.elapsedRealtime() - it.capturedAt in 0..15_000 }
                lastScreenSent = currentScreen?.id
                lastSnapshotSentAt = android.os.SystemClock.elapsedRealtime()
                realtime.start(settings.baseUrl, settings.token, currentScreen?.toWire(), text, textOnly = state.value.textOnly)
                if (screen?.notice?.isNotBlank() == true) status(screen.notice)
            } catch (_: CancellationException) { }
            catch (error: Exception) {
                if (request == assistantRequest) mutable.value = mutable.value.copy(voice = VoiceState(VoicePhase.ERROR,
                    if (error is ApiFailure || error is IllegalArgumentException) error.message.orEmpty().take(200)
                    else "No pude conectar la asistencia. Revisá el servidor desde Ajustes."))
            } finally { if (request == assistantRequest) mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun finishRealtimeInput() { realtime.finishInput() }
    fun cancelRealtimeVoice() {
        ++assistantRequest; ++voiceStartRequest
        voiceStartJob?.cancel(); voiceStartJob = null
        realtime.cancel()
        mutable.value = mutable.value.copy(busy = false, screenStep = null)
    }
    fun onAssistantBackground() {
        if (voiceStartJob?.isActive == true || state.value.voice.phase in listOf(VoicePhase.CONNECTING, VoicePhase.LISTENING)) cancelRealtimeVoice()
        else if (state.value.voice.active) realtime.cancelResponseForScreenChange()
        stopSpeaking()
    }
    fun stopAssistance() {
        assistantEvent("cancelled", withStep = false, duration = true)
        assistantSession = null
        ++assistantRequest; ++voiceStartRequest
        hasTask = false
        followUp?.cancel(); followUp = null
        voiceStartJob?.cancel(); voiceStartJob = null
        messageJob?.cancel(); messageJob = null
        realtime.cancel(); stopSpeaking()
        preparedVoiceScreen = null; hasTask = false; lastScreenSent = null; automaticSteps = 0
        lastScreenNotice = null
        mutable.value = mutable.value.copy(assistanceEnabled = false, overlayEnabled = false, screen = null, screenValid = false,
            screenStep = null, answer = "La asistencia terminó. El contexto de la tarea se eliminó.", busy = false)
    }
    fun revokeOpenAiConsent() {
        stopAssistance()
        settings.openAiConsent = false; settings.remoteScreenConsent = false; settings.screenImagesConsent = false
        status("Permiso remoto revocado. Micrófono, pantalla y contexto de tarea eliminados.")
    }
    fun invalidateScreenTarget(cancelResponse: Boolean = false) {
        // Focus/visibility events hide the target while rechecking the same screen.
        // Navigation retires the step; an unchanged snapshot may restore only a visibility-hidden step.
        mutable.value = mutable.value.copy(screenValid = false, screenStep = if (cancelResponse) null else state.value.screenStep)
        if (cancelResponse && state.value.assistanceEnabled && state.value.voice.phase in listOf(VoicePhase.WAITING, VoicePhase.SPEAKING)) {
            realtime.cancelResponseForScreenChange()
        }
    }
    fun screenUnavailable() {
        if (state.value.assistanceEnabled) {
            followUp?.cancel(); followUp = null
            mutable.value = mutable.value.copy(screen = null, screenValid = false, screenStep = null)
            realtime.clearScreen()
            status("No puedo observar esta pantalla. Volvé a una pantalla sin datos sensibles.")
            announceScreenNotice(state.value.status)
        }
    }
    fun onScreenObserved(screen: ScreenContext) {
        if (!state.value.assistanceEnabled || !settings.remoteScreenConsent) return
        val changed = state.value.screen?.id != screen.id
        mutable.value = mutable.value.copy(screen = screen.copy(image = null), screenValid = !screen.blocked,
            screenStep = if (changed || screen.blocked) null else state.value.screenStep)
        if (screen.blocked) {
            followUp?.cancel()
            if (state.value.voice.phase in listOf(VoicePhase.LISTENING, VoicePhase.CONNECTING)) cancelRealtimeVoice()
            realtime.clearScreen(); status(screen.notice); announceScreenNotice(screen.notice)
        } else if (changed) {
            lastScreenNotice = null
            if (state.value.voice.phase in listOf(VoicePhase.LISTENING, VoicePhase.CONNECTING)) {
                cancelRealtimeVoice()
                status("La pantalla cambió durante la consulta. Apagué el micrófono; tocá Hablar de nuevo cuando estés listo.")
            } else if (state.value.voice.active) realtime.cancelResponseForScreenChange()
            scheduleScreenFollowUp()
        }
    }
    private fun announceScreenNotice(notice: String) { if (notice.isNotBlank() && notice != lastScreenNotice) { lastScreenNotice = notice; speak(notice) } }
    private fun scheduleScreenFollowUp() {
        val current = state.value
        if (!current.assistanceEnabled || !hasTask || current.busy || current.voice.active || !current.screenValid ||
            current.screen?.id == lastScreenSent || current.screen == null || automaticSteps >= 18) return
        followUp?.cancel()
        val request = assistantRequest
        followUp = scope.launch {
            val now = android.os.SystemClock.elapsedRealtime()
            delay(maxOf(1200L, 4000L - (now - lastFollowUpAt)))
            if (request != assistantRequest || !state.value.assistanceEnabled || state.value.voice.active) return@launch
            screenCapture?.capture { screen ->
                if (request == assistantRequest && screen != null && !screen.blocked && state.value.screen?.id == screen.id && screen.id != lastScreenSent) {
                    if (realtime.updateScreen(screen.toWire(), requestGuidance = true)) {
                        lastScreenSent = screen.id; lastFollowUpAt = android.os.SystemClock.elapsedRealtime(); ++automaticSteps
                        lastSnapshotSentAt = lastFollowUpAt
                    }
                }
            }
        }
    }
    fun refreshScreen() {
        if (!state.value.assistanceEnabled) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSnapshotSentAt < 1000 || state.value.voice.active) { status("Esperá a que termine la respuesta para actualizar la pantalla."); return }
        screenCapture?.capture { screen ->
            if (screen != null && !screen.blocked && state.value.assistanceEnabled) {
                if (hasTask && realtime.updateScreen(screen.toWire(), true)) { lastScreenSent = screen.id; lastSnapshotSentAt = android.os.SystemClock.elapsedRealtime() }
                else status("Pantalla actualizada. Contame qué querés hacer.")
            }
        }
    }
    fun repeatAssistance() { assistantEvent("step_repeated"); speak(state.value.answer) }
    fun lostAssistance() {
        assistantEvent("user_lost")
        if (state.value.voice.phase in listOf(VoicePhase.LISTENING, VoicePhase.CONNECTING)) cancelRealtimeVoice()
        else if (state.value.voice.active) realtime.cancelResponseForScreenChange()
        sendMessage("Me perdí. Explicame el próximo paso de otra manera con la pantalla actual.", textOnly = state.value.textOnly)
    }
    private fun assistantEvent(type: String, withStep: Boolean = true, duration: Boolean = false) {
        val session = assistantSession ?: return
        TelemetrySync.enqueue(this, session, type, if (withStep) "screen-step" else null,
            if (duration) ((android.os.SystemClock.elapsedRealtime() - assistantStartedAt) / 1000).coerceIn(0, 14400).toInt() else null)
    }
    fun requestVoiceFromOverlay() { voiceRequestedFromOverlay = true }
    fun consumeVoiceRequest(): Boolean = voiceRequestedFromOverlay.also { voiceRequestedFromOverlay = false }
    fun screenPackage(value: String) { if (mutable.value.screenPackage != value) mutable.value = mutable.value.copy(screenPackage = value) }
    fun startGuide(practice: Boolean = false, onReady: (() -> Unit)? = null) {
        if (state.value.busy) return
        if (practice && guideSession == null) { status("Comenzá un intento acompañado antes de practicar con esta conexión."); return }
        if (state.value.assistanceEnabled) stopAssistance() else cancelRealtimeVoice()
        mutable.value = mutable.value.copy(textOnly = false)
        mutable.value = mutable.value.copy(guidanceOffered = false)
        mutable.value = mutable.value.copy(busy = true)
        scope.launch {
            try {
                api.register()
                if (!practice || guideSession == null) {
                    if (engine.state.stage !in listOf(Stage.IDLE, Stage.COMPLETE, Stage.CANCELLED)) engine.cancel()
                    guideSession = TelemetrySync.session(settings.baseUrl, settings.token, GuidanceEngine.FLOW_ID)
                    startedAt = android.os.SystemClock.elapsedRealtime()
                }
                guideAnnouncements.reset()
                updateGuide(engine.start(practice))
                setOverlay(true)
                status(if (state.value.accessibilityConnected && settings.screenConsent) "Guía iniciada. Solo observo WhatsApp mientras esté activa." else "Activá Accesibilidad y el permiso local de pantalla para verificar los pasos.")
                onReady?.invoke()
            } catch (e: Exception) { status(e.message ?: "No pude iniciar la guía.") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun observe(screen: SafeScreen) {
        if (!AssistanceModePolicy.observeLocal(state.value.assistanceEnabled, settings.screenConsent, engine.state.stage)) return
        updateGuide(engine.observe(screen))
    }
    private fun updateGuide(next: GuideState, forceSpeech: Boolean = false) {
        mutable.value = mutable.value.copy(guide = next)
        guideAnnouncements.next(next.stage.name, next.instruction, next.notice, forceSpeech)?.let { speak(it) }
    }
    fun confirmShare() { updateGuide(engine.confirm()) }
    fun reportGuideOutcome() { updateGuide(engine.reportSent()) }
    fun repeat() { updateGuide(engine.repeat(), forceSpeech = true) }
    fun lost() { updateGuide(engine.lost(), forceSpeech = true) }
    fun cancel() { stopSpeaking(); updateGuide(engine.cancel()) }
    fun screenConnection(connected: Boolean) { mutable.value = mutable.value.copy(accessibilityConnected = connected) }
    fun setOverlay(enabled: Boolean) { mutable.value = mutable.value.copy(overlayEnabled = enabled) }

    fun sendMessage(text: String, textOnly: Boolean = true, onSubmitted: (Boolean) -> Unit = {}): Boolean {
        val clean = text.trim().take(1000)
        if (clean.isBlank() || !state.value.assistanceEnabled || !settings.remoteScreenConsent || !settings.openAiConsent || state.value.busy || state.value.voice.active) {
            onSubmitted(false)
            if (clean.isNotBlank()) status("Esperá a que termine la respuesta. Tu consulta escrita se conserva.")
            return false
        }
        val request = ++assistantRequest
        mutable.value = mutable.value.copy(busy = true, textOnly = textOnly)
        stopSpeaking()
        val ready: (ScreenContext?) -> Unit = { screen ->
            if (request == assistantRequest && state.value.assistanceEnabled) {
                if (screen?.blocked == true) {
                    mutable.value = mutable.value.copy(busy = false)
                    status(screen.notice)
                    onSubmitted(false)
                } else { submitAssistant(clean, screen); onSubmitted(true) }
            } else onSubmitted(false)
        }
        try { screenCapture?.capture(ready) ?: ready(null) }
        catch (_: Exception) {
            if (request == assistantRequest) mutable.value = mutable.value.copy(busy = false)
            status("No pude preparar la pantalla. Tu consulta escrita se conserva.")
            onSubmitted(false)
        }
        return true
    }
    fun selectWrittenMode() {
        if (!state.value.assistanceEnabled) return
        val changed = !state.value.textOnly
        mutable.value = mutable.value.copy(textOnly = true)
        stopSpeaking()
        if (changed && state.value.voice.phase in listOf(VoicePhase.CONNECTING, VoicePhase.LISTENING)) cancelRealtimeVoice()
        realtime.selectOutputMode(textOnly = true)
    }
    fun setInstitutionMetricsConsent(allowed: Boolean) = work {
        require(state.value.deviceSettingsLoaded) { "Primero consultá los permisos vigentes del servidor." }
        api.register()
        api.request("/devices/me", "PATCH", JSONObject().put("institutionMetricsConsent", allowed).put("territoryId", settings.territoryId))
        mutable.value = mutable.value.copy(institutionMetricsConsent = allowed)
        status(if (allowed) "Autorizaste métricas de actividad y dificultades agregadas de los últimos 30 días. No se comparte pantalla ni conversación." else "Métricas institucionales revocadas; tu actividad queda excluida de los agregados.")
    }
    fun refreshDeviceSettings() {
        if (deviceSettingsJob?.isActive == true || settings.token.isBlank()) return
        mutable.value = mutable.value.copy(deviceSettingsLoaded = false)
        deviceSettingsJob = scope.launch {
            try { readDeviceSettings() }
            catch (_: Exception) { status("No pude consultar el permiso institucional vigente. No lo muestro como revocado; volvé a consultar con conexión.") }
        }
    }
    private suspend fun readDeviceSettings() {
        val server = settings.baseUrl; val token = settings.token
        val device = api.request("/devices/me", serverUrl = server, installationToken = token)
        if (server != settings.baseUrl || token != settings.token) return
        val territory = device.optString("territoryId", settings.territoryId)
        if (org.nieto.assistant.data.Territories.names.containsKey(territory)) settings.territoryId = territory
        mutable.value = mutable.value.copy(institutionMetricsConsent = device.optBoolean("institutionMetricsConsent", false),
            realtimeApproved = device.optBoolean("realtimeApproved", false), deviceSettingsLoaded = true, territoryId = settings.territoryId)
    }
    fun saveTerritory(id: String) = work {
        require(org.nieto.assistant.data.Territories.names.containsKey(id)) { "Elegí una jurisdicción argentina." }
        api.register()
        api.request("/devices/me", "PATCH", JSONObject().put("territoryId", id))
        settings.territoryId = id
        mutable.value = mutable.value.copy(territoryId = id)
        status("Jurisdicción declarada actualizada. No se usó GPS ni ubicación automática.")
    }
    fun connect(enrollmentCode: String = "", onConnected: () -> Unit = {}) = work {
        api.register(enrollmentCode)
        // Clear an accepted one-use code even if a later, unrelated refresh loses connectivity.
        onConnected()
        api.request("/devices/me", "PATCH", JSONObject().put("name", settings.deviceName))
        readDeviceSettings()
        api.request("/health", authenticated = false)
        refreshFamilyInternal()
        status(if (state.value.realtimeApproved) "Teléfono conectado y autorizado para la asistencia. La credencial NIETO queda cifrada en este dispositivo."
            else "Teléfono conectado. Para habilitar la asistencia, ingresá el código de conexión que te dio el equipo.")
    }
    fun saveServer(url: String, name: String, enrollmentCode: String = "", onConnected: () -> Unit = {}) {
        runCatching {
            require(state.value.guide.stage in listOf(Stage.IDLE, Stage.COMPLETE, Stage.CANCELLED)) { "Terminá la guía antes de cambiar de servidor." }
            stopAssistance()
            deviceSettingsJob?.cancel(); deviceSettingsJob = null
            settings.deviceName = name
            settings.baseUrl = url
            mutable.value = mutable.value.copy(deviceSettingsLoaded = false, institutionMetricsConsent = false, realtimeApproved = false)
            guideSession = null
            mutable.value = mutable.value.copy(familyName = null, shareProgress = false, allowHelp = false)
        }.onSuccess { connect(enrollmentCode, onConnected) }.onFailure { status(it.message ?: "Revisá la dirección.") }
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
