package org.nieto.assistant.flow

import java.util.UUID

enum class Target { ATTACH, LOCATION, SEND_LOCATION }
enum class Stage { IDLE, OPEN_CHAT, ATTACH, LOCATION, CONFIRM, SHARE, VERIFY, COMPLETE, CANCELLED }
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val valid get() = right > left && bottom > top && left >= 0 && top >= 0
}

/** Local-only observation. Tokens are ephemeral and never included in GuideEvent or telemetry. */
data class SafeScreen(
    val packageName: String,
    val version: String,
    val targets: Map<Target, Box> = emptyMap(),
    @Deprecated("Visible card counts cannot establish that a message is new") val outgoingLocations: Int = 0,
    val sensitive: Boolean = false,
    val recognized: Boolean = true,
    val contextToken: String? = null,
    val screenToken: String? = null,
    val revision: Long = 0,
    val scrolled: Boolean = false,
)

data class GuideState(
    val stage: Stage = Stage.IDLE,
    val instruction: String = "¿Qué te gustaría aprender hoy?",
    val target: Box? = null,
    val notice: String? = null,
    val practice: Boolean = false,
    val assistedPractice: Boolean = false,
    val completed: Boolean = false,
    val reportAvailable: Boolean = false,
    val completionSource: String? = null,
    val attemptId: String? = null,
)

data class GuideEvent(val type: String, val stepId: String? = null, val attemptId: String? = null)

/** Deterministic guidance only. Current WhatsApp profile has no validated semantic message IDs. */
class GuidanceEngine(private val event: (GuideEvent) -> Unit = {}) {
    companion object {
        const val FLOW_ID = "whatsapp-location"
        const val PROFILE_VERSION = "whatsapp-location-es@2-self-report"
        val SUPPORTED_PREFIXES = listOf("2.26.")
    }
    var state = GuideState()
        private set
    private enum class Surface { CHAT, ATTACHMENTS, PICKER, UNKNOWN }
    private data class Authorization(val context: String?, val screen: String?, val revision: Long, val sendBounds: Box?)
    private var authorization: Authorization? = null
    private var boundContext: String? = null
    private var lastScreen: SafeScreen? = null
    private var guidedReported = false
    private var chatObserved = false
    private val active get() = state.stage !in listOf(Stage.IDLE, Stage.COMPLETE, Stage.CANCELLED)

    fun start(practice: Boolean = false): GuideState {
        if (practice && (!guidedReported || active)) {
            state = state.copy(notice = "Primero terminá el intento acompañado y registrá tu reporte. Después podés practicar.")
            return state
        }
        authorization = null; boundContext = null; lastScreen = null; chatObserved = false
        if (!practice) guidedReported = false
        state = GuideState(stage = Stage.OPEN_CHAT, practice = practice,
            attemptId = if (practice) UUID.randomUUID().toString() else null,
            instruction = if (practice) "Probá de nuevo sin pistas. La confirmación de privacidad sigue activa; el resultado será tu reporte, no una verificación automática."
            else instruction(Stage.OPEN_CHAT))
        emit(if (practice) "practice_started" else "flow_started")
        emit("step_shown", "open_chat")
        return state
    }

    fun observe(screen: SafeScreen): GuideState {
        if (!active) return state
        lastScreen = screen
        val blocked = blockedReason(screen)
        if (blocked != null) {
            authorization = null; boundContext = null; chatObserved = false
            show(Stage.OPEN_CHAT)
            state = state.copy(target = null, reportAvailable = false, notice = blocked)
            return state
        }
        val surface = surface(screen)
        val changedContext = boundContext != null && screen.contextToken != null && boundContext != screen.contextToken
        if (changedContext || screen.scrolled) {
            authorization = null
            if (surface == Surface.CHAT) {
                show(Stage.OPEN_CHAT, force = true)
                boundContext = screen.contextToken
                chatObserved = true
                complete("open_chat"); show(Stage.ATTACH)
                select(screen, Target.ATTACH)
                state = state.copy(notice = if (changedContext) "Cambió la conversación. Revisá la persona destinataria y retomá desde Adjuntar."
                    else "Se movió el historial. Retomá desde Adjuntar; mostrar mensajes antiguos no acredita un envío.")
            } else {
                boundContext = null
                chatObserved = false
                show(Stage.OPEN_CHAT, force = true)
                state = state.copy(notice = "El contexto cambió. Volvé a la conversación para retomar; deberás confirmar otra vez antes de enviar.")
            }
            return state
        }
        state = state.copy(target = null, notice = null)
        when (state.stage) {
            Stage.OPEN_CHAT -> {
                if (surface == Surface.CHAT) {
                    boundContext = screen.contextToken
                    chatObserved = true
                    complete("open_chat"); show(Stage.ATTACH); select(screen, Target.ATTACH)
                } else state = state.copy(notice = "Volvé a la conversación elegida para retomar desde Adjuntar.")
            }
            Stage.ATTACH -> when (surface) {
                Surface.CHAT -> select(screen, Target.ATTACH)
                Surface.ATTACHMENTS -> { complete("attach"); show(Stage.LOCATION); select(screen, Target.LOCATION) }
                else -> { authorization = null; state = state.copy(notice = "Volvé a las opciones de Adjuntar para retomar el paso de Ubicación.") }
            }
            Stage.LOCATION -> when (surface) {
                Surface.CHAT -> recover(Stage.ATTACH, screen)
                Surface.ATTACHMENTS -> select(screen, Target.LOCATION)
                Surface.PICKER -> { complete("location"); show(Stage.CONFIRM) }
                else -> Unit
            }
            Stage.CONFIRM -> when (surface) {
                Surface.CHAT -> recover(Stage.ATTACH, screen)
                Surface.ATTACHMENTS -> recover(Stage.LOCATION, screen)
                Surface.PICKER -> Unit
                else -> Unit
            }
            Stage.SHARE -> when (surface) {
                Surface.PICKER -> if (authorization == identity(screen)) select(screen, Target.SEND_LOCATION)
                    else recover(Stage.CONFIRM, screen)
                Surface.ATTACHMENTS -> recover(Stage.LOCATION, screen)
                Surface.CHAT -> {
                    val allowed = authorization != null
                    authorization = null
                    if (allowed) {
                        complete("share"); show(Stage.VERIFY)
                        state = state.copy(reportAvailable = true, notice = verificationNotice())
                    } else recover(Stage.ATTACH, screen)
                }
                else -> { authorization = null; show(Stage.OPEN_CHAT); state = state.copy(notice = "No reconozco esta pantalla. Volvé a la conversación para retomar.") }
            }
            Stage.VERIFY -> when (surface) {
                Surface.CHAT -> state = state.copy(target = null, reportAvailable = true, notice = verificationNotice())
                Surface.ATTACHMENTS -> recover(Stage.LOCATION, screen)
                Surface.PICKER -> recover(Stage.CONFIRM, screen)
                else -> { authorization = null; show(Stage.OPEN_CHAT) }
            }
            else -> Unit
        }
        return state
    }

    fun confirm(): GuideState {
        val screen = lastScreen ?: return state
        if (state.stage != Stage.CONFIRM || state.notice != null || blockedReason(screen) != null || surface(screen) != Surface.PICKER) return state
        authorization = identity(screen)
        complete("confirm"); show(Stage.SHARE)
        select(screen, Target.SEND_LOCATION)
        return state
    }

    /** The person reports the outcome. No visible-card count can produce this event automatically. */
    fun reportSent(): GuideState {
        val screen = lastScreen ?: return state
        if (state.stage != Stage.VERIFY || !state.reportAvailable || blockedReason(screen) != null || surface(screen) != Surface.CHAT || screen.scrolled) return state
        if (boundContext != null && screen.contextToken != null && boundContext != screen.contextToken) return state
        complete("verify")
        emit(if (state.practice) "practice_reported" else "flow_reported")
        if (!state.practice) guidedReported = true
        authorization = null; boundContext = null; lastScreen = null; chatObserved = false
        state = state.copy(stage = Stage.COMPLETE, target = null, notice = null, completed = true,
            reportAvailable = false, completionSource = "self_reported",
            instruction = "Registré que vos reportaste el envío. No pude comprobar un mensaje nuevo ni acreditar autonomía.${if (!state.practice) " Podés practicar de nuevo." else ""}")
        return state
    }

    fun repeat(): GuideState {
        if (!active) return state
        emit("step_repeated", step(state.stage))
        if (state.practice) state = state.copy(assistedPractice = true)
        state = state.copy(instruction = instruction(state.stage))
        lastScreen?.let { screen ->
            if (blockedReason(screen) == null) when (state.stage) {
                Stage.ATTACH -> select(screen, Target.ATTACH)
                Stage.LOCATION -> select(screen, Target.LOCATION)
                Stage.SHARE -> if (authorization == identity(screen)) select(screen, Target.SEND_LOCATION)
                else -> Unit
            }
        }
        return state
    }

    fun lost(): GuideState {
        if (!active) return state
        emit("user_lost", step(state.stage))
        if (state.practice) state = state.copy(assistedPractice = true)
        authorization = null
        val screen = lastScreen
        if (chatObserved && screen != null && blockedReason(screen) == null) when (surface(screen)) {
            Surface.CHAT -> recover(Stage.ATTACH, screen)
            Surface.ATTACHMENTS -> recover(Stage.LOCATION, screen)
            Surface.PICKER -> recover(Stage.CONFIRM, screen)
            Surface.UNKNOWN -> show(Stage.OPEN_CHAT, force = true)
        } else {
            boundContext = null
            show(Stage.OPEN_CHAT, force = true)
            state = state.copy(notice = "Está bien ir de a poco. Abrí la conversación elegida para retomar desde Adjuntar.")
        }
        return state
    }

    fun cancel(): GuideState {
        if (active) emit("cancelled")
        authorization = null; boundContext = null; lastScreen = null; chatObserved = false
        state = state.copy(stage = Stage.CANCELLED, target = null, notice = null, reportAvailable = false, completed = false, completionSource = null,
            instruction = "Guía terminada. Dejé de observar la pantalla.")
        return state
    }
    private fun identity(screen: SafeScreen) = Authorization(screen.contextToken, screen.screenToken, screen.revision, screen.targets[Target.SEND_LOCATION])
    private fun surface(screen: SafeScreen): Surface = when {
        screen.targets[Target.SEND_LOCATION]?.valid == true -> Surface.PICKER
        screen.targets[Target.LOCATION]?.valid == true -> Surface.ATTACHMENTS
        screen.targets[Target.ATTACH]?.valid == true -> Surface.CHAT
        else -> Surface.UNKNOWN
    }
    private fun blockedReason(screen: SafeScreen): String? = when {
        screen.packageName != "com.whatsapp" -> "Saliste de WhatsApp. Volvé a la conversación; la confirmación anterior quedó anulada."
        screen.sensitive -> "Esta pantalla contiene datos sensibles o un permiso. La confirmación quedó anulada. Volvé a una conversación sin datos sensibles."
        SUPPORTED_PREFIXES.none { screen.version.startsWith(it) } -> "Esta versión de WhatsApp (${screen.version.ifBlank { "desconocida" }}) no tiene un perfil compatible. Pedí ayuda a alguien de confianza."
        !screen.recognized || surface(screen) == Surface.UNKNOWN -> "No reconozco los controles de esta pantalla. La confirmación quedó anulada; volvé a la conversación para retomar."
        else -> null
    }
    private fun recover(stage: Stage, screen: SafeScreen) {
        authorization = null
        show(stage, force = true)
        when (stage) {
            Stage.ATTACH -> select(screen, Target.ATTACH)
            Stage.LOCATION -> select(screen, Target.LOCATION)
            else -> Unit
        }
    }
    private fun select(screen: SafeScreen, target: Target) {
        val box = screen.targets[target]?.takeIf { it.valid }
        state = state.copy(target = if (state.practice && !state.assistedPractice) null else box,
            notice = if (box == null) "No encuentro el control esperado. No voy a marcar una posición aproximada." else state.notice)
    }
    private fun show(stage: Stage, force: Boolean = false) {
        val changed = state.stage != stage
        state = state.copy(stage = stage, instruction = if (state.practice && !state.assistedPractice && stage !in listOf(Stage.CONFIRM, Stage.VERIFY))
            "Seguí por tu cuenta. No mostraré pistas; el resultado sólo puede registrarse como tu reporte." else instruction(stage),
            target = null, notice = null, reportAvailable = false)
        if (changed || force) emit("step_shown", step(stage))
    }
    private fun complete(step: String) = emit("step_completed", step)
    private fun emit(type: String, stepId: String? = null) = event(GuideEvent(type, stepId, state.attemptId))
    private fun step(stage: Stage) = stage.name.lowercase()
    private fun verificationNotice() = "No puedo distinguir un mensaje nuevo de uno antiguo con la información accesible de WhatsApp. Si lo enviaste, podés registrar tu reporte; no se acreditará verificación ni autonomía."
    private fun instruction(stage: Stage): String = when (stage) {
        Stage.OPEN_CHAT -> "Abrí WhatsApp y elegí la conversación con la persona a la que querés compartir tu ubicación."
        Stage.ATTACH -> "Tocá Adjuntar, el botón con forma de clip al lado de donde escribís."
        Stage.LOCATION -> "Tocá Ubicación en las opciones para adjuntar."
        Stage.CONFIRM -> "Revisá la persona destinataria en WhatsApp. Vas a compartir tu ubicación actual. ¿Querés continuar en esta pantalla?"
        Stage.SHARE -> "Tocá Enviar mi ubicación actual. WhatsApp hará el envío sólo cuando vos toques el botón."
        Stage.VERIFY -> "Volviste a la conversación. Revisá vos si se envió la ubicación; NIETO no puede verificar el mensaje."
        else -> state.instruction
    }
}
