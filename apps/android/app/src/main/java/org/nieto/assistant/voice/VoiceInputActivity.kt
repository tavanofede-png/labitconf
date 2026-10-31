package org.nieto.assistant.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.nieto.assistant.NietoApp

/** A visible translucent activity owns microphone access while the target application stays visible. */
class VoiceInputActivity : Activity() {
    private val app get() = application as NietoApp
    private var started = false
    private var resumed = false
    private var collector: Job? = null
    private lateinit var status: TextView
    private lateinit var answer: TextView
    private lateinit var finish: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply { dimAmount=0.08f; gravity=Gravity.BOTTOM }
        val density=resources.displayMetrics.density
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding((20*density).toInt(),(18*density).toInt(),(20*density).toInt(),(22*density).toInt()); setBackgroundColor(Color.rgb(243,246,246)) }
        layout.addView(TextView(this).apply { text="TINO · consulta por voz"; textSize=21f; setTextColor(Color.rgb(0,107,100)) })
        status=TextView(this).apply { textSize=18f }; layout.addView(status)
        answer=TextView(this).apply { textSize=18f; maxLines=5 }; layout.addView(answer)
        finish=Button(this).apply { isAllCaps=false; text="Terminar de hablar"; setOnClickListener {
            if(app.state.value.voice.phase==VoicePhase.LISTENING) app.finishRealtimeInput() else app.cancelRealtimeVoice()
        } }; layout.addView(finish)
        layout.addView(Button(this).apply { text="Volver a la pantalla · apagar micrófono"; isAllCaps=false; setOnClickListener { app.onAssistantBackground(); finish() } })
        layout.addView(TextView(this).apply { text="El micrófono se apaga al terminar, cancelar o salir. Máximo 45 s. Voz generada por IA."; textSize=14f })
        setContentView(layout)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.WRAP_CONTENT)
        collector=app.scope.launch { app.state.collect { state ->
            status.text=state.voice.message
            answer.text=state.voice.partialText.ifBlank { state.answer }
            finish.text=if(state.voice.phase==VoicePhase.LISTENING) "Terminar de hablar" else "Cancelar respuesta"
            finish.isEnabled=state.voice.active
            if(!state.assistanceEnabled) finish()
        } }
    }
    override fun onResume() {
        super.onResume(); resumed=true; app.surfaceVisible("voice",true)
        if(!started) {
            started=true
            if(!app.settings.remoteScreenConsent || !app.settings.openAiConsent || !app.state.value.assistanceEnabled) { finish(); return }
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) app.startRealtimeVoice()
            else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),101)
        }
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==101 && resumed && grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED && app.settings.remoteScreenConsent && app.state.value.assistanceEnabled) app.startRealtimeVoice()
        else { app.status("El micrófono quedó apagado. Podés escribir desde la burbuja."); finish() }
    }
    override fun onStop() { resumed=false; app.onAssistantBackground(); app.surfaceVisible("voice",false); super.onStop() }
    override fun onDestroy() { collector?.cancel(); app.surfaceVisible("voice",false); super.onDestroy() }
}
