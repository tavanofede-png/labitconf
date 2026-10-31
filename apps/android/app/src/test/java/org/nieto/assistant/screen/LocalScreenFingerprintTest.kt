package org.nieto.assistant.screen

import org.junit.Assert.*
import org.junit.Test

class LocalScreenFingerprintTest {
    @Test fun `a changed article invalidates a snapshot even with identical buttons`() {
        val local = LocalScreenFingerprint()
        fun page(body: String, revision: Int = 0) = local.begin("com.example|7|1080|2400|$revision").apply {
            add("TextView"); add(body); add("c1:Continuar:button:10,20,100,80")
        }.finish()
        val first = page("Paso uno: revisá el destinatario")
        assertEquals(first, page("Paso uno: revisá el destinatario"))
        assertNotEquals(first, page("Paso dos: revisá el importe"))
        assertNotEquals(first, page("Paso uno: revisá el destinatario", revision = 1))
    }

    @Test fun `local hashes cannot be linked across process keys or expose private text`() {
        val text = "Contenido privado de una tarjeta"
        val first = LocalScreenFingerprint().begin("window").apply { add(text) }.finish()
        val second = LocalScreenFingerprint().begin("window").apply { add(text) }.finish()
        assertTrue(first.matches(Regex("[a-f0-9]{64}")))
        assertFalse(first.contains(text))
        assertNotEquals(first, second)
    }

    @Test fun `long static content and field boundaries both affect the revision`() {
        val local = LocalScreenFingerprint()
        val prefix = "a".repeat(5000)
        fun hash(vararg parts: String) = local.begin("screen").apply { parts.forEach(::add) }.finish()
        assertNotEquals(hash(prefix + "one"), hash(prefix + "two"))
        assertNotEquals(hash("ab", "c"), hash("a", "bc"))
        assertNotEquals(hash("a".repeat(2047) + "😀"), hash("a".repeat(2047) + "😁"))
    }
}
