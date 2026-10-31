package org.nieto.assistant

import android.app.Application
import org.json.JSONArray
import java.util.UUID
import org.nieto.assistant.presence.ChatAttachment
import org.nieto.assistant.presence.FloatingBubbleService
import org.nieto.assistant.presence.WakeVoiceService
import android.app.KeyguardManager
import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.service.voice.VoiceInteractionService
import androidx.core.content.ContextCompat
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
import org.nieto.assistant.voice.WakeWordService
import org.nieto.assistant.voice.TinoVoiceInteractionService
import org.nieto.assistant.telemetry.TelemetrySession
import org.nieto.assistant.telemetry.TelemetrySync
import org.nieto.assistant.screen.*
import java.util.Locale

data class ChatAttachmentLabel(val name: String, val mimeType: String)
data class ChatLine(val fromUser: Boolean, val text: String, val attachments: List<ChatAttachmentLabel> = emptyList())
data class AppState(
    val guide: GuideState = GuideState(),
    val practiceFlow: org.nieto.assistant.demo.PracticeFlow? = null,
    val answer: String = "Contame qué querés hacer y te acompaño en la pantalla actual.",
    val status: String = "Tu ritmo. Tus decisiones.",
    val busy: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val overlayEnabled: Boolean = false,
    val familyName: String? = null,
    val familyPermissions: JSONObject = JSONObject(), val familyPaused: Boolean = false,
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
    val screenCapturePending: Boolean = false,
    val screenStep: ScreenStep? = null,
    val institutionMetricsConsent: Boolean = false,
    val deviceSettingsLoaded: Boolean = false,
    val territoryId: String = "AR-C",
    val nativeSurfaceVisible: Boolean = false,
    val voiceSurfaceVisible: Boolean = false,
    val chat: List<ChatLine> = emptyList(),
    val suggestedFlow: String? = null,
    val task: GuidedTask? = null, val emailDraft: EmailDraft? = null,
    val bubbleEnabled: Boolean = false,
    val wakePhase: String = "off",
    val presenceNotice: String? = null,
    val foreground: Boolean = false,
    val speaking: Boolean = false,
    val openChatEpoch: Int = 0,
    val recordingVoiceNote: Boolean = false,
    val familyInbox: List<JSONObject> = emptyList(),
    val textOnly: Boolean = false,
    val realtimeApproved: Boolean = false,
    val wakeEnabled: Boolean = false,
    val wakeStatus: String = "Activación por voz desactivada",
    val ttsSpeaking: Boolean = false,
    val assistantSelected: Boolean = false,
)

class NietoApp : Application() {
    lateinit var settings: LocalSettings; private set
    lateinit var api: NietoApi; private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    private lateinit var engine: GuidanceEngine
    private var sessionId: String? = null
    private var conversationId = UUID.randomUUID().toString()
    private var taskStartedAt = 0L
    private var manualTelemetry: TelemetrySession? = null
    private var guideSession: TelemetrySession? = null
    private var startedAt = 0L
    private var practiceStartedAt = 0L
    private var tts: TextToSpeech? = null
    private val naturalSpeech by lazy { org.nieto.assistant.voice.NaturalSpeechPlayer(
        onActive = { active -> mutable.value = mutable.value.copy(ttsSpeaking = active, speaking = active) },
        onFailure = { speechNotice("No pude reproducir la voz. La respuesta sigue disponible por escrito.") },
    ) }
    private var ttsReady = false
    private var ttsInitialized = false
    private var pendingSpeech: String? = null
    private var wakeGreetingJob: Job? = null
    private val guideAnnouncements = GuideAnnouncementPolicy()
    private var voiceRequestedFromOverlay = false
    private lateinit var realtime: RealtimeVoice
    private var voiceStartJob: Job? = null
    private var voiceStartRequest = 0L
    private var messageJob: Job? = null
    var screenCapture: ScreenCaptureBridge? = null
    private var preparedVoiceScreen: ScreenContext? = null
    private var pendingWakeAudio: ByteArray? = null
    private var voiceWithoutScreen = false
    private var voicePreparationTimeout: Job? = null
    private var observationRefresh: Job? = null
    private var screenCaptureWait: Job? = null
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
        if (settings.read("juryActive") == "true" || settings.token.startsWith("jury-")) {
            listOf("juryActive", "accountSession", "installation", "caregiverToken", "institutionToken", "onboardingComplete").forEach { settings.write(it, "") }
            settings.baseUrl = BuildConfig.API_BASE_URL
        }
        api = NietoApi(settings)
        TelemetrySync.initialize(this)
        scope.launch { TelemetrySync.status.collect { queue ->
            mutable.value = mutable.value.copy(telemetryPending = queue.pending, telemetryPaused = queue.paused, telemetryError = queue.error)
        } }
        mutable.value = mutable.value.copy(territoryId = settings.territoryId)
        realtime = RealtimeVoice(scope,
            onState = { voice ->
                if (state.value.voice.phase == VoicePhase.LISTENING && voice.phase == VoicePhase.WAITING) hasTask = true
                mutable.value = mutable.value.copy(voice = voice)
                if (!voice.active && voice.phase != VoicePhase.ERROR) scheduleScreenFollowUp()
            },
            onAnswer = { answer ->
                val current=state.value
                val updated=if(answer.isNotBlank() && current.chat.lastOrNull()?.text!=answer)
                    (current.chat+ChatLine(false,answer)).takeLast(30) else current.chat
                mutable.value = current.copy(answer = answer,chat=updated)
            },
            onTaskContext = { context -> hasTask = context != null },
            onConversationEnded = { clearConversationState() },
            onObservationRefresh = { refreshSilentObservation() },
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
    fun setWakeStatus(text: String) { mutable.value = mutable.value.copy(wakeStatus = text) }
    fun refreshAssistantRole() { mutable.value = mutable.value.copy(assistantSelected = VoiceInteractionService.isActiveService(this, ComponentName(this, TinoVoiceInteractionService::class.java))) }
    fun startWakeListening() {
        refreshAssistantRole()
        val next = org.nieto.assistant.voice.WakeSetupPolicy.next(org.nieto.assistant.voice.WakeSetupRequirements(
            linked = settings.token.isNotBlank(), consent = settings.openAiConsent && settings.remoteScreenConsent,
            microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            notifications = android.os.Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            assistant = state.value.assistantSelected, accessibility = state.value.accessibilityConnected && screenCapture != null,
        ))
        if (next != org.nieto.assistant.voice.WakeSetupStep.READY) {
            val message = when (next) {
                org.nieto.assistant.voice.WakeSetupStep.CONNECTION -> "Conectá este dispositivo antes de activar Hola TINO."
                org.nieto.assistant.voice.WakeSetupStep.CONSENT -> "Aceptá el permiso de voz y asistencia para activar Hola TINO."
                org.nieto.assistant.voice.WakeSetupStep.MICROPHONE -> "Permití el micrófono para activar Hola TINO."
                org.nieto.assistant.voice.WakeSetupStep.NOTIFICATIONS -> "Permití las notificaciones de la escucha de TINO."
                org.nieto.assistant.voice.WakeSetupStep.ASSISTANT -> "Elegí TINO como asistente de Android."
                else -> "Activá el servicio de Accesibilidad de TINO y volvé a la app."
            }
            setWakeStatus(message); presenceNotice(message); return
        }
        if (!state.value.assistanceEnabled) enableAssistance()
        if (!state.value.assistanceEnabled) return
        settings.wakeOptIn = true
        mutable.value = mutable.value.copy(wakeEnabled = true, textOnly = false)
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_START))
        }.onFailure {
            mutable.value = mutable.value.copy(wakeEnabled = false, wakeStatus = "Android no permitió iniciar la escucha. Revisá el asistente predeterminado y el permiso de micrófono.")
        }
    }
    fun restoreWakeListening() {
        if (settings.wakeOptIn && state.value.accessibilityConnected && screenCapture != null &&
            VoiceInteractionService.isActiveService(this, ComponentName(this, TinoVoiceInteractionService::class.java))) startWakeListening()
    }
    fun stopWakeListening(clearPreference: Boolean = true) {
        if (clearPreference) settings.wakeOptIn = false
        wakeGreetingJob?.cancel(); wakeGreetingJob = null
        mutable.value = mutable.value.copy(wakeEnabled = false, wakeStatus = "Activación por voz desactivada")
        stopService(Intent(this, WakeWordService::class.java))
        cancelRealtimeVoice(clearTaskContext = clearPreference)
    }
    fun requestSystemVoice() {
        if (!state.value.wakeEnabled) return
        startService(Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_TALK))
    }
    /** Called only after the foreground service has released the local wake microphone. */
    fun startVoiceFromWake(invocation: org.nieto.assistant.voice.WakeInvocationPCM? = null) {
        if (!state.value.wakeEnabled || !state.value.assistanceEnabled || state.value.busy || state.value.voice.active) {
            invocation?.pcm24k?.fill(0); return
        }
        val locked = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        if (locked) realtime.cancel(clearTaskContext = true)
        realtime.noteUserRequest()
        voiceWithoutScreen = locked
        pendingWakeAudio?.fill(0); pendingWakeAudio = invocation?.pcm24k
        observationRefresh?.cancel(); observationRefresh = null
        stopSpeaking()
        mutable.value = mutable.value.copy(voice = VoiceState(VoicePhase.CONNECTING, "Preparando la consulta…"))
        prepareVoice { startRealtimeVoice() }
    }
    fun speak(text: String = state.value.guide.notice ?: state.value.guide.instruction) {
        if (text.isBlank() || (state.value.assistanceEnabled && state.value.textOnly)) return
        naturalSpeech.stop()
        if (!ttsReady) {
            if (!ttsInitialized) { pendingSpeech = text; speechNotice("La voz se está preparando. La instrucción sigue disponible en pantalla.") }
            else if (state.value.ttsNotice.isBlank()) speechNotice("La lectura por voz no está disponible. Podés leer la instrucción en pantalla.")
            return
        }
        pendingSpeech = null
        mutable.value = mutable.value.copy(ttsSpeaking = true, speaking = true)
        if (runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nieto_instruction") ?: TextToSpeech.ERROR }.getOrDefault(TextToSpeech.ERROR) == TextToSpeech.ERROR)
            { mutable.value = mutable.value.copy(ttsSpeaking = false, speaking = false); speechNotice("Android no pudo iniciar la lectura. Revisá el motor de voz y el idioma español; la instrucción sigue en pantalla.") }
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
            override fun onStart(utteranceId: String?) { scope.launch { mutable.value = mutable.value.copy(ttsSpeaking = true, speaking = true) } }
            override fun onDone(utteranceId: String?) { scope.launch { mutable.value = mutable.value.copy(ttsSpeaking = false, speaking = false) } }
            @Deprecated("Android compatibility callback") override fun onError(utteranceId: String?) { scope.launch { mutable.value = mutable.value.copy(ttsSpeaking = false, speaking = false); speechNotice("La lectura por voz falló. Podés repetir el paso o leerlo en pantalla.") } }
            override fun onError(utteranceId: String?, errorCode: Int) { onError(utteranceId) }
        })
        ttsReady = true; speechNotice("")
        pendingSpeech?.let { speak(it) }
    }
    private fun speechNotice(message: String) { mutable.value = mutable.value.copy(ttsNotice = message) }
    fun stopSpeaking() { pendingSpeech = null; naturalSpeech.stop(); runCatching { tts?.stop() }; mutable.value = mutable.value.copy(ttsSpeaking = false, speaking = false) }
    fun retryTelemetry() { TelemetrySync.retryRetained(this) }
    fun enableAssistance() {
        if (!settings.remoteScreenConsent || !settings.openAiConsent) { status("Aceptá el permiso nuevo de asistencia con OpenAI."); return }
        if (state.value.busy) { status("Esperá a que termine la acción actual antes de cambiar de modo."); return }
        // Cancel while the original session is still bound, then detach it before remote events can arrive.
        engine.cancel()
        guideSession = null
        guideAnnouncements.reset()
        stopSpeaking()
        mutable.value = mutable.value.copy(guide = engine.state, assistanceEnabled = true, overlayEnabled = true,
            answer = "Decí Hola Tino desde la aplicación donde necesitás ayuda.")
        status("Asistencia activa. Vos hacés cada toque.")
    }
    fun prepareVoice(quietFailure: Boolean = false, onReady: () -> Unit) {
        if (!state.value.assistanceEnabled || !settings.remoteScreenConsent) return
        mutable.value = mutable.value.copy(textOnly = false, voice = VoiceState(VoicePhase.CONNECTING, "Preparando la consulta…"))
        stopSpeaking()
        realtime.selectOutputMode(textOnly = false)
        voicePreparationTimeout?.cancel()
        val request = assistantRequest
        var completed = false
        fun failed() {
            if (completed || request != assistantRequest || !state.value.assistanceEnabled) return
            completed = true
            pendingWakeAudio?.fill(0); pendingWakeAudio = null
            voicePreparationTimeout?.cancel(); voicePreparationTimeout = null
            val message = "No pude preparar la consulta. Podés volver a decir Hola Tino."
            if (quietFailure) realtime.skipFollowUp()
            mutable.value = mutable.value.copy(voice = VoiceState(VoicePhase.ERROR, message, audibleError = !quietFailure))
            realtime.requestedAttemptFinished()
        }
        voicePreparationTimeout = scope.launch { delay(6000); failed() }
        val ready: (ScreenContext?) -> Unit = { screen ->
            if (!completed && request == assistantRequest && state.value.assistanceEnabled) {
                completed = true
                voicePreparationTimeout?.cancel(); voicePreparationTimeout = null
                preparedVoiceScreen = if (phoneLocked()) null else screen?.takeUnless { it.blocked }
                onReady()
            }
        }
        try { if (voiceWithoutScreen || phoneLocked()) ready(null) else screenCapture?.capture(ready) ?: ready(null) }
        catch (_: Exception) { failed() }
    }
    fun startRealtimeVoice(followUp: Boolean = false) {
        if (!settings.openAiConsent || !settings.remoteScreenConsent || !state.value.assistanceEnabled || state.value.busy) {
            pendingWakeAudio?.fill(0); pendingWakeAudio = null
            realtime.abandonPreparation(); return
        }
        submitAssistant(null, preparedVoiceScreen.also { preparedVoiceScreen = null }, followUp)
    }
    private fun submitAssistant(text: String?, screen: ScreenContext?, followUp: Boolean = false) {
        realtime.noteUserRequest()
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
                if (!text.isNullOrBlank()) hasTask = true
                val currentScreen = screen?.takeIf { !it.blocked && state.value.screenValid && state.value.screen?.id == it.id &&
                    state.value.screenPackage == it.packageName && android.os.SystemClock.elapsedRealtime() - it.capturedAt in 0..15_000 }
                lastScreenSent = currentScreen?.id
                lastSnapshotSentAt = android.os.SystemClock.elapsedRealtime()
                val inputAudio = pendingWakeAudio.also { pendingWakeAudio = null }
                realtime.start(settings.baseUrl, settings.token, if (voiceWithoutScreen || phoneLocked()) null else currentScreen?.toWire(), text,
                    textOnly = state.value.textOnly, followUp = followUp, inputAudio = inputAudio)
            } catch (_: CancellationException) { }
            catch (error: Exception) {
                pendingWakeAudio?.fill(0); pendingWakeAudio = null
                if (request == assistantRequest) {
                    val message = if (error is ApiFailure || error is IllegalArgumentException) error.message.orEmpty().take(200)
                        else "No pude conectar la asistencia. Revisá el servidor desde Ajustes."
                    if (followUp) realtime.skipFollowUp()
                    mutable.value = mutable.value.copy(voice = VoiceState(VoicePhase.ERROR, message, audibleError = !followUp))
                    realtime.requestedAttemptFinished()
                }
            } finally { if (request == assistantRequest) mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun finishRealtimeInput() { realtime.finishInput() }
    fun cancelRealtimeVoice(clearTaskContext: Boolean = true) {
        pendingWakeAudio?.fill(0); pendingWakeAudio = null
        voicePreparationTimeout?.cancel(); voicePreparationTimeout = null
        screenCaptureWait?.cancel(); screenCaptureWait = null
        ++assistantRequest; ++voiceStartRequest
        voiceStartJob?.cancel(); voiceStartJob = null
        mutable.value = mutable.value.copy(busy = true, screenCapturePending = false)
        val release = realtime.cancel(clearTaskContext = clearTaskContext)
        val request = assistantRequest
        scope.launch {
            release?.join()
            if (request == assistantRequest) mutable.value = mutable.value.copy(busy = false, screenStep = null)
        }
    }

    fun handleWakeControl(action: org.nieto.assistant.voice.WakeControlAction) {
        val decision = org.nieto.assistant.voice.WakeControlPolicy.decide(action)
        if (decision.disableWake) stopAssistance()
        else if (decision.clearConversation) endConversation()
    }

    val hasActiveConversation: Boolean get() = realtime.hasActiveConversation
    val taskMemoryPresent: Boolean get() = realtime.taskMemoryPresent
    val transportReady: Boolean get() = realtime.transportReady
    val idleRemainingMs: Long get() = realtime.idleRemainingMs
    fun onUserScreenAction(kind: String) {
        if (phoneLocked()) return
        if (state.value.assistanceEnabled && state.value.wakeEnabled) realtime.noteUserActivity(kind)
    }

    private fun phoneLocked(): Boolean = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    private fun refreshSilentObservation() {
        observationRefresh?.cancel()
        val request = assistantRequest
        observationRefresh = scope.launch {
            delay(650)
            if (request != assistantRequest || !state.value.assistanceEnabled || voiceWithoutScreen || phoneLocked() || state.value.voice.active ||
                !realtime.taskMemoryPresent || !realtime.hasActiveConversation) return@launch
            screenCapture?.capture { screen ->
                if (request == assistantRequest && state.value.assistanceEnabled && screen != null && !screen.blocked &&
                    realtime.taskMemoryPresent && realtime.hasActiveConversation && !state.value.voice.active) {
                    realtime.resumeObservation(screen.copy(image = null).toWire())
                }
            }
        }
    }

    fun endConversation() { cancelRealtimeVoice(clearTaskContext = true) }

    private fun clearConversationState() {
        pendingWakeAudio?.fill(0); pendingWakeAudio = null
        voiceWithoutScreen = false
        observationRefresh?.cancel(); observationRefresh = null
        ++assistantRequest; ++voiceStartRequest
        wakeGreetingJob?.cancel(); wakeGreetingJob = null
        voicePreparationTimeout?.cancel(); voicePreparationTimeout = null
        voiceStartJob?.cancel(); voiceStartJob = null
        messageJob?.cancel(); messageJob = null
        followUp?.cancel(); followUp = null
        preparedVoiceScreen = null; hasTask = false; lastScreenSent = null; automaticSteps = 0
        stopSpeaking()
        mutable.value = mutable.value.copy(answer = "", busy = false, screenStep = null, screen = null, screenValid = false, chat = emptyList(),
            suggestedFlow = null, emailDraft = null, screenCapturePending = false)
    }

    fun onAssistantBackground() {
        if (state.value.wakeEnabled) return
        if (voiceStartJob?.isActive == true || state.value.voice.phase in listOf(VoicePhase.CONNECTING, VoicePhase.LISTENING)) cancelRealtimeVoice(clearTaskContext = false)
        else if (state.value.voice.active) realtime.cancelResponseForScreenChange()
        stopSpeaking()
    }
    fun continueWithoutTino() {
        // An explicit decision is distinguishable from a lost connection or a closed popup.
        assistantEvent("assistance_ended", withStep = false)
        stopAssistance()
        tellUser("Seguís por tu cuenta. Dejé de observar la pantalla; no voy a registrar un resultado que no confirmaste.")
    }
    fun stopAssistance(clearWakePreference: Boolean = true) {
        voicePreparationTimeout?.cancel(); voicePreparationTimeout = null
        if (state.value.wakeEnabled || (clearWakePreference && settings.wakeOptIn))
            stopWakeListening(clearPreference = clearWakePreference)
        screenCaptureWait?.cancel(); screenCaptureWait = null
        mutable.value = mutable.value.copy(screenCapturePending = false)
        assistantEvent("cancelled", withStep = false, duration = true)
        assistantSession = null
        ++assistantRequest; ++voiceStartRequest
        hasTask = false
        followUp?.cancel(); followUp = null
        voiceStartJob?.cancel(); voiceStartJob = null
        messageJob?.cancel(); messageJob = null
        realtime.cancel(clearTaskContext = clearWakePreference); stopSpeaking()
        preparedVoiceScreen = null; hasTask = false; lastScreenSent = null; automaticSteps = 0
        mutable.value = mutable.value.copy(assistanceEnabled = false, overlayEnabled = false, screen = null, screenValid = false,
            screenStep = null, answer = "La asistencia terminó. El contexto de pantalla se eliminó.", busy = false)
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
        // Screen changes retire coordinates, never the user's ongoing spoken answer.
        if (cancelResponse && state.value.assistanceEnabled) realtime.clearScreen()
    }
    fun screenUnavailable() {
        if (voiceWithoutScreen || phoneLocked()) {
            if (!voiceWithoutScreen) endConversation()
            voiceWithoutScreen = true
            return
        }
        if (state.value.assistanceEnabled) {
            followUp?.cancel(); followUp = null
            mutable.value = mutable.value.copy(screen = null, screenValid = false, screenStep = null)
            if (state.value.voice.phase in listOf(VoicePhase.LISTENING, VoicePhase.CONNECTING)) realtime.updateScreenWhileListening(null) else realtime.clearScreen()
        }
    }
    fun onScreenObserved(screen: ScreenContext) {
        if (!state.value.assistanceEnabled || !settings.remoteScreenConsent) return
        if (voiceWithoutScreen || phoneLocked()) {
            if (!voiceWithoutScreen) endConversation()
            voiceWithoutScreen = true
            mutable.value = mutable.value.copy(screen = null, screenValid = false, screenStep = null)
            return
        }
        val changed = state.value.screen?.id != screen.id
        mutable.value = mutable.value.copy(screen = screen.copy(image = null), screenValid = !screen.blocked,
            screenStep = if (changed || screen.blocked) null else state.value.screenStep)
        if (screen.blocked) {
            followUp?.cancel()
            if (state.value.voice.phase in listOf(VoicePhase.LISTENING, VoicePhase.CONNECTING)) realtime.updateScreenWhileListening(null)
            else realtime.clearScreen()
        } else if (changed) {
            if (state.value.voice.phase in listOf(VoicePhase.LISTENING, VoicePhase.CONNECTING)) {
                realtime.updateScreenWhileListening(screen.toWire())
            }
            scheduleScreenFollowUp()
        }
    }
    private fun scheduleScreenFollowUp() {
        val current = state.value
        if (!current.assistanceEnabled || current.busy || !current.screenValid ||
            current.screen?.id == lastScreenSent || current.screen == null) return
        followUp?.cancel()
        val request = assistantRequest
        followUp = scope.launch {
            val now = android.os.SystemClock.elapsedRealtime()
            delay(maxOf(1200L, 4000L - (now - lastFollowUpAt)))
            if (request != assistantRequest || !state.value.assistanceEnabled) return@launch
            screenCapture?.capture { screen ->
                if (request == assistantRequest && screen != null && !screen.blocked && state.value.screen?.id == screen.id && screen.id != lastScreenSent) {
                    // Observation is silent. Only an explicit voice/text request asks the model to respond.
                    if (realtime.updateScreen(screen.toWire(), requestGuidance = false)) {
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
        if (state.value.voice.phase in listOf(VoicePhase.LISTENING, VoicePhase.CONNECTING)) cancelRealtimeVoice(clearTaskContext = false)
        else if (state.value.voice.active) realtime.cancelResponseForScreenChange()
        sendVisualMessage("Me perdí. Explicame el próximo paso de otra manera con la pantalla actual.", textOnly = state.value.textOnly)
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
    fun cancel(silent: Boolean = false) {
        stopSpeaking()
        val next = engine.cancel()
        if (silent) mutable.value = mutable.value.copy(guide = next) else updateGuide(next)
    }
    fun screenConnection(connected: Boolean) {
        mutable.value = mutable.value.copy(accessibilityConnected = connected)
        if (!connected) {
            cancelRealtimeVoice(clearTaskContext = false)
            stopSpeaking()
            mutable.value = mutable.value.copy(screenValid = false, screenStep = null)
        }
    }
    fun setOverlay(enabled: Boolean) { mutable.value = mutable.value.copy(overlayEnabled = enabled) }

    fun sendMessage(text: String, attachments: List<ChatAttachment> = emptyList(), onComplete: (Boolean) -> Unit = {}) {
        val clean = text.trim().take(1000).ifBlank { if (attachments.isNotEmpty()) "Ayudame a entender los archivos que adjunté." else "" }
        if (clean.isBlank() || state.value.busy || state.value.screenCapturePending || state.value.voice.active) { onComplete(false); return }
        if(!settings.openAiConsent && !settings.juryDemo){tellUser("Autorizá las consultas con IA en Ajustes para conversar con TINO.");onComplete(false);return}
        if(!settings.juryDemo && state.value.assistanceEnabled && attachments.isEmpty()) { sendVisualMessage(clean,onSubmitted=onComplete);return }
        if (attachments.size > ChatAttachment.MAX_COUNT || attachments.sumOf { it.byteSize } > ChatAttachment.MAX_TOTAL_BYTES) { onComplete(false); return }
        val readAloud = !(state.value.assistanceEnabled && state.value.textOnly)
        stopSpeaking()
        mutable.value = mutable.value.copy(chat = (state.value.chat + ChatLine(true, clean, attachments.map { ChatAttachmentLabel(it.name,it.mimeType) })).takeLast(30), busy = true, suggestedFlow = null)
        messageJob = scope.launch {
            val typingStartedAt = android.os.SystemClock.elapsedRealtime()
            try {
                api.register()
                val history = JSONArray()
                state.value.chat.dropLast(1).takeLast(12).forEach { line -> history.put(JSONObject().put("role", if (line.fromUser) "user" else "assistant").put("content", line.text.take(1000))) }
                val body = JSONObject().put("text", clean).put("conversationId", conversationId).put("history", history)
                    .put("outputMode", if (readAloud) "audio" else "text")
                while (body.toString().toByteArray(Charsets.UTF_8).size > 14_000 && history.length() > 0) history.remove(0)
                // Chat is independent of the local outbox session identifier.
                if (attachments.isNotEmpty()) body.put("attachments", JSONArray().apply { attachments.forEach { put(it.wire()) } })
                val reply = api.request("/assistant/message", "POST", body)
                if (reply.optBoolean("ended")) { endConversation(); onComplete(true); return@launch }
                var answer = reply.optString("instruction", "No pude encontrar una guía para esa consulta.")
                val sources = reply.optJSONArray("sources")
                if (sources != null) for (index in 0 until sources.length().coerceAtMost(3)) {
                    val source = sources.optJSONObject(index) ?: continue
                    val title = source.optString("title")
                    val url = source.optString("url")
                    if (url.startsWith("https://")) answer += "\nFuente: $title\n$url"
                }
                val draft = reply.optJSONObject("emailDraft")?.let { EmailDraft(it.getString("to"), it.getString("subject"), it.getString("body")) }
                delay((2000L - (android.os.SystemClock.elapsedRealtime() - typingStartedAt)).coerceAtLeast(0L))
                mutable.value = mutable.value.copy(chat = (state.value.chat + ChatLine(false, answer)).takeLast(30), suggestedFlow = reply.optString("flowId").takeIf { it.isNotBlank() }, emailDraft = if (reply.optBoolean("clearEmailDraft")) null else draft ?: state.value.emailDraft)
                if (readAloud) {
                    val audio = reply.optJSONObject("audio")
                    if (audio != null) naturalSpeech.play(audio.optString("base64"), audio.optString("mimeType"))
                    else speechNotice("La voz no estuvo disponible. La respuesta sigue por escrito.")
                }
                if (reply.optString("flowId") == GuidanceEngine.FLOW_ID) status("Podés iniciar la guía con el botón Compartir ubicación.")
                onComplete(true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { mutable.value = mutable.value.copy(chat = (state.value.chat + ChatLine(false, "No pude enviar tu mensaje. Probá de nuevo.")).takeLast(30), status = e.message ?: "No pude enviar la consulta."); onComplete(false) }
            finally { if(messageJob==kotlin.coroutines.coroutineContext[Job]) { messageJob=null;mutable.value = mutable.value.copy(busy = false) } }
        }
    }
    fun sendVisualMessage(text: String, textOnly: Boolean = true, onSubmitted: (Boolean) -> Unit = {}): Boolean {
        val clean = text.trim().take(1000)
        if (clean.isBlank() || !state.value.assistanceEnabled || !settings.remoteScreenConsent || !settings.openAiConsent || state.value.busy || state.value.voice.active) {
            onSubmitted(false)
            if (clean.isNotBlank()) status("Esperá a que termine la respuesta. Tu consulta escrita se conserva.")
            return false
        }
        val request = ++assistantRequest
        mutable.value = mutable.value.copy(busy = true, textOnly = textOnly)
        stopSpeaking()
        screenCaptureWait?.cancel()
        screenCaptureWait=scope.launch {
            delay(6000)
            if(request==assistantRequest) { assistantRequest++;mutable.value=mutable.value.copy(busy=false);status("No pude preparar la pantalla. Tu consulta escrita se conserva.");onSubmitted(false) }
        }
        val ready: (ScreenContext?) -> Unit = { screen ->
            if (request == assistantRequest && state.value.assistanceEnabled) {
                screenCaptureWait?.cancel();screenCaptureWait=null
                if (screen?.blocked == true) {
                    mutable.value = mutable.value.copy(busy = false)
                    status(screen.notice)
                    onSubmitted(false)
                } else { mutable.value=mutable.value.copy(chat=(state.value.chat+ChatLine(true,clean)).takeLast(30));submitAssistant(clean, screen); onSubmitted(true) }
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
        if (changed && state.value.voice.phase in listOf(VoicePhase.CONNECTING, VoicePhase.LISTENING)) cancelRealtimeVoice(clearTaskContext = false)
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
        mutable.value = mutable.value.copy(familyName = group?.optString("name"), familyPermissions = permissions ?: JSONObject(), familyPaused = response.optBoolean("paused"),
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
        org.nieto.assistant.usage.DeviceUsage.enable(this,false)
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
    fun foreground(visible: Boolean) { mutable.value = mutable.value.copy(foreground = visible) }

    private var chatOpenPending = false
    fun openChat() { chatOpenPending=true;mutable.value=mutable.value.copy(openChatEpoch=state.value.openChatEpoch+1) }
    fun consumeChatOpen(): Boolean = chatOpenPending.also { chatOpenPending = false }

    private val audioUse=org.nieto.assistant.presence.AudioUseGate()
    fun pauseWakeForRecording(owner: String = "overlay-dictation") { audioUse.acquire(owner); mutable.value = mutable.value.copy(recordingVoiceNote = audioUse.active) }

    fun resumeWakeAfterRecording(owner: String = "overlay-dictation") { audioUse.release(owner); mutable.value = mutable.value.copy(recordingVoiceNote = audioUse.active) }

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
        if (state.value.wakeEnabled) stopWakeListening()
        stopService(Intent(this, FloatingBubbleService::class.java))
        stopService(Intent(this, WakeVoiceService::class.java))
        mutable.value = mutable.value.copy(bubbleEnabled = false, wakePhase = "off", presenceNotice = null)
    }

    fun resetConversation() {
        cancelRealtimeVoice(); stopAssistance(); guideSession = null; manualTelemetry = null
        stopSpeaking()
        sessionId = null
        updateGuide(engine.cancel())
        conversationId = UUID.randomUUID().toString()
        mutable.value = mutable.value.copy(practiceFlow = null, chat = AppState().chat, suggestedFlow = null, task = null, emailDraft = null, familyInbox = emptyList())
    }

    fun closeChatConversation() {
        cancelRealtimeVoice();clearConversationState()
        guideSession=null;manualTelemetry=null;sessionId=null
        conversationId=UUID.randomUUID().toString()
        mutable.value=mutable.value.copy(practiceFlow=null,task=null)
    }

    fun showWelcomeMessage() {
        if (state.value.chat.isNotEmpty() || state.value.busy) return
        mutable.value = mutable.value.copy(chat = listOf(ChatLine(false,
            "Hola, soy TINO. Contame qué necesitás hacer con tu teléfono. Podés escribirme o hablarme.")))
    }

    fun openPractice(flow: org.nieto.assistant.demo.PracticeFlow) { stopSpeaking(); mutable.value=mutable.value.copy(practiceFlow=flow) }
    fun closePractice() { stopSpeaking(); mutable.value=mutable.value.copy(practiceFlow=null) }
    fun recordPractice(flow: org.nieto.assistant.demo.PracticeFlow,id:String) {
        if(!settings.juryDemo)return
        scope.launch { runCatching { api.request("/demo/practice/report","POST",JSONObject().put("flowId",if(flow==org.nieto.assistant.demo.PracticeFlow.WHATSAPP)"whatsapp-group" else "email-send").put("id",id)) } }
    }

    fun tellUser(text: String) { mutable.value = mutable.value.copy(chat = (state.value.chat + ChatLine(false, text)).takeLast(30)); speak(text) }

    fun startTask(flowId: String, onReady: () -> Unit) = work {
        require(flowId in setOf("whatsapp-group", "email-send"))
        require(state.value.task?.active != true) { "Terminá o cancelá la guía actual antes de empezar otra." }
        cancel()
        sessionId = null
        if (flowId != "email-send" || state.value.suggestedFlow != "email-send") mutable.value = mutable.value.copy(emailDraft = null)
        api.register()
        manualTelemetry = TelemetrySync.session(settings.baseUrl, settings.token, flowId)
        val task = GuidedTask.from(JSONObject().put("id", manualTelemetry!!.clientSessionId).put("flow", api.request("/assistant/flows").getJSONArray("flows").let { flows -> (0 until flows.length()).map { flows.getJSONObject(it) }.first { it.getString("id") == flowId } }))
        manualEvent("flow_started")
        manualEvent("step_shown", task.current.id)
        taskStartedAt = android.os.SystemClock.elapsedRealtime()
        mutable.value = mutable.value.copy(task = task, suggestedFlow = null)
        speak(task.current.instruction)
        onReady()
    }

    fun advanceTask() = work {
        val task = state.value.task?.takeIf { it.active } ?: return@work
        manualEvent("step_completed", task.current.id)
        if (task.index == task.steps.lastIndex) {
            val seconds = ((android.os.SystemClock.elapsedRealtime() - taskStartedAt) / 1000).coerceIn(0, 14400)
            manualEvent("flow_reported", duration = seconds.toInt())
            mutable.value = mutable.value.copy(task = task.copy(completed = true), emailDraft = null)
            tellUser(if (task.flowId == "email-send") "Marcaste que el correo está en Enviados. Completaste la guía." else "Marcaste que ya ves tu grupo creado. Completaste la guía.")
        } else {
            val next = task.copy(index = task.index + 1)
            manualEvent("step_shown", next.current.id)
            mutable.value = mutable.value.copy(task = next)
            speak(next.current.instruction)
        }
    }

    fun repeatTask() { state.value.task?.takeIf{it.active}?.let { manualEvent("step_repeated",it.current.id);speak(it.current.instruction) } }

    fun cancelTask() = work {
        val task = state.value.task?.takeIf { it.active } ?: return@work
        manualEvent("cancelled")
        mutable.value = mutable.value.copy(task = task.copy(cancelled = true), emailDraft = null); stopSpeaking()
    }

    fun dismissTask() { if (state.value.task?.active != true) mutable.value = mutable.value.copy(task = null) }

    private fun manualEvent(type: String, step: String? = null, duration: Int? = null) { manualTelemetry?.let { TelemetrySync.enqueue(this, it, type, step, duration) } }

    fun acceptFamilyPermissions(code: String,permissions: JSONObject)=work {
        api.register();api.request("/family/link","POST",JSONObject().put("invitationCode",code.trim()).put("permissions",permissions));refreshFamilyInternal()
    }
    fun updateFamilyPermissions(permissions: JSONObject)=work { api.request("/family/link","PATCH",JSONObject().put("permissions",permissions));if(!permissions.optBoolean("usage"))org.nieto.assistant.usage.DeviceUsage.enable(this,false);refreshFamilyInternal();status("Tus permisos quedaron actualizados.") }
    fun pauseFamily(paused: Boolean)=work { api.request("/family/link","PATCH",JSONObject().put("paused",paused));refreshFamilyInternal();status(if(paused)"Se pausó lo compartido con tu grupo." else "Se reanudaron los permisos elegidos.") }
    fun publishFamilyLocation(latitude: Double,longitude: Double,accuracy: Double)=work { api.request("/family/location","POST",JSONObject().put("latitude",latitude).put("longitude",longitude).put("accuracy",accuracy).put("minutes",15));status("Ubicación aproximada compartida por 15 minutos.") }
    fun stopFamilyLocation()=work { api.request("/family/location","DELETE");status("Dejaste de compartir tu ubicación.") }

    fun handleWakeCommand(text: String) {
        val clean=text.trim().take(1000)
        if(clean.isBlank())return
        when(org.nieto.assistant.presence.WakeCommandPolicy.route(clean,state.value.nativeSurfaceVisible,
            settings.remoteScreenConsent && settings.openAiConsent,state.value.accessibilityConnected && screenCapture!=null)) {
            org.nieto.assistant.presence.WakeRoute.CONVERSATION -> sendMessage(clean)
            org.nieto.assistant.presence.WakeRoute.STOP_SCREEN_ASSISTANCE -> {stopAssistance();tellUser("Dejé de observar la pantalla. Podés volver a pedirme ayuda cuando quieras.")}
            org.nieto.assistant.presence.WakeRoute.NEEDS_ACCESSIBILITY -> tellUser("Para señalar botones, activá Accesibilidad de TINO desde Ajustes. Después volvemos a intentarlo.")
            org.nieto.assistant.presence.WakeRoute.SCREEN_ASSISTANCE -> {
                if(!state.value.assistanceEnabled)enableAssistance()
                mutable.value=mutable.value.copy(textOnly=false)
                realtime.selectOutputMode(textOnly=false)
                automaticSteps=0
                sendScreenMessage(clean)
            }
        }
    }

    fun sendScreenMessage(text: String,onCaptured: () -> Unit = {}) {
        if(settings.juryDemo) { sendMessage(text);onCaptured();return }
        val clean = text.trim().take(1000)
        if (clean.isBlank() || !state.value.assistanceEnabled || !settings.remoteScreenConsent || !settings.openAiConsent || state.value.busy || state.value.screenCapturePending || state.value.voice.active) { onCaptured(); return }
        followUp?.cancel()
        val request = ++assistantRequest
        var delivered=false
        mutable.value=mutable.value.copy(screenCapturePending=true,screenStep=null)
        screenCaptureWait?.cancel()
        screenCaptureWait=scope.launch {
            delay(6000)
            if(!delivered && request==assistantRequest) {
                delivered=true
                mutable.value=mutable.value.copy(screenCapturePending=false)
                onCaptured()
                tellUser("No pude leer la pantalla actual. Volvé a intentarlo en una pantalla sin datos sensibles.")
            }
        }
        val ready: (ScreenContext?) -> Unit = { screen ->
            if(!delivered && request==assistantRequest) {
                delivered=true;screenCaptureWait?.cancel();screenCaptureWait=null
                mutable.value=mutable.value.copy(screenCapturePending=false)
                onCaptured()
                if(state.value.assistanceEnabled) {
                    when {
                        screen==null -> tellUser("No pude leer esta pantalla. No voy a señalar un botón sin poder identificarlo.")
                        screen.blocked -> tellUser(screen.notice)
                        else -> { mutable.value=mutable.value.copy(chat=(state.value.chat+ChatLine(true,clean)).takeLast(30));submitAssistant(clean,screen) }
                    }
                }
            }
        }
        screenCapture?.capture(ready) ?: ready(null)
    }

    fun startConversationVoice() {
        if (state.value.wakeEnabled) requestSystemVoice()
        else {
            if (!state.value.assistanceEnabled) enableAssistance()
            prepareVoice { startRealtimeVoice() }
        }
    }

}
