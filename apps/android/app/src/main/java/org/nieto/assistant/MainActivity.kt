package org.nieto.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nieto.assistant.flow.GuidanceEngine
import org.nieto.assistant.flow.Stage

class MainActivity : ComponentActivity() {
    private val app get() = application as NietoApp
    private var recognizer: SpeechRecognizer? = null
    private var listening by mutableStateOf(false)
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginListening() else app.status("El micrófono quedó desactivado. Podés escribir tu consulta.")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NietoTheme { NietoScreen(app, listening, ::toggleVoice, ::openWhatsApp, ::openAccessibility) } }
        if (app.consumeVoiceRequest()) window.decorView.post { toggleVoice() }
    }
    override fun onResume() {
        super.onResume()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        app.screenConnection(enabled.split(':').any { it.contains("org.nieto.assistant/") })
    }
    private fun toggleVoice() {
        if (listening) { recognizer?.stopListening(); listening = false; return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        else beginListening()
    }
    private fun beginListening() {
        app.stopSpeaking()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            app.status("Este teléfono no tiene un servicio de reconocimiento de voz. Podés escribir tu consulta."); return
        }
        recognizer?.destroy()
        recognizer = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            else SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true; app.status("Te escucho. Tocá Terminar cuando quieras.") }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) {
                listening = false
                app.status(if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    "No pude entenderte. Podés intentar otra vez o escribir." else "El reconocimiento de voz no está disponible ahora. Podés escribir.")
            }
            override fun onResults(results: Bundle?) {
                listening = false
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { app.sendMessage(it) }
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        runCatching { recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }) }.onFailure { listening = false; app.status("No pude iniciar el micrófono. Podés escribir.") }
    }
    private fun openWhatsApp() {
        val intent = packageManager.getLaunchIntentForPackage("com.whatsapp")
        if (intent != null) startActivity(intent) else app.status("WhatsApp no está instalado. Podés consultar por chat o pedir ayuda a tu familia.")
    }
    private fun openAccessibility() { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (app.consumeVoiceRequest()) toggleVoice()
    }
    override fun onStop() { recognizer?.cancel(); listening = false; super.onStop() }
    override fun onDestroy() { recognizer?.destroy(); recognizer = null; super.onDestroy() }
}

private val Forest = Color(0xFF264C3B)
private val WarmWhite = Color(0xFFFAF8F2)
private val Ink = Color(0xFF242E28)
@Composable private fun NietoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Forest, onPrimary = Color.White, background = WarmWhite,
        surface = WarmWhite, onBackground = Ink, onSurface = Ink, secondaryContainer = Color(0xFFE8EDDA),
        onSecondaryContainer = Forest), typography = Typography(
        bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, lineHeight = 29.sp),
        bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
        titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
        labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    ), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun NietoScreen(app: NietoApp, listening: Boolean, onVoice: () -> Unit, onWhatsApp: () -> Unit, onAccessibility: () -> Unit) {
    val state by app.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var disclosure by remember { mutableStateOf(false) }
    Scaffold(containerColor = WarmWhite,
        topBar = { Column(Modifier.fillMaxWidth().background(WarmWhite).statusBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("N", color = WarmWhite, fontSize = 29.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Forest, RoundedCornerShape(15.dp)).padding(horizontal = 15.dp, vertical = 7.dp))
                Spacer(Modifier.width(12.dp))
                Column { Text("NIETO", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Forest); Text("Acá, con vos.", fontSize = 16.sp) }
            }
        } },
        bottomBar = { NavigationBar(containerColor = Color(0xFFEEEFE5)) {
            listOf("Inicio" to "◎", "Familia" to "♡", "Ajustes" to "☷").forEachIndexed { index, item ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index; if (index == 1 && app.settings.token.isNotBlank()) app.refreshFamily() },
                    icon = { Text(item.second, fontSize = 26.sp) }, label = { Text(item.first, fontSize = 16.sp) })
            }
        } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Forest)
            when (tab) {
                0 -> HomeContent(app, state, listening, onVoice, onWhatsApp, onSettings = { tab = 2 })
                1 -> FamilyContent(app, state)
                else -> SettingsContent(app, state, onConsent = { disclosure = true }, onAccessibility)
            }
            Surface(color = Color(0xFFEDEFE5), shape = RoundedCornerShape(16.dp)) {
                Text(state.status, modifier = Modifier.padding(16.dp), fontSize = 17.sp, lineHeight = 24.sp)
            }
            if (state.telemetryPending > 0) Text("Progreso sin sincronizar: ${state.telemetryPending} evento(s).", fontSize = 15.sp)
            Text("Hecho para aprender a tu ritmo.\nNIETO · Labitconf 2026", fontSize = 14.sp, color = Forest, modifier = Modifier.padding(top = 12.dp))
        }
    }
    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false }, title = { Text("Vos decidís qué puedo ver") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Durante una guía, NIETO identifica controles accesibles de WhatsApp para mostrarte dónde tocar y comprobar tus pasos.")
            Text("No toca botones ni escribe por vos. No hace capturas, no envía el árbol de pantalla y no lee campos de contraseña ni teclados. En otras apps detiene la guía.")
            Text("La burbuja aparece con este permiso. Podés ocultarla o revocar el acceso en cualquier momento. Android te pedirá habilitar NIETO en Accesibilidad.")
        } }, confirmButton = { TextButton(onClick = { app.settings.screenConsent = true; app.setOverlay(true); disclosure = false; onAccessibility() }) { Text("Acepto y abrir ajustes") } },
        dismissButton = { TextButton(onClick = { disclosure = false }) { Text("Ahora no") } })
}

@Composable private fun HomeContent(app: NietoApp, state: AppState, listening: Boolean, onVoice: () -> Unit, onWhatsApp: () -> Unit, onSettings: () -> Unit) {
    var message by remember { mutableStateOf("") }
    Text("Un paso hoy.\nMás confianza mañana.", style = MaterialTheme.typography.headlineLarge, color = Forest, fontWeight = FontWeight.SemiBold)
    Text("Contame qué querés hacer. Lo aprendemos juntos, de a una cosa por vez.")
    BigButton(if (listening) "Terminar de hablar" else "Hablar con NIETO", onVoice, enabled = !state.busy || listening)
    Text("El micrófono se activa solo al tocar Hablar. Se usa el reconocimiento de Android; según tu teléfono, puede necesitar el servicio de voz de su proveedor.", fontSize = 15.sp, lineHeight = 21.sp)
    OutlinedTextField(value = message, onValueChange = { message = it.take(1000) }, modifier = Modifier.fillMaxWidth(),
        label = { Text("O escribime acá") }, placeholder = { Text("Quiero compartir mi ubicación") }, minLines = 2)
    OutlinedButton(onClick = { app.sendMessage(message); message = "" }, enabled = message.isNotBlank() && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Enviar consulta") }
    state.chat.takeLast(6).forEach { line -> Surface(color = if (line.fromUser) Color(0xFFE7ECDB) else Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (line.fromUser) "Vos" else "NIETO", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Forest)
            Text(line.text, fontSize = 19.sp, lineHeight = 28.sp)
        }
    } }
    HorizontalDivider(color = Color(0xFFD6DCD0))
    Text("Aprendamos algo", style = MaterialTheme.typography.titleLarge, color = Forest)
    Text("Compartir mi ubicación\npor WhatsApp", fontSize = 24.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold)
    Text("Vos elegís la conversación y hacés cada toque. Yo te acompaño y compruebo los cambios.")
    val guide = state.guide
    if (guide.stage in listOf(Stage.IDLE, Stage.CANCELLED)) {
        BigButton("Empezar esta guía", { app.startGuide(onReady = onWhatsApp) }, enabled = !state.busy)
    } else {
        Surface(color = Color(0xFFE8EDDA), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (guide.practice) "Práctica sin pistas" else "Tu próximo paso", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Forest)
                Text(guide.notice ?: guide.instruction)
                if (guide.stage == Stage.CONFIRM && guide.notice == null) BigButton("Sí, quiero compartir mi ubicación", { app.confirmShare(); onWhatsApp() })
                if (guide.stage == Stage.COMPLETE) BigButton("Practicar sin pistas", { app.startGuide(practice = true, onReady = onWhatsApp) }, enabled = !state.busy)
                else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { app.repeat() }, Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Repetir") }
                        OutlinedButton(onClick = { app.lost() }, Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Me perdí") }
                    }
                    OutlinedButton(onClick = onWhatsApp, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Volver a WhatsApp") }
                    TextButton(onClick = { app.cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Terminar la guía") }
                }
            }
        }
    }
    if (!state.accessibilityConnected || !app.settings.screenConsent) OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Activar guía en pantalla") }
    OutlinedButton(onClick = { app.requestHelp() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Pedir ayuda a mi familia") }
    Text("Perfil experimental: WhatsApp Android 2.26.* en español. Si no reconozco una pantalla, te lo digo. No envío ubicación desde NIETO.", fontSize = 15.sp, lineHeight = 22.sp)
}

@Composable private fun FamilyContent(app: NietoApp, state: AppState) {
    var code by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var revoke by remember { mutableStateOf(false) }
    Text("Cerca de los tuyos.", style = MaterialTheme.typography.headlineLarge, color = Forest)
    Text("Vos elegís quién te acompaña y qué puede saber. Tus conversaciones y tu pantalla siempre quedan fuera del grupo.")
    if (state.familyName == null) {
        Text("Unite a tu grupo", style = MaterialTheme.typography.titleLarge)
        Text("Pedile a tu familiar el código que generó en el portal NIETO Familia.")
        OutlinedTextField(value = code, onValueChange = { code = it.take(40) }, modifier = Modifier.fillMaxWidth(), label = { Text("Código de invitación") }, singleLine = true)
        PermissionRow("Compartir mi progreso", "Tareas completadas, práctica autónoma y tiempo acompañado.", progress) { progress = it }
        PermissionRow("Permitir solicitudes de ayuda", "Tu familia recibe una alerta cuando vos tocás Pedir ayuda.", help) { help = it }
        BigButton("Aceptar invitación y permisos", { app.acceptFamily(code, progress, help) }, enabled = code.isNotBlank() && !state.busy)
    } else {
        Text("Tu grupo: ${state.familyName}", style = MaterialTheme.typography.titleLarge)
        Text("Progreso: ${if (state.shareProgress) "compartido" else "privado"}\nSolicitudes de ayuda: ${if (state.allowHelp) "autorizadas" else "desactivadas"}")
        BigButton("Pedir ayuda ahora", { app.requestHelp() }, enabled = state.allowHelp && !state.busy)
        Text("Esta acción envía un aviso real al portal familiar. No realiza una llamada ni es un servicio de emergencias.", fontSize = 16.sp)
        OutlinedButton(onClick = { revoke = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Revocar acceso familiar") }
        Text("Para cambiar los permisos, revocá el acceso y aceptá una invitación nueva.", fontSize = 16.sp)
    }
    TextButton(onClick = { app.refreshFamily() }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Actualizar mi grupo") }
    if (revoke) AlertDialog(onDismissRequest = { revoke = false }, title = { Text("¿Revocar el acceso?") },
        text = { Text("Tu grupo dejará de recibir progreso y solicitudes de ayuda de este teléfono.") },
        confirmButton = { TextButton(onClick = { revoke = false; app.revokeFamily() }) { Text("Revocar acceso") } },
        dismissButton = { TextButton(onClick = { revoke = false }) { Text("Volver") } })
}

@Composable private fun SettingsContent(app: NietoApp, state: AppState, onConsent: () -> Unit, onAccessibility: () -> Unit) {
    var url by remember { mutableStateOf(app.settings.baseUrl) }
    var name by remember { mutableStateOf(app.settings.deviceName) }
    val screenConsent = app.settings.screenConsent
    Text("A tu manera.", style = MaterialTheme.typography.headlineLarge, color = Forest)
    Text("Guía en pantalla", style = MaterialTheme.typography.titleLarge)
    Text(if (state.accessibilityConnected && screenConsent) "Accesibilidad autorizada. La observación ocurre solo durante una guía activa." else "Elegí si querés que NIETO reconozca los controles de WhatsApp mientras te acompaña.")
    BigButton(if (screenConsent) "Abrir ajustes de accesibilidad" else "Conocer y activar la guía", if (screenConsent) onAccessibility else onConsent)
    PermissionRow("Mostrar burbuja NIETO", "Una ayuda a mano mientras usás WhatsApp.", state.overlayEnabled) { if (app.settings.screenConsent) app.setOverlay(it) else onConsent() }
    if (screenConsent) OutlinedButton(onClick = { app.cancel(); app.settings.screenConsent = false; app.setOverlay(false); app.status("Consentimiento revocado. Dejé de observar la pantalla. Podés deshabilitar también el servicio en los ajustes de Android.") }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Revocar observación de pantalla") }
    HorizontalDivider()
    Text("Conexión del prototipo", style = MaterialTheme.typography.titleLarge)
    Text("El equipo de la hackathon te indica la dirección. OpenARG se conecta desde el servidor; este teléfono no necesita su clave.", fontSize = 17.sp)
    OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text("Nombre de este teléfono") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Dirección del servidor") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    BigButton("Guardar y conectar", { app.saveServer(url, name) }, enabled = !state.busy)
    Text("Emulador: http://10.0.2.2:8787\nPor USB con adb reverse: http://127.0.0.1:8787\nEn celular por Wi-Fi: dirección LAN del equipo.", fontSize = 15.sp, lineHeight = 23.sp)
    Text("Privacidad", style = MaterialTheme.typography.titleLarge)
    Text("Las credenciales de instalación se cifran con Android Keystore. El chat vive en memoria y se envía al servidor para responder. El servidor recibe eventos de pasos y duración, nunca tu pantalla. No se solicita acceso a contactos, SMS, cámara ni ubicación.", fontSize = 17.sp)
    Text("Versión 0.1.0 · ${GuidanceEngine.PROFILE_VERSION}\nPrototipo experimental. La validación de pantallas en un teléfono real es necesaria antes de ampliar compatibilidad.", fontSize = 15.sp, lineHeight = 22.sp)
}

@Composable private fun PermissionRow(title: String, detail: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Column(Modifier.weight(1f)) { Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold); Text(detail, fontSize = 16.sp, lineHeight = 22.sp) }
    }
}
@Composable private fun BigButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) { Text(text, fontSize = 19.sp, lineHeight = 25.sp) }
}
