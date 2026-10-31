package org.nieto.assistant.screen

import org.nieto.assistant.flow.Box

/** The adapter exposes node metadata separately from user-bearing text getters. */
interface ScreenTreeNode : AutoCloseable {
    val visible: Boolean
    val enabled: Boolean
    val editable: Boolean
    val password: Boolean
    val dataSensitive: Boolean
    val clickable: Boolean
    val longClickable: Boolean
    val checkable: Boolean
    val scrollable: Boolean
    val heading: Boolean get() = false
    val resourceName: String
    val className: String
    val bounds: Box
    val childCount: Int
    fun text(): CharSequence?
    fun description(): CharSequence?
    fun child(index: Int): ScreenTreeNode?
    override fun close() {}
}

data class ReducedScreenTree(val controls: List<ScreenControl>, val redactions: List<Box>, val imageAllowed: Boolean, val structure: String)

/** App-independent reduction. Protected fields are classified before any text getter is called. */
object ScreenTreeCollector {
    fun collect(root: ScreenTreeNode, width: Int, height: Int, navigation: ScreenNavigation): ReducedScreenTree {
        val controls = mutableListOf<ScreenControl>()
        val redactions = mutableListOf<Box>()
        val structure = StringBuilder()
        var imageAllowed = true
        var visited = 0
        fun bounds(node: ScreenTreeNode): Box = node.bounds.let {
            Box(it.left.coerceIn(0, width), it.top.coerceIn(0, height), it.right.coerceIn(0, width), it.bottom.coerceIn(0, height))
        }
        fun privateNode(node: ScreenTreeNode) = node.editable || node.password || node.dataSensitive || ScreenPolicy.privateNodeResource(node.resourceName)
        fun redact(node: ScreenTreeNode) {
            val box = bounds(node)
            if (box.valid) redactions.add(box) else imageAllowed = false
        }
        fun add(node: ScreenTreeNode, label: String, role: String, redacted: Boolean = false) {
            val box = bounds(node)
            val caption = role in listOf("label", "heading")
            if ((!node.enabled && !caption) || !box.valid || controls.any { it.bounds == box && it.label == label && it.role == role }) return
            if (caption && controls.count { it.role in listOf("label", "heading") } >= 20) return
            if (controls.size >= 60) {
                val lastCaption = controls.indexOfLast { it.role in listOf("label", "heading") }
                if (caption || lastCaption == -1) return
                controls.removeAt(lastCaption)
            }
            controls.add(ScreenControl("", label, role, box, redacted))
        }
        data class ObservedLabel(val label: String?, val blank: Boolean)
        fun boundedLabel(node: ScreenTreeNode): ObservedLabel {
            val description = node.description()
            val raw = if (description.isNullOrBlank()) node.text() else description
            return ObservedLabel(raw?.takeIf { it.length <= 120 }?.toString()?.let(ScreenPolicy::safeLabel), raw.isNullOrBlank())
        }
        fun hasProtectedChild(node: ScreenTreeNode, depth: Int = 0): Boolean {
            if (depth > 2) return false
            for (index in 0 until node.childCount.coerceAtMost(12)) node.child(index)?.use { child ->
                if (privateNode(child) || hasProtectedChild(child, depth + 1)) return true
            }
            return false
        }
        fun launcherLabel(node: ScreenTreeNode, depth: Int = 0): String? {
            if (!navigation.isLauncher || depth > 2 || !node.visible || privateNode(node)) return null
            if (depth > 0 && (node.clickable || node.longClickable || node.checkable || node.scrollable)) return null
            if (hasProtectedChild(node)) return null
            val own = boundedLabel(node)
            own.label?.let { return it }
            if (!own.blank) return null
            if (node.childCount > 12) return null
            var found: String? = null
            for (index in 0 until node.childCount) node.child(index)?.use { child ->
                val label = launcherLabel(child, depth + 1)
                if (label != null) {
                    if (found != null && found != label) return null
                    found = label
                }
            }
            return found
        }
        fun visit(node: ScreenTreeNode, depth: Int) {
            if (++visited > 1500 || depth > 30) { imageAllowed = false; return }
            if (!node.visible) return
            structure.append(node.className).append(':').append(bounds(node)).append(';')
            if (privateNode(node)) {
                // Never read text, hints, descriptions, or descendants of protected nodes.
                redact(node)
                imageAllowed = false
                if (node.editable || node.password) add(node, "Campo de texto", "input", redacted = true)
                return
            }
            if (node.scrollable) {
                // Scroll containers may aggregate conversations or arbitrary private prose.
                add(node, "Área desplazable", "scroll")
            } else if (node.clickable || node.longClickable || node.checkable) {
                val own = if (hasProtectedChild(node)) ObservedLabel(null, false) else boundedLabel(node)
                val label = own.label ?: if (own.blank && node.clickable) launcherLabel(node) else null
                if (label != null) add(node, label, if (node.checkable) "checkbox" else "button")
                else { redact(node); imageAllowed = false }
            } else if (node.childCount == 0 || node.heading) {
                // Short non-protected form labels help explain a screen without exposing editable values.
                val label = boundedLabel(node).label
                if (label != null) add(node, label, if (node.heading) "heading" else "label")
                else redact(node)
            }
            for (index in 0 until node.childCount) node.child(index)?.use { child -> visit(child, depth + 1) }
        }
        visit(root, 0)
        return ReducedScreenTree(controls.mapIndexed { index, control -> control.copy(id = "c${index + 1}") }, redactions, imageAllowed, structure.toString())
    }
}
