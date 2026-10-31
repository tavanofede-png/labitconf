package org.nieto.assistant.screen

import org.junit.Assert.*
import org.junit.Test
import org.nieto.assistant.flow.Box

class ScreenTreeCollectorTest {
    private class Node(
        private val label: String = "", override val bounds: Box = Box(0, 0, 500, 900),
        override val editable: Boolean = false, override val password: Boolean = false,
        override val dataSensitive: Boolean = false, override val clickable: Boolean = false,
        override val scrollable: Boolean = false, override val resourceName: String = "", override val heading: Boolean = false,
        private val protectedGetters: Boolean = false, private val children: List<Node> = emptyList()
    ) : ScreenTreeNode {
        override val visible = true
        override val enabled = true
        override val longClickable = false
        override val checkable = false
        override val className = if (editable) "android.widget.EditText" else "android.view.View"
        override val childCount get() = children.size
        var reads = 0
        override fun text(): CharSequence { check(!protectedGetters) { "Protected text getter invoked" }; ++reads; return label }
        override fun description(): CharSequence? { check(!protectedGetters) { "Protected description getter invoked" }; ++reads; return null }
        override fun child(index: Int) = children.getOrNull(index)
    }
    private fun button(label: String, top: Int) = Node(label, Box(20, top, 300, top + 50), clickable = true)

    @Test fun `ARCA password and OTP are reduced before text reads while safe sign-in and help stay usable`() {
        val password = Node("never read", Box(20, 100, 300, 150), editable = true, password = true, protectedGetters = true)
        val otp = Node("never read", Box(20, 170, 300, 220), dataSensitive = true, protectedGetters = true)
        val heading = Node("Contraseña y verificación", Box(20, 20, 300, 80), heading = true)
        val tree = ScreenTreeCollector.collect(Node(children = listOf(heading, password, otp, button("Ingresar con clave fiscal", 300), button("Ayuda", 400))), 500, 900, ScreenNavigation())
        assertEquals(listOf("Contraseña y verificación", "Campo de texto", "Ingresar con clave fiscal", "Ayuda"), tree.controls.map { it.label })
        assertEquals("heading", tree.controls.first().role)
        assertTrue(tree.controls.single { it.role == "input" }.redacted)
        assertEquals(0, password.reads + otp.reads)
        assertFalse(tree.imageAllowed)
        assertTrue(tree.redactions.contains(password.bounds))
        assertFalse(tree.structure.contains("never read"))
        val screen = ScreenContext("current", "ar.gob.afip.mobile.android.contribuyentes.mi_afip", 500, 900, tree.controls, 10, tree.structure)
        assertEquals(tree.controls[2].bounds, ScreenPolicy.target(ScreenStep("current", tree.controls[2].id, "Tocá Ingresar"), screen, 20))
    }

    @Test fun `any app including Android settings retains safe observed controls beside private editable values`() {
        val field = Node("private value", Box(20, 100, 300, 150), editable = true, protectedGetters = true)
        for (packageName in listOf("com.android.settings", "com.android.systemui", "com.bank.example", "ar.gob.argentinagobar", "com.unknown.app")) {
            assertFalse(ScreenPolicy.blockedPackage(packageName))
            val tree = ScreenTreeCollector.collect(Node(children = listOf(field, button("Siguiente", 300))), 500, 900, ScreenNavigation())
            assertEquals(listOf("input", "button"), tree.controls.map { it.role })
            assertEquals("Siguiente", tree.controls.last().label)
            assertFalse(tree.imageAllowed)
        }
        assertEquals(0, field.reads)
    }

    @Test fun `private contact rows and OTP selectors never expose descriptions or descendants`() {
        val contact = Node("private person", Box(20, 100, 300, 150), clickable = true,
            resourceName = "com.any.app:id/contact_row", protectedGetters = true,
            children = listOf(Node("private child", protectedGetters = true)))
        val otp = Node("private code", Box(20, 180, 300, 230), clickable = true,
            resourceName = "com.any.app:id/otp_value", protectedGetters = true)
        val tree = ScreenTreeCollector.collect(Node(children = listOf(contact, otp, button("Atrás", 300))), 500, 900, ScreenNavigation())
        assertEquals(listOf("Atrás"), tree.controls.map { it.label })
        assertEquals(0, contact.reads + otp.reads)
        assertFalse(tree.imageAllowed)
    }

    @Test fun `arbitrary launcher icon gets its real parent bounds without querying installed packages`() {
        val iconBounds = Box(50, 100, 150, 200)
        val icon = Node(bounds = iconBounds, clickable = true, children = listOf(Node("Mi Argentina", Box(55, 170, 145, 195))))
        val root = Node(children = listOf(icon))
        val confirmed = ScreenTreeCollector.collect(root, 500, 900, ScreenNavigation(isLauncher = true))
        assertEquals("Mi Argentina", confirmed.controls.single { it.role == "button" }.label)
        assertEquals(iconBounds, confirmed.controls.single { it.role == "button" }.bounds)
        assertTrue(ScreenNavigation().installedApps.isEmpty())
        assertFalse(ScreenTreeCollector.collect(root, 500, 900, ScreenNavigation(isLauncher = false)).controls.any { it.role == "button" })
    }

    @Test fun `unreadable protected bounds disable pixels without suppressing the rest of the tree`() {
        val secret = Node(editable = true, password = true, bounds = Box(0, 0, 0, 0), protectedGetters = true)
        val tree = ScreenTreeCollector.collect(Node(children = listOf(secret, button("Continuar", 300))), 500, 900, ScreenNavigation())
        assertFalse(tree.imageAllowed)
        assertEquals(listOf("Continuar"), tree.controls.map { it.label })
        assertEquals(0, secret.reads)
    }

    @Test fun `form labels and headings stay visible while Nombre and Apellido input values never get read`() {
        val name = Node("private name", Box(20, 130, 300, 170), editable = true, protectedGetters = true)
        val surname = Node("private surname", Box(20, 230, 300, 270), editable = true, protectedGetters = true)
        val root = Node(children = listOf(Node("Datos personales", Box(20, 10, 300, 50), heading = true),
            Node("Nombre", Box(20, 90, 300, 120)), name, Node("Apellido", Box(20, 190, 300, 220)), surname,
            Node("Período 2026", Box(20, 300, 300, 340)), button("Siguiente", 400)))
        val tree = ScreenTreeCollector.collect(root, 500, 900, ScreenNavigation())
        assertEquals(listOf("Datos personales", "Nombre", "Campo de texto", "Apellido", "Campo de texto", "Período 2026", "Siguiente"), tree.controls.map { it.label })
        assertEquals(listOf("heading", "label", "input", "label", "input", "label", "button"), tree.controls.map { it.role })
        assertEquals(0, name.reads + surname.reads)
        assertTrue(tree.controls.filter { it.role == "input" }.all { it.redacted })
        assertFalse(tree.imageAllowed)
    }

    @Test fun `a container with a protected child never reads an aggregated private description`() {
        val input = Node("private", Box(20, 100, 300, 150), editable = true, protectedGetters = true)
        val container = Node(clickable = true, protectedGetters = true, children = listOf(input))
        val tree = ScreenTreeCollector.collect(Node(children = listOf(container, button("Ayuda", 400))), 500, 900, ScreenNavigation())
        assertEquals(listOf("Campo de texto", "Ayuda"), tree.controls.map { it.label })
        assertEquals(0, container.reads + input.reads)
    }

    @Test fun `long prose and secret static values are masked while short form labels remain`() {
        val root = Node(children = listOf(Node("A".repeat(121), Box(20, 20, 300, 70)),
            Node("PIN: 4321", Box(20, 90, 300, 140)), Node("Período 2026", Box(20, 160, 300, 210)), button("Continuar", 300)))
        val tree = ScreenTreeCollector.collect(root, 500, 900, ScreenNavigation())
        assertEquals(listOf("Período 2026", "Continuar"), tree.controls.map { it.label })
        assertTrue(tree.redactions.contains(Box(20, 20, 300, 70)))
        assertTrue(tree.redactions.contains(Box(20, 90, 300, 140)))
    }

    @Test fun `many static captions cannot crowd out a real actionable control`() {
        val labels = (1..80).map { Node("Etiqueta $it", Box(20, it, 300, it + 1)) }
        val tree = ScreenTreeCollector.collect(Node(children = labels + button("Siguiente", 400)), 500, 900, ScreenNavigation())
        assertTrue(tree.controls.any { it.label == "Siguiente" && it.role == "button" })
        assertEquals(tree.controls.size, tree.controls.map { it.id }.toSet().size)
        assertTrue(tree.controls.size <= 60)
    }
}
