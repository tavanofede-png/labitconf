package org.nieto.assistant.screen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.nieto.assistant.MainActivity
import org.nieto.assistant.NietoApp
import org.nieto.assistant.flow.*
import org.nieto.assistant.flow.Target

/** Reads only whitelisted control labels locally. There is deliberately no performAction/gesture path. */
class NietoAccessibilityService : AccessibilityService() {
    private val app get() = application as NietoApp
    private val handler = Handler(Looper.getMainLooper())
    private val inspect = Runnable { collectScreen() }
    private var overlay: GuideOverlay? = null
    private var collector: Job? = null
    private var lastPresentation: List<Any>? = null

    override fun onServiceConnected() {
        app.screenConnection(true)
        overlay = GuideOverlay(this, app)
        collector = app.scope.launch {
            app.state.collectLatest { state ->
                val enabled = state.overlayEnabled && app.settings.screenConsent
                val presentation = listOf(state.guide, enabled, state.screenPackage, state.status)
                if (lastPresentation != presentation) {
                    lastPresentation = presentation
                    overlay?.render(state.guide, enabled)
                }
            }
        }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (windows.any { it.id == event.windowId && it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }) return
        app.screenPackage(event.packageName?.toString().orEmpty())
        if (!app.settings.screenConsent || app.state.value.guide.stage in listOf(Stage.IDLE, Stage.COMPLETE, Stage.CANCELLED)) return
        if (event.packageName?.toString() == packageName) return
        handler.removeCallbacks(inspect)
        handler.postDelayed(inspect, 220)
    }
    private fun collectScreen() {
        if (!app.settings.screenConsent) return
        val root = rootInActiveWindow ?: run {
            app.observe(SafeScreen("", "", recognized = false)); return
        }
        val activePackage = root.packageName?.toString().orEmpty()
        if (activePackage != "com.whatsapp") {
            app.observe(SafeScreen(activePackage, "", sensitive = activePackage == "com.android.permissioncontroller"))
            return
        }
        val window = windows.firstOrNull { it.id == root.windowId }
        if (window != null && window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
            app.observe(SafeScreen(activePackage, "", sensitive = true)); return
        }
        val version = runCatching { packageManager.getPackageInfo("com.whatsapp", 0).versionName.orEmpty() }.getOrDefault("")
        app.observe(WhitelistedCollector.collect(root, version))
    }
    override fun onInterrupt() { app.cancel(); overlay?.clear() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        collector?.cancel()
        overlay?.clear()
        app.screenConnection(false)
        super.onDestroy()
    }
}

private object WhitelistedCollector {
    private fun normalized(raw: CharSequence?) = ControlPolicy.normalize(raw)
    private fun id(node: AccessibilityNodeInfo) = node.viewIdResourceName?.substringAfter(":id/").orEmpty()
    private fun bounds(node: AccessibilityNodeInfo): Box {
        val rect = Rect(); node.getBoundsInScreen(rect)
        return Box(rect.left, rect.top, rect.right, rect.bottom)
    }
    fun collect(root: AccessibilityNodeInfo, version: String): SafeScreen {
        val candidates = mutableMapOf<Target, MutableSet<Box>>()
        var sensitive = false
        var count = 0
        var visited = 0
        var chatInputPresent = false
        val outgoingCards = mutableSetOf<Box>()
        fun locationWithSendStatus(node: AccessibilityNodeInfo): Boolean {
            var location = false
            var sent = false
            var scanned = 0
            fun scan(item: AccessibilityNodeInfo, depth: Int) {
                if (depth > 8 || ++scanned > 120 || !item.isVisibleToUser || item.isPassword) return
                val resource = id(item)
                if (resource in setOf("location_thumb", "location_thumbnail", "location_image", "location_map")) location = true
                if (resource in setOf("status", "message_status", "msg_status", "delivery_status")) {
                    val description = normalized(item.contentDescription)
                    if (description in setOf("enviado", "enviada", "entregado", "entregada", "leido", "leida", "enviado a todos", "leido por todos")) sent = true
                }
                for (index in 0 until item.childCount) item.getChild(index)?.let { scan(it, depth + 1) }
            }
            scan(node, 0)
            return location && sent
        }
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (++visited > 1800 || depth > 35 || !node.isVisibleToUser) return
            if (node.isPassword) { sensitive = true; return }
            val resource = id(node)
            val editable = node.isEditable || node.className?.toString()?.contains("EditText") == true
            if (editable) {
                if (resource in setOf("entry", "conversation_entry", "message_entry")) chatInputPresent = true
                return // Never read editable text, hints, names, OTPs or keyboard contents.
            }
            val target = ControlPolicy.labelTarget(node.contentDescription, node.text)
            if (target != null) {
                val controlIds = mutableListOf(resource)
                var idAncestor = node.parent
                var idLevels = 0
                while (idAncestor != null && idLevels++ < 3) { controlIds.add(id(idAncestor)); idAncestor = idAncestor.parent }
                // A semantic label must belong to an actual clickable control or its clickable parent.
                var control: AccessibilityNodeInfo? = node
                var levels = 0
                while (control != null && !control.isClickable && levels++ < 3) control = control.parent
                if (control?.isClickable == true && control.isEnabled && ControlPolicy.accepts(target, controlIds)) {
                    val box = bounds(control)
                    if (box.valid) candidates.getOrPut(target) { mutableSetOf() }.add(box)
                }
            }
            if (resource in setOf("location_thumb", "location_thumbnail", "location_image", "location_map")) {
                var ancestor: AccessibilityNodeInfo? = node.parent
                var levels = 0
                while (ancestor != null && levels++ < 4) {
                    if (id(ancestor) in setOf("main_layout", "message_container", "message_content", "message_bubble", "bubble") &&
                        locationWithSendStatus(ancestor)) { outgoingCards.add(bounds(node)); break }
                    ancestor = ancestor.parent
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        count = outgoingCards.size
        val targets = candidates.mapNotNull { (key, values) ->
            // Ambiguous duplicate controls receive no marker.
            if (values.size == 1 && (key != Target.ATTACH || chatInputPresent)) key to values.single() else null
        }.toMap()
        return SafeScreen("com.whatsapp", version, targets, count, sensitive,
            recognized = targets.isNotEmpty() || count > 0)
    }
}

private class GuideOverlay(private val service: NietoAccessibilityService, private val app: NietoApp) {
    private val manager = service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
    private val views = mutableListOf<View>()
    private var expanded = false
    private var lastStage = Stage.IDLE
    private val green = Color.rgb(38, 76, 59)
    private val cream = Color.rgb(250, 248, 242)
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    fun clear() { views.forEach { runCatching { manager.removeView(it) } }; views.clear() }
    fun render(guide: GuideState, enabled: Boolean) {
        clear()
        if (!enabled) return
        if (lastStage in listOf(Stage.IDLE, Stage.CANCELLED) && guide.stage !in listOf(Stage.IDLE, Stage.CANCELLED)) expanded = true
        lastStage = guide.stage
        guide.target?.takeIf { app.state.value.screenPackage == "com.whatsapp" }?.let { target ->
            add(HighlightView(service, target), WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT))
        }
        val bubble = Button(service).apply {
            text = "N"; textSize = 24f; setTextColor(cream); backgroundTintList = android.content.res.ColorStateList.valueOf(green)
            contentDescription = "Abrir ayuda de NIETO"
            setOnClickListener { expanded = !expanded; render(app.state.value.guide, true) }
        }
        add(bubble, WindowManager.LayoutParams(dp(60), dp(60), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END; x = dp(8); y = dp(80)
        })
        if (!expanded) return
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); setBackgroundColor(cream)
            elevation = dp(6).toFloat()
        }
        panel.addView(TextView(service).apply { text = "NIETO · un paso a la vez"; textSize = 16f; setTextColor(green); typeface = Typeface.DEFAULT_BOLD })
        val message = guide.notice ?: guide.instruction
        panel.addView(TextView(service).apply { text = message; textSize = 20f; setTextColor(Color.rgb(35, 41, 37)); setPadding(0, dp(8), 0, dp(6)) })
        fun button(title: String, action: () -> Unit) { panel.addView(Button(service).apply {
            text = title; textSize = 16f; minHeight = dp(48); isAllCaps = false; setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2)) }
        if (guide.stage == Stage.CONFIRM && guide.notice == null) button("Sí, quiero compartir mi ubicación") { app.confirmShare() }
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Repetir" to { app.repeat() }, "Me perdí" to { app.lost() }).forEach { (title, action) ->
            row.addView(Button(service).apply { text = title; isAllCaps = false; textSize = 16f; minHeight = dp(48); setOnClickListener { action() } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        panel.addView(row)
        if (guide.stage == Stage.COMPLETE) button("Practicar sin pistas") { app.startGuide(practice = true) }
        button("Pedir ayuda a mi familia") { app.requestHelp() }
        panel.addView(TextView(service).apply { text = app.state.value.status; textSize = 15f; setTextColor(green); setPadding(0, dp(6), 0, dp(6)) })
        button("Hablar con NIETO") {
            app.requestVoiceFromOverlay()
            service.startActivity(Intent(service, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        val controls = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Abrir NIETO" to {
            service.startActivity(Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }, "Cerrar guía" to { app.cancel(); expanded = false; render(app.state.value.guide, true) }).forEach { (title, action) ->
            controls.addView(Button(service).apply { text = title; isAllCaps = false; minHeight = dp(48); setOnClickListener { action() } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        panel.addView(controls)
        val scroll = ScrollView(service).apply { addView(panel) }
        val display = service.resources.displayMetrics
        val targetTop = guide.target?.top ?: display.heightPixels
        val panelAtBottom = targetTop < display.heightPixels / 2
        add(scroll, WindowManager.LayoutParams(display.widthPixels - dp(24),
            (display.heightPixels * 0.45).toInt(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = if (panelAtBottom) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(if (panelAtBottom) 28 else 30)
        })
    }
    private fun add(view: View, params: WindowManager.LayoutParams) {
        runCatching { manager.addView(view, params); views.add(view) }.onFailure { app.status("No pude mostrar la burbuja. Podés seguir desde NIETO.") }
    }
}

private class HighlightView(context: android.content.Context, private val box: Box) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(38, 120, 66); style = Paint.Style.STROKE; strokeWidth = 5 * resources.displayMetrics.density }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val margin = 4 * resources.displayMetrics.density
        canvas.drawRoundRect(box.left - margin, box.top - margin, box.right + margin, box.bottom + margin, 12f, 12f, stroke)
    }
}
