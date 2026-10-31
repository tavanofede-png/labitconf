package org.nieto.assistant.screen

import org.junit.Assert.*
import org.junit.Test

class ScreenNavigationPolicyTest {
    @Test fun `system modes distinguish buttons from gestures without guessing missing or OEM values`() {
        assertEquals("button", ScreenNavigationPolicy.method(0, 0))
        assertEquals("button", ScreenNavigationPolicy.method(1, 1))
        assertEquals("gesture", ScreenNavigationPolicy.method(2, 2))
        assertEquals("gesture", ScreenNavigationPolicy.method(null, 2))
        assertEquals("button", ScreenNavigationPolicy.method(0, null))
        assertEquals("unknown", ScreenNavigationPolicy.method(null, null))
        assertEquals("unknown", ScreenNavigationPolicy.method(0, 2))
        assertEquals("unknown", ScreenNavigationPolicy.method(3, 2))
        assertEquals("unknown", ScreenNavigationPolicy.method(null, -1))
    }

    @Test fun `optional navigation metadata is generic and never substitutes for observed icons`() {
        assertEquals("Otra app", ScreenNavigationPolicy.installedApp("com.other.app", "Otra app")?.label)
        assertEquals("Ajustes", ScreenNavigationPolicy.installedApp("com.android.settings", "Ajustes")?.label)
        assertNull(ScreenNavigationPolicy.installedApp("bad package/command", "Otra app"))
        assertNull(ScreenNavigationPolicy.installedApp("com.google.android.gm", "person@example.org"))
        assertEquals("WhatsApp", ScreenNavigationPolicy.installedApp("com.whatsapp", "WhatsApp")?.label)
        assertEquals(80, ScreenNavigationPolicy.installedApp("com.android.chrome", "A".repeat(100))?.label?.length)
    }

    @Test fun `any app icon uses the observed default launcher without an installed apps inventory`() {
        val launcher = ScreenNavigation(isLauncher = true)
        assertEquals("Gmail", ScreenNavigationPolicy.launcherAppLabel("Gmail", launcher))
        assertEquals("Mi Argentina", ScreenNavigationPolicy.launcherAppLabel("Mi Argentina", launcher))
        assertEquals("Otra app", ScreenNavigationPolicy.launcherAppLabel("Otra app", launcher))
        assertNull(ScreenNavigationPolicy.launcherAppLabel("Gmail", launcher.copy(isLauncher = false)))
        assertNull(ScreenNavigationPolicy.launcherAppLabel("person@example.org", launcher))
        assertTrue(launcher.installedApps.isEmpty())
    }
}
