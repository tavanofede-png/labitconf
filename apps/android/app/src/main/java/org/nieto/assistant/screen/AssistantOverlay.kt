package org.nieto.assistant.screen

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.SystemClock
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.nieto.assistant.AppState
import org.nieto.assistant.MainActivity
import org.nieto.assistant.NietoApp
import org.nieto.assistant.family.FamilyActivity
import org.nieto.assistant.voice.VoiceInputActivity
import org.nieto.assistant.voice.VoicePhase

/** Stable native views preserve the draft and keyboard while streaming state updates arrive. */
class AssistantOverlay(private val service: NietoAccessibilityService, private val app: NietoApp) {
    private val manager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val green = Color.rgb(0,107,100)
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    private var bubble: Button? = null
    private var panel: ScrollView? = null
    private var highlight: View? = null
    private var message: TextView? = null
    private var status: TextView? = null
    private var voice: Button? = null
    private var editor: EditText? = null
    private var draft = ""
    private var expanded = false
    private var inputMode = false
    private var panelParams: WindowManager.LayoutParams? = null
    private var marked: org.nieto.assistant.flow.Box? = null
    fun clear() {
        if(app.state.value.assistanceEnabled && app.settings.remoteScreenConsent) editor?.let { draft = it.text.toString() }
        else { draft=""; editor?.setText(""); expanded=false; inputMode=false }
        listOfNotNull(bubble,panel,highlight).forEach { runCatching { manager.removeView(it) } }
        bubble=null; panel=null; highlight=null; editor=null; marked=null
    }
    fun render(state: AppState, captureVisible: Boolean) {
        if (!state.assistanceEnabled || !app.settings.remoteScreenConsent || !state.overlayEnabled || (state.nativeSurfaceVisible && !state.voiceSurfaceVisible) || !captureVisible) { clear(); return }
        if(state.voiceSurfaceVisible) {
            editor?.let { draft=it.text.toString() }; remove(bubble); remove(panel); bubble=null; panel=null; editor=null
        } else {
            if (state.bubbleEnabled) { remove(bubble);remove(panel);bubble=null;panel=null;editor=null } else if (bubble == null) createBubble()
            if (!state.bubbleEnabled && expanded && panel == null) createPanel()
        }
        if (!expanded && panel != null) { editor?.let { draft=it.text.toString() }; remove(panel); panel=null; editor=null }
        message?.text = state.voice.partialText.ifBlank { state.answer }
        status?.text = (if (state.screen?.blocked == true) state.screen.notice else state.voice.message + "\n" + state.screen?.notice.orEmpty()) +
            state.ttsNotice.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        voice?.text = when(state.voice.phase) {
            VoicePhase.LISTENING -> "Terminar de hablar"
            VoicePhase.CONNECTING, VoicePhase.WAITING, VoicePhase.SPEAKING -> "Cancelar respuesta"
            else -> "Hablar con TINO"
        }
        val target = if (state.screenValid && state.screen?.packageName == state.screenPackage) ScreenPolicy.target(state.screenStep, state.screen, SystemClock.elapsedRealtime()) else null
        if (target != marked) {
            remove(highlight); highlight=null; marked=target
            if (target != null) {
                highlight = object : View(service) {
                    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(38,140,72); style=Paint.Style.STROKE; strokeWidth=dp(4).toFloat() }
                    override fun onDraw(canvas: Canvas) { canvas.drawRoundRect(target.left.toFloat(),target.top.toFloat(),target.right.toFloat(),target.bottom.toFloat(),dp(10).toFloat(),dp(10).toFloat(),paint) }
                }
                add(highlight!!, WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT))
            }
        }
    }
    private fun createBubble() {
        bubble = Button(service).apply { text="t"; textSize=24f; setTextColor(Color.WHITE); backgroundTintList=android.content.res.ColorStateList.valueOf(green)
            contentDescription="Abrir asistencia TINO"; setOnClickListener { expanded=!expanded; inputMode=false; render(app.state.value,true) } }
        add(bubble!!,WindowManager.LayoutParams(dp(60),dp(60),WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.END; x=dp(8); y=dp(65) })
    }
    private fun createPanel() {
        val layout=LinearLayout(service).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(12),dp(14),dp(12)); setBackgroundColor(Color.rgb(243,246,246)) }
        fun label(text: String,size: Float=18f) = TextView(service).apply { this.text=text; textSize=size; setTextColor(green); setPadding(0,dp(5),0,dp(5)) }.also { layout.addView(it) }
        fun button(text:String,action:()->Unit) = Button(service).apply { this.text=text; isAllCaps=false; textSize=16f; minHeight=dp(48); setOnClickListener { action() } }.also { layout.addView(it,LinearLayout.LayoutParams(-1,-2)) }
        label("TINO · vos hacés cada toque",16f)
        message=label(app.state.value.answer,20f)
        status=label(app.state.value.voice.message,14f)
        voice=button("Hablar con TINO") {
            if (app.state.value.voice.active) app.cancelRealtimeVoice()
            else {
                afterKeyboard { app.prepareVoice { service.startActivity(Intent(service,VoiceInputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
        }
        button("Escribir una consulta") {
            inputMode=true
            panelParams?.let { it.flags=0; manager.updateViewLayout(panel,it) }
            editor?.requestFocus()
            editor?.post { (service.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT) }
        }
        editor=EditText(service).apply { hint="¿Qué querés hacer?"; textSize=18f; maxLines=3; setText(draft); inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters=arrayOf(android.text.InputFilter.LengthFilter(1000)); importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS }
        layout.addView(editor)
        button("Enviar consulta escrita") { val text=editor?.text.toString().trim(); if(text.isNotBlank()) { draft=""; editor?.setText(""); afterKeyboard { app.sendScreenMessage(text) } } }
        button("Ya lo hice · actualizar pantalla") { afterKeyboard { app.refreshScreen() } }
        button("Repetir este paso") { app.repeatAssistance() }
        button("Me perdí") { afterKeyboard { app.lostAssistance() } }
        button("Abrir Familia") { service.startActivity(Intent(service,FamilyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        button("Abrir TINO") { service.startActivity(Intent(service,MainActivity::class.java).putExtra("destination","chat").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        button("Seguir por mi cuenta") { hideKeyboard(); draft=""; app.continueWithoutTino() }
        button("Apagar asistencia y borrar tarea") { hideKeyboard(); draft=""; app.stopAssistance() }
        panel=ScrollView(service).apply { addView(layout) }
        val display=service.resources.displayMetrics
        panelParams=WindowManager.LayoutParams(display.widthPixels-dp(24),(display.heightPixels*0.46).toInt(),WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y=dp(20); softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
        add(panel!!,panelParams!!)
    }
    private fun hideKeyboard() {
        (service.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editor?.windowToken,0)
        inputMode=false; editor?.clearFocus()
        panelParams?.let { it.flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; runCatching { manager.updateViewLayout(panel,it) } }
    }
    private fun afterKeyboard(action:()->Unit) {
        hideKeyboard()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if(app.state.value.assistanceEnabled && app.settings.remoteScreenConsent) action() },220)
    }
    private fun add(view:View,params:WindowManager.LayoutParams) { runCatching { manager.addView(view,params) }.onFailure { app.status("No pude mostrar la burbuja. Revisá Accesibilidad desde TINO.") } }
    private fun remove(view:View?) { if(view!=null) runCatching { manager.removeView(view) } }
}
