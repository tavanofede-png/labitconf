package org.nieto.assistant.screen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import org.nieto.assistant.AppState
import org.nieto.assistant.NietoApp
import org.nieto.assistant.flow.Box

/** A small voice control and a noninteractive pointer over the app the person is using. */
class AssistantOverlay(private val service: NietoAccessibilityService, private val app: NietoApp) {
    private val manager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var bubble: Button? = null
    private var highlight: View? = null
    private var marked: Box? = null
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()

    fun clear() {
        remove(bubble); remove(highlight)
        bubble = null; highlight = null; marked = null
    }

    fun render(state: AppState, captureVisible: Boolean) {
        if (!state.assistanceEnabled || !app.settings.remoteScreenConsent || !state.overlayEnabled ||
            state.nativeSurfaceVisible || !captureVisible) { clear(); return }
        if (bubble == null) {
            bubble = Button(service).apply {
                text = "t"; textSize = 24f; setTextColor(Color.WHITE); isAllCaps = false
                setOnClickListener {
                    if (app.state.value.voice.active) app.cancelRealtimeVoice() else app.requestSystemVoice()
                }
            }
            add(bubble!!, WindowManager.LayoutParams(dp(52), dp(52), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.END; x = dp(8); y = dp(65)
            })
        }
        bubble?.apply {
            contentDescription = if (state.voice.active) "Cancelar consulta de TINO" else "Hablar con TINO"
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (state.voice.active) Color.rgb(161, 63, 52) else Color.rgb(0, 107, 100))
        }
        val target = if (state.screenValid && state.screen?.packageName == state.screenPackage)
            ScreenPolicy.target(state.screenStep, state.screen, SystemClock.elapsedRealtime()) else null
        if (target != marked) {
            remove(highlight); highlight = null; marked = target
            if (target != null) {
                highlight = object : View(service) {
                    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.rgb(38, 140, 72); style = Paint.Style.STROKE; strokeWidth = dp(4).toFloat()
                    }
                    override fun onDraw(canvas: Canvas) {
                        canvas.drawRoundRect(target.left.toFloat(), target.top.toFloat(), target.right.toFloat(), target.bottom.toFloat(), dp(10).toFloat(), dp(10).toFloat(), paint)
                    }
                }
                add(highlight!!, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT))
            }
        }
    }

    private fun add(view: View, params: WindowManager.LayoutParams) {
        runCatching { manager.addView(view, params) }.onFailure { app.status("No pude mostrar el control de voz. Revisá Accesibilidad desde TINO.") }
    }
    private fun remove(view: View?) { if (view != null) runCatching { manager.removeView(view) } }
}
