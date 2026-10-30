package org.nieto.assistant.flow

enum class Target { ATTACH, LOCATION, SEND_LOCATION }
enum class Stage { IDLE, OPEN_CHAT, ATTACH, LOCATION, CONFIRM, SHARE, VERIFY, COMPLETE, CANCELLED }

data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val valid get() = right > left && bottom > top && left >= 0 && top >= 0
}
data class SafeScreen(
    val packageName: String,
    val version: String,
    val targets: Map<Target, Box> = emptyMap(),
    val outgoingLocations: Int = 0,
    val sensitive: Boolean = false,
    val recognized: Boolean = true,
)
data class GuideState(
    val stage: Stage = Stage.IDLE,
    val instruction: String = "¿Qué te gustaría aprender hoy?",
    val target: Box? = null,
    val notice: String? = null,
    val practice: Boolean = false,
    val assistedPractice: Boolean = false,
    val completed: Boolean = false,
)
data class GuideEvent(val type: String, val stepId: String? = null)

/** Local, deterministic guide. No Android actions, clicks, gestures or network access. */
class GuidanceEngine(private val event: (GuideEvent) -> Unit = {}) {
    companion object {
        const val FLOW_ID = "whatsapp-location"
        const val PROFILE_VERSION = "whatsapp-location-es@1"
        val SUPPORTED_PREFIXES = listOf("2.26.")
    }
    var state = GuideState()
        private set
    private var baselineLocations = 0
    private var baselineCaptured = false
    private var lastScreen: SafeScreen? = null

    fun start(practice: Boolean = false): GuideState {
        baselineCaptured = false
        baselineLocations = 0
        lastScreen = null
        state = GuideState(stage = Stage.OPEN_CHAT, practice = practice,
            instruction = if (practice) "Probá compartir tu ubicación de nuevo. Esta vez no mostraré pistas. La confirmación de privacidad sigue activa."
                else "Abrí WhatsApp y elegí la conversación con la persona a la que querés compartir tu ubicación.")
        if (!practice) event(GuideEvent("flow_started"))
        event(GuideEvent("step_shown", "open_chat"))
        return state
    }

    fun observe(screen: SafeScreen): GuideState {
        if (state.stage in listOf(Stage.IDLE, Stage.COMPLETE, Stage.CANCELLED)) return state
        lastScreen = screen
        val reason = when {
            screen.packageName != "com.whatsapp" -> "Abrí WhatsApp para continuar. En otras apps no leo la pantalla."
            screen.sensitive -> "Esta pantalla contiene un campo sensible o un permiso del sistema. Volvé a una pantalla de WhatsApp sin datos sensibles."
            SUPPORTED_PREFIXES.none { screen.version.startsWith(it) } -> "Esta versión de WhatsApp (${screen.version.ifBlank { "desconocida" }}) no tiene un perfil compatible. Pedí ayuda a alguien de confianza."
            !screen.recognized -> "No reconozco los controles de esta pantalla. Volvé a la conversación o pedí ayuda."
            else -> null
        }
        if (reason != null) {
            state = state.copy(target = null, notice = reason)
            return state
        }
        state = state.copy(notice = null, target = null)
        when (state.stage) {
            Stage.OPEN_CHAT -> if (screen.targets[Target.ATTACH] != null) {
                baselineLocations = screen.outgoingLocations
                baselineCaptured = true
                advance(Stage.ATTACH, "Tocá Adjuntar, el botón con forma de clip al lado de donde escribís.", "open_chat")
            }
            else -> Unit
        }
        when (state.stage) {
            Stage.ATTACH -> {
                if (screen.targets[Target.LOCATION] != null) advance(Stage.LOCATION, "Tocá Ubicación en las opciones para adjuntar.", "attach")
                else select(screen, Target.ATTACH)
            }
            Stage.LOCATION -> {
                if (screen.targets[Target.SEND_LOCATION] != null) advance(Stage.CONFIRM,
                    "Vas a compartir tu ubicación actual con esta conversación. Revisá la persona destinataria. ¿Querés continuar?", "location")
                else select(screen, Target.LOCATION)
            }
            Stage.SHARE -> {
                if (screen.targets[Target.SEND_LOCATION] != null) select(screen, Target.SEND_LOCATION)
                else if (screen.targets[Target.ATTACH] != null) {
                    advance(Stage.VERIFY, "Estoy comprobando si apareció un nuevo mensaje saliente de ubicación con estado de envío.", "send_location")
                    checkSuccess(screen)
                } else state = state.copy(notice = "Esperá a volver a la conversación. Todavía no puedo comprobar el envío.")
            }
            Stage.VERIFY -> checkSuccess(screen)
            else -> Unit
        }
        return state
    }

    fun confirm(): GuideState {
        if (state.stage != Stage.CONFIRM || state.notice != null) return state
        val current = lastScreen ?: return state
        if (current.sensitive || current.targets[Target.SEND_LOCATION] == null) return state
        advance(Stage.SHARE, "Tocá Enviar mi ubicación actual. WhatsApp hará el envío cuando vos toques el botón.", null)
        select(current, Target.SEND_LOCATION)
        return state
    }

    fun repeat(): GuideState {
        if (state.stage !in listOf(Stage.IDLE, Stage.COMPLETE, Stage.CANCELLED)) {
            event(GuideEvent("step_repeated", state.stage.name.lowercase()))
            if (state.practice) {
                state = state.copy(assistedPractice = true, instruction = assistedInstruction())
                lastScreen?.let { observe(it) }
            }
        }
        return state
    }
    fun lost(): GuideState {
        event(GuideEvent("user_lost", state.stage.name.lowercase()))
        state = state.copy(assistedPractice = state.practice || state.assistedPractice,
            instruction = if (state.practice) assistedInstruction() else state.instruction,
            notice = "Está bien ir de a poco. Volvé a la última pantalla que reconocés. Podés pedir ayuda a tu familia.")
        return state
    }
    fun cancel(): GuideState {
        if (state.stage !in listOf(Stage.IDLE, Stage.COMPLETE, Stage.CANCELLED)) event(GuideEvent("cancelled"))
        state = state.copy(stage = Stage.CANCELLED, target = null, notice = null, instruction = "Guía terminada. Dejé de observar la pantalla.")
        return state
    }
    private fun select(screen: SafeScreen, target: Target) {
        val box = screen.targets[target]?.takeIf { it.valid }
        state = state.copy(target = if (state.practice && !state.assistedPractice) null else box,
            notice = if (box == null) "No encuentro el control esperado. No voy a marcar una posición aproximada." else null)
    }
    private fun checkSuccess(screen: SafeScreen) {
        if (baselineCaptured && screen.targets[Target.ATTACH] != null && screen.outgoingLocations > baselineLocations) {
            val autonomous = state.practice && !state.assistedPractice
            event(GuideEvent(if (autonomous) "autonomy_completed" else "flow_completed"))
            state = state.copy(stage = Stage.COMPLETE, target = null, notice = null, completed = true,
                instruction = if (autonomous) "Lo hiciste sin pistas. Pude comprobar un nuevo mensaje de ubicación enviado."
                    else "Pude comprobar un nuevo mensaje de ubicación enviado. Ahora podés practicar sin pistas.")
        } else state = state.copy(target = null, notice = "Todavía no puedo verificar el envío. Busco un nuevo mensaje saliente de ubicación con estado de envío. No lo daré por completado sin esa evidencia.")
    }
    private fun advance(next: Stage, text: String, completedStep: String?) {
        completedStep?.let { event(GuideEvent("step_completed", it)) }
        state = state.copy(stage = next, instruction = if (state.practice && !state.assistedPractice && next !in listOf(Stage.CONFIRM, Stage.VERIFY))
            "Seguí por tu cuenta. Estoy comprobando el resultado sin mostrarte pistas." else text, target = null)
        event(GuideEvent("step_shown", next.name.lowercase()))
    }
    private fun assistedInstruction(): String = when (state.stage) {
        Stage.OPEN_CHAT -> "Abrí WhatsApp y elegí la conversación con la persona a la que querés compartir tu ubicación."
        Stage.ATTACH -> "Tocá Adjuntar, el botón con forma de clip al lado de donde escribís."
        Stage.LOCATION -> "Tocá Ubicación en las opciones para adjuntar."
        Stage.SHARE -> "Tocá Enviar mi ubicación actual. WhatsApp hará el envío cuando vos toques el botón."
        else -> state.instruction
    }
}
