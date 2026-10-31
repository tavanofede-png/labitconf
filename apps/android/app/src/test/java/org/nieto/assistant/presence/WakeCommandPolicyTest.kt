package org.nieto.assistant.presence

import org.junit.Assert.*
import org.junit.Test

class WakeCommandPolicyTest {
    @Test fun backgroundHelpUsesTheVisualLoopForAnyAppWhenAuthorized() {
        for(text in listOf("Ayudame a crear un grupo de WhatsApp","Quiero enviar un correo","¿Cómo agrando la letra?"))
            assertEquals(WakeRoute.SCREEN_ASSISTANCE,WakeCommandPolicy.route(text,false,true,true))
    }
    @Test fun conversationDoesNotSilentlyGrantScreenAccess() {
        assertEquals(WakeRoute.CONVERSATION,WakeCommandPolicy.route("Ayudame",false,false,true))
        assertEquals(WakeRoute.CONVERSATION,WakeCommandPolicy.route("Ayudame",true,true,true))
        assertEquals(WakeRoute.NEEDS_ACCESSIBILITY,WakeCommandPolicy.route("Ayudame",false,true,false))
    }
    @Test fun anExplicitStopEndsScreenObservationAndNegationsDoNotTriggerIt() {
        for(text in listOf("Pará TINO","Dejá de mirar la pantalla","Cancelar"))
            assertEquals(WakeRoute.STOP_SCREEN_ASSISTANCE,WakeCommandPolicy.route(text,false,true,true))
        assertEquals(WakeRoute.SCREEN_ASSISTANCE,WakeCommandPolicy.route("No quiero cancelar la ayuda",false,true,true))
    }
}
