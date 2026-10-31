package org.nieto.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID
import org.nieto.assistant.NietoApp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable fun AudioMessageButton(encoded: String) {
    val context = LocalContext.current
    val app = context.applicationContext as NietoApp
    val lifecycle = LocalLifecycleOwner.current
    val audioOwner = remember { UUID.randomUUID().toString() }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var playing by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf(false) }
    fun releasePlayback(failed: Boolean = false) {
        player?.release(); player = null; file?.delete(); file = null
        playing = false; preparing = false; failure = failed
        app.resumeWakeAfterRecording(audioOwner)
    }
    DisposableEffect(encoded,lifecycle) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) releasePlayback() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer);releasePlayback() }
    }
    TextButton(onClick = {
        if (playing || preparing) { releasePlayback(); return@TextButton }
        runCatching {
            app.stopSpeaking(); app.pauseWakeForRecording(audioOwner)
            val clip = File.createTempFile("tino-message-", ".m4a", context.cacheDir)
            file = clip; clip.writeBytes(Base64.decode(encoded, Base64.DEFAULT))
            val active=MediaPlayer(); player=active; preparing=true; failure=false
            active.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            active.setDataSource(clip.absolutePath)
            active.setOnPreparedListener { current -> if(player===current) runCatching { current.start();preparing=false;playing=true }.onFailure { releasePlayback(true) } }
            active.setOnCompletionListener { current -> if(player===current) releasePlayback() }
            active.setOnErrorListener { current,_,_ -> if(player===current) releasePlayback(true);true }
            active.prepareAsync()
        }.onFailure { releasePlayback(true) }
    }, enabled = encoded.isNotBlank()) { Text(if (failure) "Reintentar audio" else if (preparing) "Cancelar carga" else if (playing) "Detener audio" else "▶ Escuchar", color = DashTeal) }
}

@Composable internal fun VoiceNoteDialog(name: String, busy: Boolean, serverError: String?, serverMessage: String?, close: () -> Unit, send: (String, String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as NietoApp
    val lifecycle = LocalLifecycleOwner.current
    val file = remember { File.createTempFile("tino-voice-", ".m4a", context.cacheDir) }
    var requestId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    val audioOwner = remember { UUID.randomUUID().toString() }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recording by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }
    fun stopRecording() {
        val active = recorder ?: return
        runCatching { active.stop(); ready = file.length() in 100..131072; if(!ready) error = "No pude preparar el audio. Probá grabarlo de nuevo." }.onFailure { ready = false; error = "El aviso fue demasiado corto. Probá grabarlo de nuevo." }
        active.release(); recorder = null; recording = false
        app.resumeWakeAfterRecording(audioOwner)
    }
    fun startRecording() {
        app.pauseWakeForRecording(audioOwner); app.stopSpeaking()
        requestId=UUID.randomUUID().toString();sent=false;error=null;ready=false
        runCatching {
            @Suppress("DEPRECATION") val active = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            recorder = active
            active.setAudioSource(MediaRecorder.AudioSource.MIC); active.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            active.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); active.setAudioSamplingRate(16000); active.setAudioEncodingBitRate(24000)
            active.setMaxDuration(30_000); active.setMaxFileSize(131072); active.setOutputFile(file.absolutePath)
            active.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) stopRecording() }
            active.prepare(); active.start(); recording = true; ready = false; seconds = 0; error = null
        }.onFailure { recorder?.release(); recorder = null; recording = false; app.resumeWakeAfterRecording(audioOwner); error = "No pude iniciar la grabación. Revisá el micrófono y probá otra vez." }
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) startRecording() else error = "El aviso de voz necesita permiso de micrófono." }
    LaunchedEffect(recording) { while (recording) { delay(1000); seconds++ } }
    LaunchedEffect(busy, serverMessage) { if (sent && !busy && serverError == null && serverMessage == "Aviso de voz enviado.") close() }
    DisposableEffect(Unit) { onDispose { runCatching { recorder?.stop() }; recorder?.release(); file.delete(); app.resumeWakeAfterRecording(audioOwner) } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && recording) stopRecording() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("Un aviso para $name") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Grabá hasta 30 segundos. Podés escucharlo antes de enviarlo. El aviso estará disponible durante 24 horas.", color = DashMuted, lineHeight = 23.sp)
            if (recording) { Text("● Grabando · ${seconds}s", color = DashRed, fontSize = 24.sp); Button(onClick = { stopRecording() }) { Text("Terminar grabación") } }
            else {
                OutlinedButton(onClick = { if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording() else microphone.launch(Manifest.permission.RECORD_AUDIO) }, enabled = !busy) { Text(if (ready) "Grabar de nuevo" else "Grabar aviso", color = DashTeal) }
                if (ready) AudioMessageButton(Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            }
            (error ?: if (sent) serverError else null)?.let { Text(it, color = DashRed, fontSize = 14.sp) }
            if (busy && sent) LinearProgressIndicator(color = DashCyan)
        }
    }, confirmButton = { TextButton(onClick = {
        if (ready) { sent = true; send(Base64.encodeToString(file.readBytes(), Base64.NO_WRAP), requestId) }
    }, enabled = ready && !recording && !busy) { Text("Enviar aviso", color = DashTeal) } }, dismissButton = { TextButton(onClick = close, enabled = !busy) { Text("Cancelar") } })
}
