package org.nieto.assistant.screen

import android.graphics.Rect
import android.os.Build
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import org.nieto.assistant.flow.Box
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

data class CollectedScreen(val context: ScreenContext, val redactions: List<Box>, val windowId: Int, val imageAllowed: Boolean = true)

object RemoteScreenCollector {
    fun collect(root: AccessibilityNodeInfo, width: Int, height: Int, now: Long): CollectedScreen {
        val pkg = root.packageName?.toString().orEmpty()
        val controls = mutableListOf<ScreenControl>()
        val redactions = mutableListOf<Box>()
        var blocked = ScreenPolicy.blockedPackage(pkg)
        var visited = 0
        var imageAllowed = true
        fun box(node: AccessibilityNodeInfo): Box {
            val rect = Rect(); node.getBoundsInScreen(rect)
            return Box(rect.left.coerceIn(0, width), rect.top.coerceIn(0, height), rect.right.coerceIn(0, width), rect.bottom.coerceIn(0, height))
        }
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (++visited > 1500 || depth > 30) { imageAllowed = false; return }
            if (!node.isVisibleToUser) return
            val editable = node.isEditable || node.className?.toString()?.contains("EditText") == true
            val variation = node.inputType and InputType.TYPE_MASK_VARIATION
            val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
            val secret = node.isPassword || (editable && ((inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) || (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD))) ||
                (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)
            if (secret) { blocked = true; return }
            if (editable) {
                // Do not inspect text, hints or contentDescription of editable controls.
                val bounds = box(node)
                if (bounds.valid) {
                    redactions.add(bounds)
                    if (node.isEnabled && controls.size < 60 && controls.none { it.role == "input" && it.bounds == bounds })
                        controls.add(ScreenControl("c${controls.size + 1}", "Campo de texto", "input", bounds))
                } else imageAllowed = false
                return
            }
            // Detect sensitive screen headings locally; their text is never included as general screen prose.
            if (ScreenPolicy.sensitiveLabel(node.text?.toString()?.take(120).orEmpty()) || ScreenPolicy.sensitiveLabel(node.contentDescription?.toString()?.take(120).orEmpty())) blocked = true
            val actionable = node.isClickable || node.isLongClickable || node.isCheckable || node.isScrollable
            // Inspect only short control labels; conversation paragraphs and arbitrary static text are excluded.
            if (actionable && controls.size < 60) {
                val raw = node.contentDescription?.toString()?.take(120).orEmpty().ifBlank { node.text?.toString()?.take(120).orEmpty() }
                if (ScreenPolicy.sensitiveLabel(raw)) blocked = true
                val label = ScreenPolicy.safeLabel(raw)
                val bounds = box(node)
                if (label != null && bounds.valid && node.isEnabled) {
                    val role = when { node.isCheckable -> "checkbox"; node.isScrollable -> "scroll"; else -> "button" }
                    if (controls.none { it.bounds == bounds && it.label == label }) controls.add(ScreenControl("c${controls.size + 1}", label, role, bounds))
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                try { visit(child, depth + 1) } finally { @Suppress("DEPRECATION") child.recycle() }
            }
        }
        if (!blocked) visit(root, 0)
        val content = "$pkg|${root.windowId}|$width|$height|$blocked|" + controls.joinToString { "${it.id}:${it.label}:${it.role}:${it.bounds}" } + redactions.joinToString()
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
        return CollectedScreen(ScreenContext(UUID.randomUUID().toString(), pkg, width, height, if (blocked) emptyList() else controls,
            now, fingerprint, blocked = blocked, notice = if (blocked) "Pantalla sensible: no se envió su contenido. Volvé a una pantalla sin datos privados."
            else if (Build.VERSION.SDK_INT < 30) "Android 8–10: asistencia con controles accesibles, sin captura."
            else if (!imageAllowed) "No pude revisar toda la pantalla para ocultar campos privados. Continúo sólo con controles."
            else ""), redactions, root.windowId, imageAllowed)
    }
}

fun ScreenContext.toWire(): JSONObject = JSONObject().put("id", id).put("packageName", packageName)
    .put("width", width).put("height", height).put("controls", JSONArray().apply {
        controls.forEach { control -> put(JSONObject().put("id", control.id).put("label", control.label).put("role", control.role)
            .put("bounds", JSONArray(listOf(control.bounds.left, control.bounds.top, control.bounds.right, control.bounds.bottom)))) }
    }).apply { image?.let { put("image", JSONObject().put("mimeType", "image/jpeg").put("data", it)) } }

interface ScreenCaptureBridge {
    fun capture(callback: (ScreenContext?) -> Unit)
}
