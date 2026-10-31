package org.nieto.assistant

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nieto.assistant.family.FamilyActivity
import org.nieto.assistant.flow.Stage
import org.nieto.assistant.voice.VoiceInputActivity

class MainActivity : ComponentActivity() {
    private val app get()=application as NietoApp
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NietoTheme { MainScreen(app,::openAccessibility,::openVoice,::openFamily,::openWhatsApp) } }
    }
    override fun onResume() {
        super.onResume(); app.surfaceVisible("main",true)
        val enabled=Settings.Secure.getString(contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        app.screenConnection(enabled.split(':').any { it.contains("org.nieto.assistant/") })
    }
    override fun onStop() { app.surfaceVisible("main",false); super.onStop() }
    override fun onDestroy() { app.surfaceVisible("main",false); super.onDestroy() }
    private fun openAccessibility() { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    private fun openVoice() { app.prepareVoice { startActivity(Intent(this,VoiceInputActivity::class.java)) } }
    private fun openFamily() { startActivity(Intent(this,FamilyActivity::class.java)) }
    private fun openWhatsApp() { packageManager.getLaunchIntentForPackage("com.whatsapp")?.let(::startActivity) ?: app.status("WhatsApp no está instalado.") }
}

private val Forest=Color(0xFF264C3B)
private val Cream=Color(0xFFFAF8F2)
@Composable private fun NietoTheme(content:@Composable ()->Unit) {
    MaterialTheme(colorScheme=lightColorScheme(primary=Forest,onPrimary=Color.White,background=Cream,surface=Cream,onSurface=Color(0xFF242E28)),
        typography=Typography(bodyLarge=androidx.compose.ui.text.TextStyle(fontSize=20.sp,lineHeight=29.sp),bodyMedium=androidx.compose.ui.text.TextStyle(fontSize=18.sp,lineHeight=26.sp),
            labelLarge=androidx.compose.ui.text.TextStyle(fontSize=18.sp,fontWeight=FontWeight.SemiBold)),content=content)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MainScreen(app:NietoApp,onAccessibility:()->Unit,onVoice:()->Unit,onFamily:()->Unit,onWhatsApp:()->Unit) {
    val state by app.state.collectAsState()
    var settings by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var images by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var localGuide by remember { mutableStateOf(false) }
    LaunchedEffect(settings) { if(settings) app.refreshDeviceSettings() }
    LaunchedEffect(state.assistanceEnabled) { if(!state.assistanceEnabled) message="" }
    Scaffold(containerColor=Cream,topBar={ Row(Modifier.fillMaxWidth().statusBarsPadding().padding(24.dp),verticalAlignment=Alignment.CenterVertically) {
        Text("N",color=Cream,fontSize=30.sp,fontWeight=FontWeight.Bold,modifier=Modifier.background(Forest,RoundedCornerShape(15.dp)).padding(horizontal=15.dp,vertical=8.dp))
        Spacer(Modifier.width(14.dp)); Column { Text("NIETO",fontSize=25.sp,fontWeight=FontWeight.Bold,color=Forest); Text("Acá, con vos.",fontSize=16.sp) }
    } },bottomBar={ NavigationBar {
        NavigationBarItem(selected=!settings,onClick={settings=false},icon={Text("◎",fontSize=25.sp)},label={Text("Asistencia")})
        NavigationBarItem(selected=false,onClick=onFamily,icon={Text("♡",fontSize=25.sp)},label={Text("Familia")})
        NavigationBarItem(selected=settings,onClick={settings=true},icon={Text("☷",fontSize=25.sp)},label={Text("Ajustes")})
    } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(!settings) {
                Text("Tu teléfono.\nUn paso a la vez.",fontSize=34.sp,lineHeight=39.sp,fontWeight=FontWeight.SemiBold,color=Forest)
                Text("Contame qué querés hacer. NIETO mira la pantalla autorizada y te explica dónde tocar. Cada acción la hacés vos.")
                if(!state.assistanceEnabled) BigButton("Activar acompañamiento",{
                    if(app.settings.remoteScreenConsent && app.settings.openAiConsent) app.enableAssistance() else consent=true
                }) else {
                    Text("Acompañamiento activo",fontWeight=FontWeight.Bold,color=Forest)
                    BigButton("Hablar con NIETO",onVoice,enabled=!state.busy && !state.voice.active)
                    Text("La ventana de voz deja visible tu app. El micrófono sólo se activa al tocar Hablar y se apaga al salir de esa ventana.",fontSize=15.sp,lineHeight=22.sp)
                    OutlinedTextField(value=message,onValueChange={message=it.take(1000)},label={Text("O escribí tu tarea")},modifier=Modifier.fillMaxWidth().onFocusChanged { if(it.isFocused) app.selectWrittenMode() },minLines=2)
                    OutlinedButton(onClick={val sent=message.trim();app.sendMessage(sent,onSubmitted={accepted -> if(accepted && message.trim()==sent) message=""})},enabled=message.isNotBlank() && !state.busy && !state.voice.active,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) { Text("Enviar consulta sin audio") }
                    Surface(color=Color(0xFFE8EDDA),shape=RoundedCornerShape(18.dp)) { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("Tu paso actual",fontSize=15.sp,fontWeight=FontWeight.Bold,color=Forest)
                        Text(state.voice.partialText.ifBlank { state.answer },fontSize=20.sp,lineHeight=29.sp)
                        Text(state.voice.message,fontSize=15.sp)
                    } }
                    Text("Abrí la app donde necesitás ayuda y tocá la burbuja N. Podés hablar, escribir y ver el próximo paso sin perder esa pantalla. No se guarda un historial.",fontSize=17.sp)
                    if(!state.accessibilityConnected) BigButton("Habilitar burbuja en Accesibilidad",onAccessibility)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={app.repeatAssistance()},modifier=Modifier.weight(1f)) { Text("Repetir") }
                        OutlinedButton(onClick={app.lostAssistance()},modifier=Modifier.weight(1f)) { Text("Me perdí") }
                    }
                    OutlinedButton(onClick={app.stopAssistance()},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) { Text("Apagar asistencia y borrar tarea") }
                }
                BigButton("Abrir Familia",onFamily)
                HorizontalDivider()
                TextButton(onClick={localGuide=!localGuide}) { Text(if(localGuide) "Ocultar guía local opcional" else "Guía local opcional: ubicación por WhatsApp") }
                if(localGuide) {
                    Text("Una práctica preparada, separada de la asistencia por IA. La guía comprueba controles locales compatibles; no toca botones.",fontSize=16.sp)
                    BigButton("Empezar guía local",{app.stopAssistance();app.startGuide(onReady=onWhatsApp)},enabled=!state.busy)
                    Text(state.guide.notice ?: state.guide.instruction)
                    if(state.guide.stage==Stage.CONFIRM) BigButton("Volver a WhatsApp para confirmar",onWhatsApp)
                    if(state.guide.reportAvailable) BigButton("Revisar y reportar desde WhatsApp",onWhatsApp)
                    if(state.guide.completionSource=="self_reported") {
                        Text("Resultado reportado por vos. NIETO no verificó un mensaje nuevo ni acredita autonomía.",fontSize=16.sp)
                        BigButton("Practicar sin pistas",{app.startGuide(practice=true,onReady=onWhatsApp)},enabled=!state.busy)
                    }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={app.repeat()},modifier=Modifier.weight(1f)) {Text("Repetir")}
                        OutlinedButton(onClick={app.lost()},modifier=Modifier.weight(1f)) {Text("Me perdí")}
                    }
                    OutlinedButton(onClick={app.cancel()}) { Text("Cerrar guía local") }
                }
            } else SettingsContent(app,state,{consent=true},onAccessibility)
            Surface(color=Color(0xFFEDEFE5),shape=RoundedCornerShape(16.dp)) { Text(state.status,Modifier.padding(16.dp),fontSize=17.sp,lineHeight=24.sp) }
            if(state.ttsNotice.isNotBlank()) Text(state.ttsNotice,fontSize=17.sp,color=Forest)
            if(state.telemetryPending>0) Text("Progreso pendiente de sincronizar: ${state.telemetryPending}. Se conserva cifrado al cerrar la app.",fontSize=15.sp)
            if(state.telemetryError.isNotBlank()) Text(state.telemetryError,fontSize=16.sp)
            if(state.telemetryPaused>0) Text("${state.telemetryPaused} eventos están en pausa. Conservan el servidor y acceso originales; no se trasladan a otra identidad.",fontSize=15.sp)
            if(state.telemetryPending!=0) OutlinedButton(onClick={app.retryTelemetry()}) {Text("Reintentar con los accesos originales")}
            Text("NIETO · Labitconf 2026 · ${org.nieto.assistant.BuildConfig.VERSION_NAME}\nValidación física pendiente en cada teléfono.",fontSize=14.sp,color=Forest)
        }
    }
    if(consent) AlertDialog(onDismissRequest={consent=false},title={Text("Permitir asistencia con OpenAI")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Mientras actives el acompañamiento, NIETO enviará a OpenAI a través del servidor tus consultas, la voz que grabes al tocar Hablar y los nombres y posiciones de controles de la pantalla actual.")
        Text("También podés autorizar capturas para entender mejor la pantalla. Pueden contener información personal visible. Se ocultan campos editables y se bloquean pantallas sensibles reconocidas; evitá abrir contenido privado durante la asistencia.")
        Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(checked=images,onCheckedChange={images=it},enabled=Build.VERSION.SDK_INT>=30); Text(if(Build.VERSION.SDK_INT>=30) "Autorizar también capturas de pantalla" else "Android 8–10: sólo controles; sin capturas",fontSize=16.sp) }
        Text("NIETO propone un paso y puede resaltarlo; nunca toca ni escribe por vos. Las respuestas pueden equivocarse. La voz es generada por IA. No compartas contraseñas ni códigos.")
        Text("No se guardan capturas ni historial de consultas. Apagar o revocar cierra la conexión y elimina el contexto de tarea. Los bloques ya enviados no pueden retirarse. El permiso local anterior no autoriza este envío.")
    }},confirmButton={TextButton(onClick={
        app.settings.remoteScreenConsent=true;app.settings.screenImagesConsent=images && Build.VERSION.SDK_INT>=30
        app.settings.openAiConsent=true;app.settings.screenConsent=true
        consent=false;app.enableAssistance()
    }) {Text("Acepto y activar")}},dismissButton={TextButton(onClick={consent=false}) {Text("Ahora no")}})
}
@Composable private fun SettingsContent(app:NietoApp,state:AppState,onConsent:()->Unit,onAccessibility:()->Unit) {
    var url by remember {mutableStateOf(app.settings.baseUrl)}
    var name by remember {mutableStateOf(app.settings.deviceName)}
    var enrollmentCode by remember {mutableStateOf("")}
    var territoriesOpen by remember {mutableStateOf(false)}
    Text("Vos decidís.",fontSize=32.sp,color=Forest,fontWeight=FontWeight.SemiBold)
    Text("Pantalla y voz",fontSize=24.sp,color=Forest)
    Text(if(app.settings.remoteScreenConsent) "Permiso remoto activo. La observación ocurre sólo mientras encendés la asistencia." else "El permiso local anterior no permite enviar tu pantalla. Revisá y aceptá el nuevo consentimiento.")
    BigButton("Revisar permisos de asistencia",onConsent)
    BigButton("Abrir ajustes de Accesibilidad",onAccessibility)
    if(app.settings.remoteScreenConsent) OutlinedButton(onClick={app.revokeOpenAiConsent()},modifier=Modifier.fillMaxWidth()) {Text("Revocar pantalla, voz y consultas")}
    HorizontalDivider()
    Text("Métricas institucionales",fontSize=24.sp,color=Forest)
    Text("Opcional e independiente de Familia. Permite estadísticas agregadas de actividad y dificultades de los últimos 30 días. No incluye tu pantalla, consultas ni respuestas. Se desactiva por defecto.",fontSize=17.sp)
    if(state.deviceSettingsLoaded) Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(checked=state.institutionMetricsConsent,onCheckedChange={app.setInstitutionMetricsConsent(it)},enabled=!state.busy); Text("Autorizar métricas agregadas",fontSize=17.sp) }
    else { Text("Permiso vigente sin consultar. Conectá el teléfono o actualizá para conocerlo.",fontSize=16.sp); OutlinedButton(onClick={app.refreshDeviceSettings()},enabled=!state.busy) { Text("Consultar permiso vigente") } }
    Text("Jurisdicción declarada",fontWeight=FontWeight.SemiBold)
    Text("Elegila vos; NIETO no usa GPS ni detecta tu ubicación. Sirve para agrupar estadísticas del territorio autorizado.",fontSize=16.sp)
    Box {
        OutlinedButton(onClick={territoriesOpen=true},enabled=!state.busy,modifier=Modifier.fillMaxWidth()) { Text(org.nieto.assistant.data.Territories.names[state.territoryId] ?: "Elegir jurisdicción") }
        DropdownMenu(expanded=territoriesOpen,onDismissRequest={territoriesOpen=false},modifier=Modifier.heightIn(max=340.dp)) {
            org.nieto.assistant.data.Territories.names.forEach { (id,title) -> DropdownMenuItem(text={Text(title)},onClick={territoriesOpen=false;app.saveTerritory(id)}) }
        }
    }
    HorizontalDivider()
    Text("Conexión del teléfono",fontSize=24.sp,color=Forest)
    OutlinedTextField(value=name,onValueChange={name=it.take(60)},label={Text("Nombre del teléfono")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    OutlinedTextField(value=url,onValueChange={url=it},label={Text("Servidor NIETO")},modifier=Modifier.fillMaxWidth(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
    OutlinedTextField(value=enrollmentCode,onValueChange={enrollmentCode=it.filter(Char::isLetterOrDigit).uppercase(java.util.Locale.ROOT).take(12)},
        label={Text("Código de conexión del equipo")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    Text("El equipo genera este código de un solo uso desde Conexión. Vence a los 15 minutos. Es distinto del código familiar y no es una clave de OpenAI.",fontSize=16.sp)
    BigButton("Guardar y conectar",{app.saveServer(url,name,enrollmentCode,onConnected={enrollmentCode=""})},enabled=!state.busy)
    if(state.deviceSettingsLoaded) Text(if(state.realtimeApproved) "Asistencia autorizada para este teléfono." else "La asistencia espera la autorización del equipo.",fontSize=16.sp)
    if(app.settings.deviceId.isNotBlank()) Text("Identificador de este teléfono: ${app.settings.deviceId}",fontSize=14.sp)
    Text("Emulador: http://10.0.2.2:8787\nUSB con adb reverse: http://127.0.0.1:8787\nEn Wi-Fi: dirección LAN del equipo.",fontSize=15.sp)
    Text("Sólo la credencial NIETO queda cifrada en este teléfono. Las claves de OpenAI y OpenARG permanecen en el servidor. Android 11+ puede aportar una captura autorizada; Android 8–10 aporta controles accesibles.",fontSize=16.sp)
}
@Composable private fun BigButton(text:String,onClick:()->Unit,enabled:Boolean=true) {
    Button(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=58.dp),shape=RoundedCornerShape(16.dp),contentPadding=PaddingValues(horizontal=20.dp,vertical=16.dp)) {Text(text,fontSize=19.sp,lineHeight=25.sp)}
}
