package org.nieto.assistant.presence

import android.app.KeyguardManager
import android.app.Service
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.ImageView
import kotlinx.coroutines.*
import org.nieto.assistant.*
import org.nieto.assistant.flow.Stage
import kotlin.math.abs

/** A shortcut only: uses draw-over-apps permission, never accessibility or screen capture. */
class FloatingBubbleService : Service() {
    private val app get() = application as NietoApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private var bubble: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private val lockEvents = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { render() } }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        registerReceiver(lockEvents, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) })
        scope.launch { app.state.collect { render() } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop" || !Settings.canDrawOverlays(this) || app.settings.read("accountSession").isBlank()) { stopSelf(); return START_NOT_STICKY }
        try {
            showPresence(4201, "Burbuja TINO activa", "Tocá la mascota para volver al chat.", false)
            app.bubbleState(true); render()
        } catch (_: Exception) { app.presenceNotice("No pude mostrar la burbuja. Revisá el permiso de Android."); stopSelf() }
        return START_NOT_STICKY
    }
    private fun render() {
        val state = app.state.value
        if (!state.bubbleEnabled) return
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        val guideBubble = state.accessibilityConnected && state.overlayEnabled && app.settings.screenConsent &&
            (state.task?.active == true || state.guide.stage !in listOf(Stage.IDLE, Stage.COMPLETE, Stage.CANCELLED))
        val hidden = state.foreground || getSystemService(KeyguardManager::class.java).isKeyguardLocked || guideBubble
        if (hidden) { removeBubble(); return }
        if (bubble != null) return
        val dp = resources.displayMetrics.density
        val size = (68 * dp).toInt()
        val layout = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = resources.displayMetrics.widthPixels - size - (12 * dp).toInt()
            y = resources.displayMetrics.heightPixels / 3
        }
        val image = ImageView(this).apply {
            setImageResource(R.drawable.tino_mascot); contentDescription = "Abrir chat de TINO"
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(227, 249, 246)); setStroke((2 * dp).toInt(), Color.rgb(0, 214, 201)) }
            elevation = 8 * dp
            setOnClickListener { app.openChat(); startActivity(Intent(this@FloatingBubbleService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        }
        var startX = 0f; var startY = 0f; var originalX = 0; var originalY = 0
        image.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; originalX = layout.x; originalY = layout.y; true }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = (originalX + event.rawX - startX).toInt().coerceIn(0, (resources.displayMetrics.widthPixels - size).coerceAtLeast(0))
                    layout.y = (originalY + event.rawY - startY).toInt().coerceIn(0, (resources.displayMetrics.heightPixels - size).coerceAtLeast(0))
                    runCatching { windows.updateViewLayout(image, layout) }; true
                }
                MotionEvent.ACTION_UP -> { if (abs(event.rawX - startX) + abs(event.rawY - startY) < 10 * dp) view.performClick(); true }
                else -> false
            }
        }
        try { windows.addView(image, layout); bubble = image; params = layout }
        catch (_: Exception) { app.presenceNotice("La burbuja necesita permiso para aparecer sobre otras apps."); stopSelf() }
    }
    private fun removeBubble() { bubble?.let { runCatching { windows.removeView(it) } }; bubble = null; params = null }
    override fun onDestroy() {
        scope.cancel(); runCatching { unregisterReceiver(lockEvents) }; removeBubble(); app.bubbleState(false)
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
}
