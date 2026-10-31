package org.nieto.assistant.screen

import org.junit.Assert.*
import org.junit.Test
import org.nieto.assistant.flow.Box

class ScreenPolicyTest {
    private val control=ScreenControl("c1","Buscar","button",Box(10,20,100,80))
    private val screen=ScreenContext("screen-1","com.example",1080,2400,listOf(control),1000,"fingerprint")
    @Test fun `only current unique live control can be highlighted`() {
        val step=ScreenStep("screen-1","c1","Tocá Buscar")
        assertEquals(control.bounds,ScreenPolicy.target(step,screen,2000))
        assertNull(ScreenPolicy.target(step.copy(screenId="old-screen"),screen,2000))
        assertNull(ScreenPolicy.target(step.copy(controlId="invented"),screen,2000))
        assertNull(ScreenPolicy.target(step,screen.copy(controls=listOf(control,control)),2000))
        assertNull(ScreenPolicy.target(step,screen.copy(blocked=true),2000))
        assertNull(ScreenPolicy.target(step,screen,121001))
    }
    @Test fun `bounds outside current display are rejected`() {
        val bad=control.copy(bounds=Box(0,0,1081,100))
        assertNull(ScreenPolicy.target(ScreenStep("screen-1","c1","Tocá"),screen.copy(controls=listOf(bad)),2000))
    }
    @Test fun `sensitive screens and labels never enter control payload`() {
        listOf("com.bank.mobile","com.android.permissioncontroller","com.wallet.app","com.android.inputmethod.latin","com.mercadopago.wallet").forEach { assertTrue(ScreenPolicy.blockedPackage(it)) }
        assertFalse(ScreenPolicy.blockedPackage("com.whatsapp"))
        listOf("Contraseña","Código de verificación","Mi token","Ingresar PIN","Transferir").forEach { assertTrue(it,ScreenPolicy.sensitiveLabel(it));assertNull(ScreenPolicy.safeLabel(it)) }
        assertNull(ScreenPolicy.safeLabel("Código 123456"))
        assertNull(ScreenPolicy.safeLabel("persona@example.org"))
        assertEquals("Buscar",ScreenPolicy.safeLabel("Buscar"))
    }
}
