package org.nieto.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import org.nieto.assistant.flow.EmailDraft
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nieto.assistant.flow.GuidanceEngine
import org.nieto.assistant.flow.Stage
import org.nieto.assistant.entry.TinoEntryRoot
import org.nieto.assistant.ui.TinoTypography
import org.nieto.assistant.ui.TinoChatScreen

class MainActivity : ComponentActivity() {
    private val app get() = application as NietoApp
    private var recognizer: SpeechRecognizer? = null
    private var listening by mutableStateOf(false)
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginListening() else app.status("El micrófono quedó desactivado. Podés escribir tu consulta.")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NietoTheme { TinoEntryRoot(app) { onLogout -> NietoScreen(app, listening, ::toggleVoice, ::openWhatsApp, ::openEmail, ::openAccessibility, onLogout) } } }
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
    private fun openEmail(draft: EmailDraft?, compose: Boolean) {
        val uri = if (draft == null) Uri.parse("mailto:") else Uri.parse("mailto:${Uri.encode(draft.to)}?subject=${Uri.encode(draft.subject)}&body=${Uri.encode(draft.body)}")
        runCatching { startActivity(if (compose) Intent(Intent.ACTION_SENDTO, uri) else Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL)) }.onFailure { app.tellUser("No encontré una app de correo disponible. Podés abrir la que uses habitualmente y seguir la guía de TINO.") }
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

private val Forest = Color(0xFF006B64)
private val WarmWhite = Color(0xFFF3F6F6)
private val Ink = Color(0xFF101416)
private val Turquoise = Color(0xFF00D6C9)
@Composable private fun NietoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Turquoise, onPrimary = Ink, background = WarmWhite,
        surface = WarmWhite, onBackground = Ink, onSurface = Ink, secondaryContainer = Color(0xFFE3F9F6),
        onSecondaryContainer = Forest), typography = TinoTypography, content = content)
}

@Composable private fun NietoScreen(app: NietoApp, listening: Boolean, onVoice: () -> Unit, onWhatsApp: () -> Unit, onEmail: (EmailDraft?, Boolean) -> Unit, onAccessibility: () -> Unit, onLogout: () -> Unit) {
    val state by app.state.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var disclosure by remember { mutableStateOf(false) }
    BackHandler(enabled = tab != 0) { tab = 0 }
    val startFlow: (String) -> Unit = { flowId ->
        if (flowId == GuidanceEngine.FLOW_ID) { tab = 2; app.startGuide(onReady = onWhatsApp) }
        else { tab = 0; app.startTask(flowId) { if (flowId == "email-send") onEmail(app.state.value.emailDraft, true) else onWhatsApp() } }
    }
    val reopenTask: () -> Unit = { if (app.state.value.task?.flowId == "email-send") onEmail(null, false) else onWhatsApp() }
    if (tab == 0) {
        TinoChatScreen(app, state, listening, onVoice,
            onStartGuide = startFlow, onOpenTask = reopenTask,
            onFamily = { tab = 1; if (app.settings.token.isNotBlank()) app.refreshFamily() },
            onGuides = { tab = 2 }, onSettings = { tab = 3 }, onLogout = onLogout)
    } else {
        Scaffold(containerColor = WarmWhite, topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Image(painterResource(R.drawable.tino_logo), "TINO", Modifier.width(116.dp).height(48.dp))
                TextButton(onClick = { tab = 0 }) { Text("Volver al chat", color = Ink) }
            }
        }) { insets ->
            Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 800.dp).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Forest)
                    when (tab) {
                        1 -> FamilyContent(app, state)
                        2 -> GuideContent(app, state, onWhatsApp, onStartFlow = startFlow, onSettings = { tab = 3 })
                        else -> SettingsContent(app, state, onConsent = { disclosure = true }, onAccessibility)
                    }
                }
            }
        }
    }
    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false }, title = { Text("Vos decidís qué puedo ver") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Durante una guía, TINO identifica controles accesibles de WhatsApp para mostrarte dónde tocar y comprobar tus pasos.")
            Text("No toca botones ni escribe por vos. No hace capturas, no envía el árbol de pantalla y no lee campos de contraseña ni teclados. Las otras guías muestran instrucciones en la burbuja sin leer tus mensajes ni correos.")
            Text("La burbuja aparece con este permiso. Podés ocultarla o revocar el acceso en cualquier momento. Android te pedirá habilitar TINO en Accesibilidad.")
        } }, confirmButton = { TextButton(onClick = { app.settings.screenConsent = true; app.setOverlay(true); disclosure = false; onAccessibility() }) { Text("Acepto y abrir ajustes") } },
        dismissButton = { TextButton(onClick = { disclosure = false }) { Text("Ahora no") } })
}

@Composable private fun GuideContent(app: NietoApp, state: AppState, onWhatsApp: () -> Unit, onStartFlow: (String) -> Unit, onSettings: () -> Unit) {
    Text("Guías", style = MaterialTheme.typography.headlineLarge, color = Ink)
    BigButton("Crear un grupo de WhatsApp", { onStartFlow("whatsapp-group") }, enabled = !state.busy)
    BigButton("Enviar un correo", { onStartFlow("email-send") }, enabled = !state.busy)
    Text("Compartir ubicación", style = MaterialTheme.typography.titleLarge, color = Forest)
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
    OutlinedButton(onClick = { app.requestHelp() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Pedir ayuda a mi red") }
    Text("Perfil experimental: WhatsApp Android 2.26.* en español. Si no reconozco una pantalla, te lo digo. No envío ubicación desde TINO.", fontSize = 15.sp, lineHeight = 22.sp)
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
        Text("Pedile a una persona de confianza el código que generó en el portal TINO Red.")
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
    Text(if (state.accessibilityConnected && screenConsent) "Accesibilidad autorizada. La observación ocurre solo durante una guía activa." else "Elegí si querés que TINO reconozca los controles de WhatsApp mientras te acompaña.")
    BigButton(if (screenConsent) "Abrir ajustes de accesibilidad" else "Conocer y activar la guía", if (screenConsent) onAccessibility else onConsent)
    PermissionRow("Mostrar burbuja TINO", "Una ayuda a mano mientras usás WhatsApp.", state.overlayEnabled) { if (app.settings.screenConsent) app.setOverlay(it) else onConsent() }
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
