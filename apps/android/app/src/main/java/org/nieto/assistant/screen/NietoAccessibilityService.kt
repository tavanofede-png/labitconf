package org.nieto.assistant.screen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import java.io.ByteArrayOutputStream
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

/** Observes only during explicit assistance. No performAction or gesture execution path exists. */
class NietoAccessibilityService : AccessibilityService(), ScreenCaptureBridge {
    private val app get() = application as NietoApp
    private val handler = Handler(Looper.getMainLooper())
    private val inspect = Runnable { collectScreen() }
    private var overlay: AssistantOverlay? = null
    private var localOverlay: GuideOverlay? = null
    private var collector: Job? = null
    private var lastCollected: CollectedScreen? = null
    private var captureInProgress = false
    private var captureGeneration = 0L
    private var localObserving = false

    override fun onServiceConnected() {
        app.screenConnection(true)
        app.screenCapture = this
        overlay = AssistantOverlay(this, app)
        localOverlay = GuideOverlay(this, app)
        collector = app.scope.launch {
            app.state.collectLatest { state ->
                val observing = app.settings.screenConsent && state.guide.stage !in listOf(Stage.IDLE, Stage.COMPLETE, Stage.CANCELLED)
                if (observing != localObserving) { WhitelistedCollector.clearContext(); localObserving = observing }
                overlay?.render(state, !captureInProgress)
                localOverlay?.render(state.guide, !state.assistanceEnabled && state.overlayEnabled && app.settings.screenConsent && !state.nativeSurfaceVisible && state.guide.stage !in listOf(Stage.IDLE, Stage.CANCELLED))
                if (!state.assistanceEnabled || state.nativeSurfaceVisible) { ++captureGeneration; if (!state.assistanceEnabled) lastCollected = null }
            }
        }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (windows.any { it.id == event.windowId && it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }) return
        val localGuideActive = app.settings.screenConsent && app.state.value.guide.stage !in listOf(Stage.IDLE, Stage.COMPLETE, Stage.CANCELLED)
        if (event.packageName?.toString() == packageName) {
            if (localGuideActive) {
                val root = rootInActiveWindow
                val nativeWindow = root?.packageName?.toString() == packageName
                @Suppress("DEPRECATION") root?.recycle()
                if (nativeWindow) {
                    WhitelistedCollector.clearContext()
                    app.observe(SafeScreen(packageName, "", recognized = false))
                }
            }
            return
        }
        if (localGuideActive) {
            WhitelistedCollector.noteEvent(event.eventType)
            if (event.packageName?.toString() != "com.whatsapp") {
                WhitelistedCollector.clearContext()
                app.observe(SafeScreen(event.packageName?.toString().orEmpty(), "", recognized = false))
            }
        }
        if (!app.state.value.assistanceEnabled && (!app.settings.screenConsent || app.state.value.guide.stage in listOf(Stage.IDLE, Stage.COMPLETE, Stage.CANCELLED))) return
        app.invalidateScreenTarget()
        handler.removeCallbacks(inspect)
        handler.postDelayed(inspect, 550)
    }
    private fun collectScreen() {
        val root = rootInActiveWindow ?: run {
            app.screenUnavailable()
            if (localObserving) { WhitelistedCollector.clearContext(); app.observe(SafeScreen("", "", recognized = false)) }
            return
        }
        if (root.packageName?.toString() == packageName) { @Suppress("DEPRECATION") root.recycle(); return }
        try {
        val activePackage = root.packageName?.toString().orEmpty()
        app.screenPackage(activePackage)
        if (app.state.value.assistanceEnabled && app.settings.remoteScreenConsent) {
            val display = resources.displayMetrics
            val fresh = RemoteScreenCollector.collect(root, display.widthPixels.coerceAtMost(10000), display.heightPixels.coerceAtMost(10000), SystemClock.elapsedRealtime())
            val stable = lastCollected?.takeIf { it.context.fingerprint == fresh.context.fingerprint }
            lastCollected = if (stable != null) fresh.copy(context = fresh.context.copy(id = stable.context.id)) else fresh
            app.onScreenObserved(lastCollected!!.context)
        }
        if (!app.settings.screenConsent || app.state.value.guide.stage in listOf(Stage.IDLE, Stage.COMPLETE, Stage.CANCELLED)) return
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
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }
    override fun capture(callback: (ScreenContext?) -> Unit) {
        if (!app.state.value.assistanceEnabled || !app.settings.remoteScreenConsent || captureInProgress || app.state.value.nativeSurfaceVisible) { callback(null); return }
        collectScreen()
        val collected = lastCollected
        if (collected == null || collected.context.blocked) { callback(collected?.context); return }
        if (!app.settings.screenImagesConsent || Build.VERSION.SDK_INT < 30 || !collected.imageAllowed) { callback(collected.context); return }
        // A display screenshot includes other windows. Never upload IME suggestions, permission dialogs or split-screen apps.
        fun ownOverlay(win: android.view.accessibility.AccessibilityWindowInfo): Boolean {
            if (win.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return false
            val root = win.root ?: return false
            return try { root.packageName?.toString() == packageName } finally { @Suppress("DEPRECATION") root.recycle() }
        }
        val otherWindows = windows.filter { it.id != collected.windowId && !ownOverlay(it) }
        if (otherWindows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD || it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }) {
            callback(collected.context.copy(notice = "Hay un teclado u otra app visible. Continúo sólo con controles, sin captura.")); return
        }
        val windowMasks = otherWindows.mapNotNull { win -> Rect().also { win.getBoundsInScreen(it) }.takeIf { !it.isEmpty } }
        fun windowSignature() = windows.filter { it.id != collected.windowId && !ownOverlay(it) }
            .map { win -> val rect=Rect(); win.getBoundsInScreen(rect); "${win.id}:${win.type}:$rect" }.sorted()
        val capturedWindows = windowSignature()
        fun targetStillVisible(): Boolean {
            val root = rootInActiveWindow ?: return false
            return try { root.windowId == collected.windowId && root.packageName?.toString() == collected.context.packageName }
            finally { @Suppress("DEPRECATION") root.recycle() }
        }
        val generation = ++captureGeneration
        captureInProgress = true; overlay?.clear(); localOverlay?.clear()
        handler.postDelayed({
            if (generation != captureGeneration || !app.state.value.assistanceEnabled || app.state.value.nativeSurfaceVisible || !app.settings.remoteScreenConsent || !targetStillVisible()) {
                captureInProgress = false; callback(null); return@postDelayed
            }
            val result = object : TakeScreenshotCallback {
                override fun onFailure(errorCode: Int) {
                    captureInProgress = false; overlay?.render(app.state.value, true)
                    if (generation != captureGeneration) { callback(null); return }
                    collectScreen()
                    if (lastCollected?.context?.id != collected.context.id || lastCollected?.context?.blocked == true) { callback(null); return }
                    // Secure windows are never bypassed. No pixels or controls from this request are uploaded.
                    if (errorCode == 6) { app.screenUnavailable(); callback(null) }
                    else callback(collected.context.copy(notice = "Android no permitió la captura. Continúo sólo con controles accesibles."))
                }
                override fun onSuccess(result: ScreenshotResult) {
                    var image: String? = null
                    val buffer = result.hardwareBuffer
                    try {
                        if (generation == captureGeneration && app.state.value.assistanceEnabled && !app.state.value.nativeSurfaceVisible && app.settings.remoteScreenConsent && targetStillVisible()) {
                            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            val bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, true)
                            hardware?.recycle()
                            if (bitmap != null) {
                                // Editable fields are opaque before encoding. Pixels never touch files.
                                val canvas = Canvas(bitmap); val paint = Paint().apply { color = Color.rgb(235,235,235) }
                                collected.redactions.forEach { canvas.drawRect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat(), paint) }
                                windowMasks.forEach { canvas.drawRect(it, paint) }
                                val scale = minOf(1f, 1280f / maxOf(bitmap.width, bitmap.height))
                                val small = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                                val bytes = ByteArrayOutputStream().use { out -> small.compress(Bitmap.CompressFormat.JPEG, 65, out); out.toByteArray() }
                                if (bytes.size <= 300_000) image = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                if (small !== bitmap) small.recycle(); bitmap.recycle()
                            }
                        }
                    } catch (_: Exception) { /* Controls-only fallback; never persist or log pixels. */ }
                    finally { buffer.close() }
                    captureInProgress = false
                    collectScreen()
                    overlay?.render(app.state.value, true)
                    if (windowSignature() != capturedWindows || lastCollected?.imageAllowed != true) image = null
                    if (generation != captureGeneration || lastCollected?.context?.id != collected.context.id || !app.settings.remoteScreenConsent || app.state.value.nativeSurfaceVisible || !targetStillVisible()) callback(null)
                    else callback(collected.context.copy(image = image))
                }
            }
            try { takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, result) }
            catch (_: Exception) { captureInProgress = false; overlay?.render(app.state.value, true); callback(collected.context) }
        }, 120)
    }
    override fun onInterrupt() { app.stopAssistance(); app.cancel(); overlay?.clear(); localOverlay?.clear() }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        collector?.cancel()
        overlay?.clear()
        localOverlay?.clear()
        ++captureGeneration
        app.screenCapture = null
        WhitelistedCollector.clearContext()
        if (localObserving) app.observe(SafeScreen("", "", recognized = false))
        app.stopAssistance()
        app.screenConnection(false)
        super.onDestroy()
    }
}

private object WhitelistedCollector {
    private val secret = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
    private var revision = 0L
    private var hadScroll = false
    private var chatToken: String? = null
    private var headerToken: String? = null
    private fun opaque(value: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    fun noteEvent(type: Int) {
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_VIEW_SCROLLED) ++revision
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) hadScroll = true
    }
    fun clearContext() { ++revision; java.security.SecureRandom().nextBytes(secret); chatToken = null; headerToken = null; hadScroll = false }
    private fun id(node: AccessibilityNodeInfo) = node.viewIdResourceName?.substringAfter(":id/").orEmpty()
    private fun bounds(node: AccessibilityNodeInfo): Box {
        val rect = Rect(); node.getBoundsInScreen(rect)
        return Box(rect.left, rect.top, rect.right, rect.bottom)
    }
    fun collect(root: AccessibilityNodeInfo, version: String): SafeScreen {
        val candidates = mutableMapOf<Target, MutableSet<Box>>()
        val headers = mutableSetOf<String>()
        var sensitive = false
        var visited = 0
        var complete = true
        var chatInputPresent = false
        val rootBounds = bounds(root)
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (++visited > 1800 || depth > 35) { complete = false; return }
            if (!node.isVisibleToUser) return
            if (node.isPassword || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)) { sensitive = true; return }
            val resource = id(node)
            val editable = node.isEditable || node.className?.toString()?.contains("EditText") == true
            if (editable) {
                if (resource in setOf("entry", "conversation_entry", "message_entry")) chatInputPresent = true
                return // Never inspect editable values, hints, descriptions or keyboard contents.
            }
            val ownBounds = bounds(node)
            if (resource in setOf("conversation_contact_name", "conversation_contact_name_tv", "conversation_title", "toolbar_title") &&
                ownBounds.valid && ownBounds.top < rootBounds.bottom / 4) {
                // An opaque change detector only, never proof of a unique recipient or an exported name.
                val header = node.text?.take(120)?.toString()?.trim().orEmpty()
                if (header.isNotBlank()) headers.add(opaque("header:$header"))
            }
            val controlIds = mutableListOf(resource)
            var controlBox: Box? = if (node.isClickable && node.isEnabled) ownBounds else null
            var parent = node.parent
            var levels = 0
            while (parent != null && levels++ < 3) {
                val current = parent
                controlIds.add(id(current))
                if (controlBox == null && current.isClickable && current.isEnabled) controlBox = bounds(current)
                parent = current.parent
                @Suppress("DEPRECATION") current.recycle()
            }
            @Suppress("DEPRECATION") parent?.recycle()
            // Only known control resources are allowed to expose their short semantic labels locally.
            // Message bodies, sent-status text, visible-card counts and node uniqueId are never outcome evidence.
            if (ControlPolicy.possibleControl(controlIds)) {
                val target = ControlPolicy.labelTarget(node.contentDescription, node.text)
                if (target != null && ControlPolicy.accepts(target, controlIds) && controlBox?.valid == true)
                    candidates.getOrPut(target) { mutableSetOf() }.add(controlBox!!)
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
                try { visit(child, depth + 1) } finally { @Suppress("DEPRECATION") child.recycle() }
            }
        }
        visit(root, 0)
        val targets = if (!complete || sensitive) emptyMap() else candidates.mapNotNull { (key, boxes) ->
            if (boxes.size == 1 && (key != Target.ATTACH || chatInputPresent)) key to boxes.single() else null
        }.toMap()
        val inChat = targets.containsKey(Target.ATTACH)
        if (inChat) {
            val header = headers.singleOrNull()
            if (chatToken == null || (header != null && headerToken != null && header != headerToken)) {
                ++revision; chatToken = java.util.UUID.randomUUID().toString()
            }
            headerToken = header
        } else if (targets.isEmpty()) { chatToken = null; headerToken = null }
        val token = opaque("window:${root.windowId}:revision:$revision:controls:${targets.entries.sortedBy { it.key.name }}")
        val scrolled = hadScroll; hadScroll = false
        return SafeScreen("com.whatsapp", version, targets = targets, sensitive = sensitive,
            recognized = targets.isNotEmpty(), contextToken = chatToken, screenToken = token,
            revision = revision, scrolled = scrolled)
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
            contentDescription = "Abrir ayuda de TINO"
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
        panel.addView(TextView(service).apply { text = "TINO · un paso a la vez"; textSize = 16f; setTextColor(green); typeface = Typeface.DEFAULT_BOLD })
        val message = guide.notice ?: guide.instruction
        panel.addView(TextView(service).apply { text = message; textSize = 20f; setTextColor(Color.rgb(35, 41, 37)); setPadding(0, dp(8), 0, dp(6)) })
        fun button(title: String, action: () -> Unit) { panel.addView(Button(service).apply {
            text = title; textSize = 16f; minHeight = dp(48); isAllCaps = false; setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2)) }
        if (guide.stage == Stage.CONFIRM && guide.notice == null) button("Sí, quiero compartir mi ubicación") { app.confirmShare() }
        if (guide.stage == Stage.VERIFY && guide.reportAvailable && app.state.value.screenPackage == "com.whatsapp")
            button("Lo envié · registrar mi reporte") { app.reportGuideOutcome() }
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Repetir" to { app.repeat() }, "Me perdí" to { app.lost() }).forEach { (title, action) ->
            row.addView(Button(service).apply { text = title; isAllCaps = false; textSize = 16f; minHeight = dp(48); setOnClickListener { action() } }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        panel.addView(row)
        if (guide.stage == Stage.COMPLETE) button("Practicar sin pistas") { app.startGuide(practice = true) }
        button("Pedir ayuda a mi familia") { app.requestHelp() }
        panel.addView(TextView(service).apply { text = app.state.value.status; textSize = 15f; setTextColor(green); setPadding(0, dp(6), 0, dp(6)) })
        button("Hablar con TINO") {
            app.requestVoiceFromOverlay()
            service.startActivity(Intent(service, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        val controls = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Abrir TINO" to {
            service.startActivity(Intent(service, MainActivity::class.java).putExtra("destination","chat").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
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
        runCatching { manager.addView(view, params); views.add(view) }.onFailure { app.status("No pude mostrar la burbuja. Podés seguir desde TINO.") }
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
