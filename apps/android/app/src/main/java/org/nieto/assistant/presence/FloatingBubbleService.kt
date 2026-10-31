package org.nieto.assistant.presence

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.Service
import android.content.*
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import androidx.core.view.ViewCompat
import kotlinx.coroutines.*
import org.nieto.assistant.*
import org.nieto.assistant.flow.Stage
import kotlin.math.hypot

/** User-enabled chat head. Ordinary chat never launches an Activity or captures the other app. */
class FloatingBubbleService : Service() {
    companion object {
        private var reference = java.lang.ref.WeakReference<FloatingBubbleService>(null)
        internal var active: FloatingBubbleService?
            get() = reference.get()
            private set(value) { reference = java.lang.ref.WeakReference(value) }
    }
    private val app get() = application as NietoApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private var bubble: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var panel: FloatingChatPanel? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var snap: ValueAnimator? = null
    private var draft = ""
    private val writtenDraft=org.nieto.assistant.screen.WrittenDraft()
    private var pending = emptyList<ChatAttachment>()
    private var loadingFiles = false
    private var dictating = false
    private var voice: OverlayDictation? = null
    private var pin = BubbleDock.Pin(BubbleDock.Edge.RIGHT,.35f)
    private val lockEvents = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { render() } }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); active = this
        val saved = app.settings.read("bubblePin").split(':')
        if (saved.size == 2) runCatching { pin = BubbleDock.Pin(BubbleDock.Edge.valueOf(saved[0]), saved[1].toFloat()) }
        registerReceiver(lockEvents, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) })
        scope.launch { app.state.collect { render() } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop" || !Settings.canDrawOverlays(this) || app.settings.read("accountSession").isBlank()) { stopSelf(); return START_NOT_STICKY }
        try {
            showPresence(4201, "Burbuja TINO activa", "Chat flotante · arrastrá la mascota a un borde.", false)
            app.bubbleState(true); render()
        } catch (_: Exception) { app.presenceNotice("No pude mostrar la burbuja. Revisá el permiso de Android."); stopSelf() }
        return START_NOT_STICKY
    }
    private fun safeArea(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.currentWindowMetrics
            val bars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return Rect(bars.left + dp(8),bars.top + dp(8), metrics.bounds.width() - bars.right - dp(8), metrics.bounds.height() - bars.bottom - dp(8))
        }
        val metrics = resources.displayMetrics
        return Rect(dp(8),dp(32),metrics.widthPixels-dp(8),metrics.heightPixels-dp(32))
    }
    private fun render() {
        val state = app.state.value
        if (!state.bubbleEnabled) return
        if (!Settings.canDrawOverlays(this) || app.settings.read("accountSession").isBlank()) { stopSelf(); return }
        val guideBubble = state.accessibilityConnected && state.overlayEnabled && app.settings.screenConsent &&
            (state.task?.active == true || state.guide.stage !in listOf(Stage.IDLE, Stage.COMPLETE, Stage.CANCELLED))
        val hidden = state.foreground || getSystemService(KeyguardManager::class.java).isKeyguardLocked || guideBubble
        if (hidden) { closePanel(); removeBubble(); return }
        panel?.render(state)
        if (bubble != null) return
        val size = dp(64); val area = safeArea()
        val point = BubbleDock.position(pin,area.width()-size,area.height()-size)
        val layout = WindowManager.LayoutParams(size,size,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; x = area.left+point.x; y = area.top+point.y; title = "TINO · burbuja" }
        val image = object: ImageView(this) { override fun performClick(): Boolean { super.performClick();return true } }.apply {
            setImageResource(R.drawable.tino_mascot); contentDescription = "Abrir chat flotante de TINO. Arrastrá para fijar a un borde."
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(227,249,246)); setStroke(dp(2),Color.rgb(0,214,201)) }
            elevation = dp(8).toFloat(); isClickable = true; isFocusable = true
            setOnClickListener { if (panel == null) openPanel() else closePanel() }
        }
        BubbleDock.Edge.entries.forEach { edge ->
            val label = when(edge) { BubbleDock.Edge.LEFT -> "Fijar al margen izquierdo"; BubbleDock.Edge.RIGHT -> "Fijar al margen derecho"; BubbleDock.Edge.TOP -> "Fijar al margen superior"; BubbleDock.Edge.BOTTOM -> "Fijar al margen inferior" }
            ViewCompat.addAccessibilityAction(image,label) { _, _ -> pin = BubbleDock.Pin(edge,.5f); settle(); true }
        }
        var startX=0f; var startY=0f; var originalX=0; var originalY=0; var dragged=false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        image.setOnTouchListener { view,event ->
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { snap?.cancel(); startX=event.rawX; startY=event.rawY; originalX=layout.x; originalY=layout.y; dragged=false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(event.rawX-startX,event.rawY-startY) > slop) dragged=true
                    if (dragged) {
                        closePanel(); val bounds=safeArea()
                        layout.x=(originalX+event.rawX-startX).toInt().coerceIn(bounds.left,(bounds.right-size).coerceAtLeast(bounds.left))
                        layout.y=(originalY+event.rawY-startY).toInt().coerceIn(bounds.top,(bounds.bottom-size).coerceAtLeast(bounds.top))
                        runCatching { windows.updateViewLayout(image,layout) }
                    }; true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragged) { val bounds=safeArea(); pin=BubbleDock.nearest(layout.x-bounds.left,layout.y-bounds.top,bounds.width()-size,bounds.height()-size); settle() }
                    else if (event.actionMasked==MotionEvent.ACTION_UP) view.performClick()
                    true
                }
                else -> false
            }
        }
        try { windows.addView(image,layout); bubble=image; params=layout }
        catch (_: Exception) { app.presenceNotice("La burbuja necesita permiso para aparecer sobre otras apps."); stopSelf() }
    }
    private fun settle() {
        val view=bubble ?: return; val layout=params ?: return; val area=safeArea()
        val target=BubbleDock.position(pin,area.width()-layout.width,area.height()-layout.height)
        val fromX=layout.x; val fromY=layout.y
        snap?.cancel()
        snap=ValueAnimator.ofFloat(0f,1f).apply {
            duration=180
            addUpdateListener { value -> val f=value.animatedValue as Float; layout.x=(fromX+(area.left+target.x-fromX)*f).toInt(); layout.y=(fromY+(area.top+target.y-fromY)*f).toInt(); runCatching { windows.updateViewLayout(view,layout) } }
            start()
        }
        app.settings.write("bubblePin","${pin.edge}:${pin.fraction}")
    }
    private fun openPanel() {
        if (panel != null || bubble == null) return
        val area=safeArea(); val anchor=params ?: return
        val width=dp(420).coerceAtMost(area.width()); val height=dp(560).coerceAtMost(area.height())
        val layout=WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.LEFT; title="TINO · chat flotante"
            x=(if (anchor.x > area.centerX()) anchor.x-width-dp(8) else anchor.x+anchor.width+dp(8)).coerceIn(area.left,(area.right-width).coerceAtLeast(area.left))
            y=(anchor.y-height/3).coerceIn(area.top,(area.bottom-height).coerceAtLeast(area.top))
            softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        }
        val view=FloatingChatPanel(this,draft,{
            closePanel();draft="";pending=emptyList();writtenDraft.edit("");app.closeChatConversation()
        },::closePanel,{
            closePanel(); app.openChat(); startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        },::send,::toggleVoice,{ images -> launchHelper(if(images) "images" else "files") },{ index -> pending=pending.filterIndexed { i,_ -> i!=index }; panel?.attachments(pending) })
        // IME insets are relative to this overlay's frame, not to the entire display.
        // Retain the absolute keyboard top even when moving above it makes the overlap zero.
        var keyboardTop: Int? = null
        if (Build.VERSION.SDK_INT >= 30) view.setOnApplyWindowInsetsListener { _,insets ->
            val visible=insets.isVisible(WindowInsets.Type.ime())
            val overlap=insets.getInsets(WindowInsets.Type.ime()).bottom
            val bounds=safeArea()
            if(!visible) keyboardTop=null
            else if(overlap>0 && view.height>0) {
                val location=IntArray(2);view.getLocationOnScreen(location)
                val top=location[1]+view.height-overlap
                keyboardTop=minOf(keyboardTop ?: top,top)
            }
            val nextHeight=if(visible && keyboardTop!=null) (keyboardTop!!-bounds.top-dp(8)).coerceAtLeast(dp(180)).coerceAtMost(height) else height
            val nextY=if(visible) bounds.top else (anchor.y-height/3).coerceIn(bounds.top,(bounds.bottom-height).coerceAtLeast(bounds.top))
            if(layout.height!=nextHeight || layout.y!=nextY) {
                layout.height=nextHeight;layout.y=nextY
                view.post { if(panel===view) runCatching { windows.updateViewLayout(view,layout) } }
            }
            insets
        }
        view.setOnTouchListener { _,event -> if(event.actionMasked==MotionEvent.ACTION_OUTSIDE) { view.performClick();closePanel(); true } else false }
        try { windows.addView(view,layout); panel=view; panelParams=layout; view.requestApplyInsets(); view.attachments(pending); view.render(app.state.value)
            if(app.state.value.chat.isEmpty())scope.launch { delay(2000);if(panel===view)app.showWelcomeMessage() }
        }
        catch (_: Exception) { notice("No pude abrir el chat flotante.") }
    }
    private fun send(text: String) {
        val view=panel ?: return
        if (loadingFiles || dictating || app.state.value.busy || app.state.value.voice.active || (text.isBlank() && pending.isEmpty())) return
        if(!app.settings.openAiConsent && !app.settings.juryDemo){view.notice("Autorizá las consultas con IA desde Ajustes de TINO.");return}
        val files=pending.toList()
        writtenDraft.edit(text.ifBlank { if(files.isNotEmpty()) "Ayudame a entender los archivos que adjunté." else "" })
        val submission=writtenDraft.begin(true)?:return
        fun complete(sent:Boolean) {
            writtenDraft.edit(panel?.input?.text?.toString()?:draft)
            writtenDraft.complete(submission,sent)
            draft=writtenDraft.value
            panel?.input?.setText(draft)
            if(sent && pending==files){pending=emptyList();panel?.attachments(pending)}
            notice(if(sent)"Podés seguir escribiendo desde acá." else "No se envió. Conservé tu mensaje para reintentar.")
        }
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken,0)
        if(files.isEmpty() && !app.settings.juryDemo && app.state.value.assistanceEnabled && app.settings.remoteScreenConsent) {
            app.selectWrittenMode()
            closePanel()
            scope.launch { delay(300);app.sendVisualMessage(text,onSubmitted={sent -> complete(sent);openPanel()}) }
            return
        }
        app.sendMessage(text,files,onComplete=::complete)
    }

    internal fun notice(text: String) { panel?.notice(text); app.presenceNotice(text) }
    private fun launchHelper(action: String) {
        if (dictating) stopVoice(false)
        closePanel()
        runCatching { startActivity(Intent(this,OverlayActionActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { resumeHelper(); notice("No pude abrir el permiso o el selector de Android.") }
    }
    internal fun resumeHelper() { if(!app.state.value.foreground && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) openPanel() }
    internal fun receiveFiles(uris: List<Uri>) {
        resumeHelper()
        if (loadingFiles) return
        if (uris.size+pending.size>ChatAttachment.MAX_COUNT) { notice("Podés adjuntar hasta 3 archivos por mensaje."); return }
        loadingFiles=true; notice("Preparando archivos…")
        scope.launch {
            try {
                val selected=withContext(Dispatchers.IO) { uris.map { ChatAttachment.read(this@FloatingBubbleService,it) } }
                require((pending+selected).sumOf { it.byteSize }<=ChatAttachment.MAX_TOTAL_BYTES) { "Los adjuntos pueden sumar hasta 4 MB por mensaje." }
                pending = pending + selected
                panel?.attachments(pending)
                notice("Archivos listos. Revisalos y tocá Enviar.")
            } catch(cancel: CancellationException) { throw cancel }
            catch(error: Exception) { notice(error.message ?: "No pude abrir ese archivo.") }
            finally { loadingFiles=false }
        }
    }
    private fun toggleVoice() { if(dictating) stopVoice(true) else if(!app.state.value.busy) launchHelper("microphone") }
    internal fun startDictation() {
        if (panel==null || dictating || app.state.value.busy) return
        try {
            // Invoked synchronously while OverlayActionActivity is visible: Android while-in-use rules.
            showPresence(4201,"TINO está transcribiendo","Tocá el micrófono para terminar. El texto se revisa antes de enviar.",true)
            dictating=true; app.stopSpeaking(); app.pauseWakeForRecording(); panel?.listening(true)
            voice=OverlayDictation(this,scope,{ panel?.transcript(it) },{ text,error ->
                text?.let { panel?.appendTranscript(it) }; finishVoice(); panel?.notice(error ?: "Listo. Revisá la transcripción y tocá Enviar.")
            }).also { it.start() }
        } catch (_: Exception) { finishVoice(); notice("No pude activar el micrófono. Revisá el permiso de Android.") }
    }
    private fun finishVoice() {
        dictating=false; panel?.listening(false); app.resumeWakeAfterRecording()
        runCatching { showPresence(4201,"Burbuja TINO activa","Chat flotante · arrastrá la mascota a un borde.",false) }
    }
    private fun stopVoice(deliver: Boolean) { voice?.stop(deliver); voice=null; if(dictating) finishVoice() }
    private fun closePanel() {
        if (dictating) stopVoice(false)
        panel?.let { draft=it.input.text.toString(); getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken,0); runCatching { windows.removeView(it) } }
        panel=null; panelParams=null
    }
    private fun removeBubble() { snap?.cancel(); snap=null; bubble?.let { runCatching { windows.removeView(it) } }; bubble=null; params=null }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); closePanel(); removeBubble(); render() }
    override fun onDestroy() {
        active=null; stopVoice(false); scope.cancel(); runCatching { unregisterReceiver(lockEvents) }; closePanel(); removeBubble(); app.bubbleState(false)
        pending=emptyList(); draft=""; stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
    private fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
}
