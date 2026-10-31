package org.nieto.assistant

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.service.voice.VoiceInteractionService
import androidx.activity.result.contract.ActivityResultContracts
import org.nieto.assistant.voice.TinoVoiceInteractionService
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
    private val microphonePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) activateVoice() else app.status("Para responder a Hola Tino, necesitás autorizar el micrófono.")
    }
    private val assistantRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { app.refreshAssistantRole(); app.restoreWakeListening() }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NietoTheme { MainScreen(app,::openAccessibility,::openVoice,::openFamily,::chooseAssistant) } }
    }
    override fun onResume() {
        super.onResume(); app.surfaceVisible("main",true); app.refreshAssistantRole()
        val enabled=Settings.Secure.getString(contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component=ComponentName(this,org.nieto.assistant.screen.NietoAccessibilityService::class.java)
        app.screenConnection(enabled.split(':').any { ComponentName.unflattenFromString(it)==component })
    }
    override fun onStop() { app.surfaceVisible("main",false); super.onStop() }
    override fun onDestroy() { app.surfaceVisible("main",false); super.onDestroy() }
    private fun openAccessibility() { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    private fun openVoice() {
        if (!app.state.value.accessibilityConnected) { app.status("Habilitá TINO en Accesibilidad para señalar los controles."); openAccessibility(); return }
        if (!VoiceInteractionService.isActiveService(this,ComponentName(this,TinoVoiceInteractionService::class.java))) { chooseAssistant(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermissions.launch(if(Build.VERSION.SDK_INT>=33) arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.RECORD_AUDIO))
        } else activateVoice()
    }
    private fun activateVoice() {
        app.startWakeListening()
        if (app.state.value.wakeEnabled) startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
    }
    private fun chooseAssistant() {
        if (Build.VERSION.SDK_INT>=29) {
            val roles=getSystemService(RoleManager::class.java)
            if(roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) { assistantRole.launch(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)); return }
        }
        assistantRole.launch(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
    }
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
@Composable private fun MainScreen(app:NietoApp,onAccessibility:()->Unit,onVoice:()->Unit,onFamily:()->Unit,onAssistant:()->Unit) {
    val state by app.state.collectAsState()
    var settings by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var images by remember { mutableStateOf(false) }
    LaunchedEffect(settings) { if(settings) app.refreshDeviceSettings() }
    Column(Modifier.fillMaxSize().background(Cream).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Text("TINO",fontSize=30.sp,fontWeight=FontWeight.Bold,color=Forest)
            TextButton(onClick={settings=!settings}) { Text(if(settings) "Volver" else "Configuración") }
        }
        if(settings) SettingsContent(app,state,{consent=true},onAccessibility) else {
            Text("Decí «Hola Tino». Estoy para ayudarte.",fontSize=34.sp,lineHeight=42.sp,fontWeight=FontWeight.SemiBold,color=Forest)
            Text("Después de esta configuración, usá Gmail, WhatsApp o la aplicación que necesites. Decí Hola Tino y hablá después de la señal. TINO te responde por voz sin abrir una pantalla de chat.")
            Text("Configuración inicial",fontWeight=FontWeight.Bold,fontSize=23.sp)
            Text(if(app.settings.token.isBlank()) "1. Falta conectar esta tablet al servicio." else "1. Tablet conectada al servicio.")
            if(app.settings.token.isBlank()) BigButton("Conectar tablet",{settings=true})
            Text(if(state.accessibilityConnected) "2. Accesibilidad habilitada." else "2. Permití observar y señalar los controles.")
            if(!state.accessibilityConnected) BigButton("Habilitar Accesibilidad",onAccessibility)
            Text(if(state.assistantSelected) "3. TINO es el asistente predeterminado." else "3. Elegí TINO como asistente predeterminado.")
            if(!state.assistantSelected) BigButton("Elegir asistente TINO",onAssistant)
            if(state.wakeEnabled) {
                Text(state.wakeStatus,fontWeight=FontWeight.Bold,color=Forest)
                Text("La notificación permite terminar la conversación. Hola Tino sigue disponible para otra consulta.")
                BigButton("Apagar TINO",{app.handleWakeControl(org.nieto.assistant.voice.WakeControlAction.FULL_OFF)})
                Text("Apagar TINO desactiva Hola Tino. Para volver a usarlo, habilitalo aquí.",fontSize=16.sp)
            } else BigButton("Habilitar Hola Tino",{
                if(app.settings.openAiConsent && app.settings.remoteScreenConsent) onVoice() else consent=true
            })
            Text("Tras 30 segundos sin actividad, TINO olvida la conversación y espera un nuevo Hola Tino. Android muestra el uso del micrófono.",fontSize=16.sp)
            OutlinedButton(onClick=onFamily,modifier=Modifier.fillMaxWidth()) {Text("Configuración familiar")}
        }
        if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.status,fontSize=17.sp,color=Forest)
        if(state.ttsNotice.isNotBlank()) Text(state.ttsNotice,fontSize=16.sp)
        Text("TINO · "+BuildConfig.VERSION_NAME+" · Configuración del asistente",fontSize=14.sp,color=Forest)
    }
    if(consent) AlertDialog(onDismissRequest={consent=false},title={Text("Permitir Hola Tino y asistencia por voz")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("TINO reconocerá Hola Tino en la tablet, sin enviar esa escucha a Internet. Mantendrá una notificación de micrófono y podrás detenerlo. Esta elección se conserva para que Android pueda restaurar el asistente después de reiniciar.")
        Text("Después de llamarlo, tu consulta de voz y los controles de la pantalla autorizada se envían a OpenAI a través del servidor para responder y señalar dónde tocar. Las pantallas sensibles se ocultan. No digas contraseñas ni códigos.")
        Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(checked=images,onCheckedChange={images=it},enabled=Build.VERSION.SDK_INT>=30); Text("Permitir también capturas con campos ocultos",fontSize=16.sp) }
        Text("TINO no toca botones ni crea pantallas dentro de tus aplicaciones. No se guarda un historial de conversaciones ni capturas. La respuesta usa voz generada por IA.")
    }},confirmButton={TextButton(onClick={
        app.settings.remoteScreenConsent=true;app.settings.screenImagesConsent=images && Build.VERSION.SDK_INT>=30
        app.settings.openAiConsent=true;app.settings.screenConsent=true
        consent=false;onVoice()
    }) {Text("Permitir y configurar")}},dismissButton={TextButton(onClick={consent=false}) {Text("Cancelar")}})
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
    Text("La conexión de producción usa HTTPS y funciona sin una computadora local encendida.",fontSize=15.sp)
    Text("Sólo la credencial NIETO queda cifrada en este teléfono. Las claves de OpenAI y OpenARG permanecen en el servidor. Android 11+ puede aportar una captura autorizada; Android 8–10 aporta controles accesibles.",fontSize=16.sp)
}
@Composable private fun BigButton(text:String,onClick:()->Unit,enabled:Boolean=true) {
    Button(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=58.dp),shape=RoundedCornerShape(16.dp),contentPadding=PaddingValues(horizontal=20.dp,vertical=16.dp)) {Text(text,fontSize=19.sp,lineHeight=25.sp)}
}
