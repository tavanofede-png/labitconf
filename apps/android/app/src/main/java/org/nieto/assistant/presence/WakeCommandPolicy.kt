package org.nieto.assistant.presence

import java.text.Normalizer
import java.util.Locale

enum class WakeRoute { CONVERSATION, SCREEN_ASSISTANCE, NEEDS_ACCESSIBILITY, STOP_SCREEN_ASSISTANCE }

/** Permission-driven routing, independent of which app or task the person asks about. */
object WakeCommandPolicy {
    fun route(text: String, inTino: Boolean, screenAllowed: Boolean, accessibilityReady: Boolean): WakeRoute {
        val clean=Normalizer.normalize(text,Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"").lowercase(Locale.ROOT).trim().trimEnd('.','!','?')
        if(Regex("^(cancelar|cancela|para(?: tino)?|terminar (?:la )?ayuda|deja de (?:ver|mirar|observar) (?:la )?pantalla)$").matches(clean))return WakeRoute.STOP_SCREEN_ASSISTANCE
        if(inTino || !screenAllowed)return WakeRoute.CONVERSATION
        return if(accessibilityReady)WakeRoute.SCREEN_ASSISTANCE else WakeRoute.NEEDS_ACCESSIBILITY
    }
}
