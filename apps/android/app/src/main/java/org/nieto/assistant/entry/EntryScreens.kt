package org.nieto.assistant.entry

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.nieto.assistant.NietoApp
import org.nieto.assistant.R

private val Teal = Color(0xFF006B64)
private val Mint = Color(0xFFE3F9F6)
private val Ink = Color(0xFF101416)
private val Muted = Color(0xFF4C5B61)
private val Background = Color(0xFFF3F6F6)

@Composable fun TinoEntryRoot(app: NietoApp, vm: EntryViewModel = viewModel(), assistant: @Composable () -> Unit) {
    val s by vm.state.collectAsState()
    val device by app.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionEpoch by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permissionEpoch++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val microphoneGranted = remember(permissionEpoch, s.preferences) { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    var disclosure by rememberSaveable { mutableStateOf(false) }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.preference("microphone", granted); if (!granted) vm.message("Podés usar TINO por chat sin el micrófono.") }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.preference("notifications", granted); if (!granted) vm.message("Podés continuar sin notificaciones.") }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); vm.photo(uri.toString()) }.onFailure { vm.message("No pude conservar esa foto. Podés elegir otra o continuar sin foto.") }
    }
    BackHandler(enabled = s.page != EntryPage.WELCOME) { vm.back() }
    if (s.page == EntryPage.HOME && s.role == AccountRole.USER) {
        Column(Modifier.fillMaxSize().background(Background).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { vm.move(EntryPage.WELCOME) }) { Text("← Mi espacio") }
                TextButton(onClick = { vm.move(EntryPage.CONNECTION) }) { Text("Conexión") }
            }
            Box(Modifier.weight(1f)) { assistant() }
        }
        return
    }
    Scaffold(containerColor = Background, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Image(painterResource(R.drawable.tino_logo), "TINO", Modifier.width(110.dp).height(46.dp))
            TextButton(onClick = { vm.move(EntryPage.CONNECTION) }) { Text("Conexión", color = Teal, fontSize = 15.sp) }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val scroll = rememberScrollState()
            LaunchedEffect(s.page, s.question) { scroll.scrollTo(0) }
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(scroll).imePadding().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (s.page != EntryPage.WELCOME) TextButton(onClick = vm::back, enabled = !s.busy, contentPadding = PaddingValues(0.dp)) { Text("← Volver", color = Teal) }
                    else Text("BIENVENIDA", color = Teal, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    if (s.page != EntryPage.WELCOME) Text(s.role.label, color = Muted, fontSize = 14.sp)
                }
                if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Teal)
                s.error?.let { Banner(it, true) }; s.message?.let { Banner(it) }
                when (s.page) {
                    EntryPage.WELCOME -> {
                        Title("Tu teléfono,\na tu manera.", "Elegí cómo querés usar TINO. Una ayuda que se adapta a vos.")
                        AccountRole.entries.forEachIndexed { index, role ->
                            Surface(onClick = { vm.start(role) }, enabled = !s.busy, color = Color.White, shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDCE5E4)), modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(20.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Surface(shape = RoundedCornerShape(16.dp), color = Mint) { Text("0${index + 1}", Modifier.padding(13.dp), color = Teal, fontWeight = FontWeight.Bold) }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(role.label, color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Bold); Text(role.subtitle, color = Muted, fontSize = 16.sp, lineHeight = 23.sp) }
                                    Text("→", color = Teal, fontSize = 24.sp)
                                }
                            }
                        }
                        TextButton(onClick = vm::login, enabled = !s.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Ya tengo cuenta · Ingresar", color = Teal) }
                        OutlinedButton(onClick = vm::guest, enabled = !s.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp)) { Text("Usar el asistente sin cuenta", color = Teal) }
                        Text("Ayuda sin etiquetas. Acceso sin barreras.", color = Muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                    EntryPage.FAMILY_CHOICE -> {
                        Title("Tu red, más cerca.", "Elegí si querés sumarte a un grupo o empezar uno nuevo.")
                        ChoiceCard("Ingresar a un grupo familiar", "Identificá el grupo por el correo del administrador. Verificamos tu propio correo para ingresar.") { vm.family("join") }
                        ChoiceCard("Empezar un grupo familiar", "Buscá a la persona que querés acompañar y creá tu perfil de apoyo.") { vm.family("create") }
                    }
                    EntryPage.FIND_PERSON -> {
                        Title("¿A quién querés acompañar?", "Ingresá el usuario, correo o teléfono con el que la persona creó su cuenta.")
                        EntryField("Usuario, correo o teléfono", s.field("targetIdentifier"), { vm.field("targetIdentifier", it) }, enabled = !s.busy)
                        Banner("La solicitud requiere aceptación. Encontrar una cuenta no habilita ver sus datos ni su pantalla.")
                        Primary("Continuar con mi perfil", !s.busy, vm::findPerson)
                    }
                    EntryPage.JOIN_FAMILY -> {
                        Title("Sumate a tu grupo.", "El correo del administrador identifica la red. El código se envía a tu correo, sin contraseña.")
                        EntryField("Correo del administrador", s.field("adminEmail"), { vm.field("adminEmail", it) }, KeyboardType.Email, enabled = !s.busy)
                        EntryField("Tu correo", s.field("email"), { vm.field("email", it) }, KeyboardType.Email, enabled = !s.busy)
                        CheckRow("Es mi primera vez en TINO", s.firstTime, vm::firstTime)
                        Primary("Solicitar código de acceso", !s.busy, vm::requestCode)
                    }
                    EntryPage.LOGIN -> {
                        Title("Volvé a tu espacio.", "Ingresá con un código de un solo uso. No necesitás contraseña.")
                        Column(Modifier.selectableGroup()) { AccountRole.entries.forEach { role -> RadioRow(role.label, role == s.role, { vm.loginRole(role) }, !s.busy) } }
                        EntryField("Tu correo", s.field("email"), { vm.field("email", it) }, KeyboardType.Email, enabled = !s.busy)
                        Primary("Enviar código", !s.busy, vm::requestCode)
                    }
                    EntryPage.CODE -> {
                        Title("Un código.\nSin contraseña.", if (s.delivery == "operator") "Modo de prueba local: pedile al equipo el código que aparece en la terminal del servidor. No se envió un correo." else "Ingresá el código enviado a ${s.field("email")}.")
                        EntryField("Código de seis dígitos", s.code, vm::code, KeyboardType.NumberPassword, enabled = !s.busy)
                        Primary("Verificar código", !s.busy && s.code.length == 6, vm::verify)
                        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                        LaunchedEffect(s.resendAt) { while (now < s.resendAt) { delay(1000); now = System.currentTimeMillis() } }
                        TextButton(onClick = vm::requestCode, enabled = !s.busy && now >= s.resendAt) { Text(if (now < s.resendAt) "Reenviar en ${(s.resendAt - now) / 1000 + 1} s" else "Reenviar código") }
                        TextButton(onClick = vm::back, enabled = !s.busy) { Text("Cambiar correo") }
                    }
                    EntryPage.PROFILE -> {
                        Title("Primero,\nconozcámonos.", when (s.role) { AccountRole.USER -> "Contanos cómo querés que te llamemos. La foto y los datos adicionales son opcionales."; AccountRole.FAMILY -> "Estos datos son tuyos, como persona que acompaña. La otra persona conserva sus permisos."; else -> "Contanos quiénes son y dónde trabajan." })
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ProfilePhoto(s.field("photo"), s.field("name"))
                            Column { TextButton(onClick = { photo.launch(arrayOf("image/*")) }, enabled = !s.busy) { Text(if (s.field("photo").isBlank()) "Agregar foto (opcional)" else "Cambiar foto") }; if (s.field("photo").isNotBlank()) TextButton(onClick = { vm.photo("") }) { Text("Quitar foto") } }
                        }
                        EntryField("Nombre y apellido", s.field("name"), { vm.field("name", it) }, enabled = !s.busy)
                        EntryField("Tu correo", s.field("email"), { vm.field("email", it) }, KeyboardType.Email, enabled = !s.busy && app.settings.read("accountSession").isBlank())
                        EntryField("Nombre de usuario (opcional)", s.field("username"), { vm.field("username", it) }, enabled = !s.busy)
                        EntryField("Teléfono (opcional)", s.field("phone"), { vm.field("phone", it) }, KeyboardType.Phone, enabled = !s.busy)
                        TerritorySelector(s.field("territoryId")) { vm.field("territoryId", it) }
                        if (s.role == AccountRole.INSTITUTION) {
                            EntryField("Nombre de la institución", s.field("organization"), { vm.field("organization", it) }, enabled = !s.busy)
                            EntryField("Tu función (opcional)", s.field("position"), { vm.field("position", it) }, enabled = !s.busy)
                        }
                        Primary(if (app.settings.read("accountSession").isBlank()) "Verificar mi correo" else "Continuar", !s.busy, vm::submitProfile)
                    }
                    EntryPage.PERMISSIONS -> {
                        Title("Vos elegís\nlos permisos.", "Podés continuar sin habilitar todo. Cada permiso se puede cambiar después.")
                        if (s.role != AccountRole.INSTITUTION) {
                            Permission("Micrófono", "Para pedir ayuda por voz. Solo se activa cuando vos lo elegís.", microphoneGranted) { if (it) microphone.launch(Manifest.permission.RECORD_AUDIO) else { vm.message("Podés revocar el micrófono desde los permisos de TINO en Android."); context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) } }
                            Permission("Notificaciones", "Para los avisos que hayas elegido.", if (Build.VERSION.SDK_INT >= 33) context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else s.preferences["notifications"] == true) { value -> if (value && Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.preference("notifications", value) }
                            if (s.role == AccountRole.USER) Permission("Guía en pantalla", "Reconocer controles de WhatsApp durante una guía. Android te pedirá autorizar Accesibilidad.", device.accessibilityConnected && app.settings.screenConsent) { enabled -> if (enabled) disclosure = true else { app.cancel(); app.settings.screenConsent = false; app.setOverlay(false); vm.preference("screen", false) } }
                            else Permission("Solicitar resúmenes de acompañamiento", "La otra persona acepta o rechaza cada permiso. Esto no habilita acceso a sus datos.", s.preferences["support"] == true) { vm.preference("support", it) }
                        } else Banner("Tu institución consulta datos agregados por territorio. No accede a conversaciones, capturas ni ubicaciones individuales. El acceso debe estar autorizado por el equipo.")
                        Primary("Personalizar mi experiencia", !s.busy) { vm.move(EntryPage.QUESTIONS) }
                    }
                    EntryPage.QUESTIONS -> {
                        val questions = questionsFor(s.role); val question = questions[s.question]
                        Text("PREGUNTA ${s.question + 1} DE ${questions.size}", color = Teal, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        LinearProgressIndicator(progress = { (s.question + 1).toFloat() / questions.size }, modifier = Modifier.fillMaxWidth(), color = Teal, trackColor = Mint)
                        Title(question.title, "Elegí la opción que más se acerque a lo que necesitás. Podés cambiarla después.")
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) { question.options.forEachIndexed { index, option -> RadioRow("${'A' + index}. $option", s.answers[question.id] == index, { vm.answer(index) }) } }
                        Primary(if (s.question == questions.lastIndex) "Revisar mi configuración" else "Siguiente pregunta", s.answers[question.id] != null, vm::nextQuestion)
                    }
                    EntryPage.REVIEW -> {
                        Title("Así va a\nacompañarte TINO.", "Revisá tus elecciones antes de guardarlas.")
                        Surface(color = Mint, shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(s.field("name"), fontWeight = FontWeight.Bold); Text(s.field("email"), fontSize = 16.sp); Text(s.role.label, color = Teal); TextButton(onClick = { vm.move(EntryPage.PROFILE) }) { Text("Editar datos") } } }
                        questionsFor(s.role).forEachIndexed { index, q -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(q.title, color = Muted, fontSize = 16.sp); Text(q.options[s.answers[q.id] ?: 0], fontSize = 18.sp); TextButton(onClick = { vm.editQuestion(index) }) { Text("Cambiar respuesta") }; HorizontalDivider() } }
                        if (s.role == AccountRole.FAMILY) Banner("El vínculo y los permisos requieren aceptación de la persona acompañada. Tu cuenta no controla su teléfono.")
                        CheckRow("Confirmo mis datos y entiendo que puedo cambiar mis preferencias y revocar permisos.", s.accepted, vm::accept)
                        Primary("Guardar y empezar", s.accepted && !s.busy, vm::finish)
                    }
                    EntryPage.CONNECTION -> ConnectionScreen(app.settings.baseUrl, s.busy, vm::connect)
                    EntryPage.HOME -> RoleDashboard(s, vm)
                }
                if (s.page !in listOf(EntryPage.WELCOME, EntryPage.HOME, EntryPage.CONNECTION)) TextButton(onClick = { vm.move(EntryPage.WELCOME) }, enabled = !s.busy, modifier = Modifier.fillMaxWidth()) { Text("Volver a la bienvenida", color = Muted, fontSize = 15.sp) }
            }
        }
    }
    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false }, title = { Text("Vos decidís qué puedo ver") }, text = { Text("Durante una guía, TINO identifica controles accesibles de WhatsApp para mostrarte dónde tocar. No hace capturas, no toca botones por vos y no envía el árbol de pantalla al servidor. Ignora contraseñas y campos de texto. Podés revocar este permiso cuando quieras.") }, confirmButton = { TextButton(onClick = { app.settings.screenConsent = true; app.setOverlay(true); vm.preference("screen", true); disclosure = false; context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Acepto y abrir ajustes") } }, dismissButton = { TextButton(onClick = { disclosure = false }) { Text("Ahora no") } })
}

@Composable private fun RoleDashboard(s: EntryState, vm: EntryViewModel) {
    Title(if (s.role == AccountRole.FAMILY) "Tu red,\nmás cerca." else "Entender para\nacompañar mejor.", if (s.role == AccountRole.FAMILY) "Solo ves lo que cada persona autoriza." else "Indicadores agregados de TINO y contexto territorial de OpenARG.")
    Primary("Actualizar datos", !s.busy, vm::dashboard)
    if (s.role == AccountRole.FAMILY) {
        s.dashboard?.optJSONObject("group")?.optString("name")?.let { Text(it, fontWeight = FontWeight.Bold) }
        OutlinedButton(onClick = vm::invitation, enabled = !s.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Crear invitación para mi red") }
        if (s.invitationCode.isNotBlank()) Banner("Código de invitación: ${s.invitationCode}\nLa persona lo ingresa en Familia y elige qué compartir.")
        val members = s.dashboard?.optJSONArray("members")
        if (members != null && members.length() == 0) Banner("Todavía no hay personas vinculadas. Compartí una invitación; el vínculo requiere aceptación.")
        for (i in 0 until (members?.length() ?: 0)) {
            val member = members!!.getJSONObject(i); val permissions = member.optJSONObject("permissions")
            ChoiceCard(member.optString("name", "Persona de tu red"), if (permissions?.optBoolean("progress") == true) "${member.optInt("completedFlows")} tareas completadas · ${member.optInt("autonomyChecks")} prácticas autónomas · ${member.optInt("accompaniedSeconds") / 60} min acompañados" else "Esta persona mantiene su progreso privado.") { }
        }
        val alerts = s.dashboard?.optJSONArray("alerts")
        for (i in 0 until (alerts?.length() ?: 0)) { val alert = alerts!!.getJSONObject(i); Banner("${alert.optString("requesterName", "Tu red")}: ${alert.optString("message")}"); if (alert.isNull("resolvedAt")) OutlinedButton(onClick = { vm.resolveAlert(alert.getString("id")) }, enabled = !s.busy) { Text("Marcar como atendido") } }
        val requests = s.dashboard?.optJSONArray("joinRequests")
        for (i in 0 until (requests?.length() ?: 0)) { val request = requests!!.getJSONObject(i); Banner("${request.optString("name")} quiere sumarse a tu grupo.\n${request.optString("email")}"); OutlinedButton(onClick = { vm.approveMember(request.getString("id")) }, enabled = !s.busy) { Text("Aprobar incorporación") } }
    } else {
        TerritorySelector(s.field("territoryId")) { vm.field("territoryId", it) }
        val data = s.dashboard
        if (data != null) {
            if (data.optBoolean("suppressed", true)) Banner("Todavía no hay una cohorte suficiente. Los indicadores se protegen hasta alcanzar ${data.optInt("minimumCohort", 5)} participantes activos. Sin datos no significa cero.")
            else {
                Stat("Personas activas", data.optInt("participants").toString())
                data.optJSONObject("metrics")?.let { m -> Stat("Guías completadas", m.optInt("completedFlows").toString()); Stat("Prácticas autónomas", m.optInt("autonomyChecks").toString()); Stat("Tiempo acompañado", "${m.optDouble("accompaniedMinutes")} min") }
                data.optJSONObject("usage")?.let { Stat("Tiempo de pantalla autorizado", "${it.optDouble("screenMinutes")} min") }
                data.optJSONObject("safety")?.let { Stat("Señales de seguridad", it.optInt("total").toString()) }
                val flows = data.optJSONArray("flows"); for (i in 0 until (flows?.length() ?: 0)) { val flow = flows!!.getJSONObject(i); Text(flow.optString("name"), fontWeight = FontWeight.Bold); Text("${flow.optInt("started")} inicios · ${flow.optInt("completed")} completados", fontSize = 16.sp) }
            }
        }
        Text("Contexto público · OpenARG", fontWeight = FontWeight.Bold, color = Teal)
        val context = s.context
        if (context == null) Text("Actualizá los datos para consultar fuentes y períodos.", fontSize = 16.sp)
        val warnings = context?.optJSONArray("warnings"); for (i in 0 until (warnings?.length() ?: 0)) Banner(warnings!!.getString(i))
        val indicators = context?.optJSONArray("indicators"); for (i in 0 until (indicators?.length() ?: 0)) { val item = indicators!!.getJSONObject(i); Stat(item.optString("label"), "${item.opt("value")} ${item.optString("unit")}"); Text("${item.optString("period")} · ${item.optString("sourceTitle")}", fontSize = 14.sp, color = Muted) }
    }
    TextButton(onClick = vm::logout, enabled = !s.busy) { Text("Cerrar sesión") }
}

@Composable private fun ConnectionScreen(initial: String, busy: Boolean, connect: (String) -> Unit) {
    var url by rememberSaveable { mutableStateOf(initial) }
    Title("Tu app y tu servidor,\nconectados.", "La tablet se conecta al backend de TINO. Esta dirección no es la landing.")
    EntryField("Dirección del servidor", url, { url = it }, KeyboardType.Uri, enabled = !busy)
    Primary("Guardar y comprobar conexión", !busy) { connect(url) }
    Text("Por USB en la computadora de Federico: ejecutar adb reverse tcp:8787 tcp:8787 y usar http://127.0.0.1:8787.", fontSize = 16.sp, color = Muted)
    TextButton(onClick = { url = "http://127.0.0.1:8787" }) { Text("Usar conexión USB") }
    TextButton(onClick = { url = "http://10.0.2.2:8787" }) { Text("Usar emulador Android") }
}
@Composable private fun Title(title: String, detail: String) { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { Text(title, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.semantics { heading() }); Text(detail, fontSize = 18.sp, lineHeight = 27.sp, color = Muted) } }
@Composable private fun EntryField(label: String, value: String, onChange: (String) -> Unit, keyboard: KeyboardType = KeyboardType.Text, enabled: Boolean = true) { OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled, label = { Text(label, fontSize = 16.sp) }, keyboardOptions = KeyboardOptions(keyboardType = keyboard), singleLine = true, shape = RoundedCornerShape(16.dp)) }
@Composable private fun Primary(label: String, enabled: Boolean = true, action: () -> Unit) { Button(action, Modifier.fillMaxWidth().heightIn(min = 58.dp), enabled = enabled, shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(18.dp)) { Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) } }
@Composable private fun Banner(text: String, error: Boolean = false) { Surface(color = if (error) Color(0xFFFFEDEA) else Mint, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) { Text(text, Modifier.padding(16.dp), color = if (error) Color(0xFF8B2525) else Teal, fontSize = 16.sp, lineHeight = 24.sp) } }
@Composable private fun ChoiceCard(title: String, detail: String, action: () -> Unit) { Surface(onClick = action, color = Color.White, shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDCE5E4)), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 22.sp); Text(detail, color = Muted, fontSize = 17.sp, lineHeight = 25.sp) } } }
@Composable private fun RadioRow(label: String, selected: Boolean, action: () -> Unit, enabled: Boolean = true) { Surface(color = if (selected) Mint else Color.White, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Teal else Color(0xFFDCE5E4)), modifier = Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = action)) { Row(Modifier.padding(14.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, null, enabled = enabled); Spacer(Modifier.width(12.dp)); Text(label, fontSize = 18.sp, lineHeight = 26.sp, color = Ink) } } }
@Composable private fun CheckRow(label: String, checked: Boolean, action: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { action(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, null); Spacer(Modifier.width(10.dp)); Text(label, fontSize = 17.sp, lineHeight = 25.sp) } }
@Composable private fun Permission(title: String, detail: String, checked: Boolean, action: (Boolean) -> Unit) { Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) { Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp); Text(detail, color = Muted, fontSize = 16.sp, lineHeight = 24.sp) }; Switch(checked, action) } } }
@Composable private fun TerritorySelector(value: String, select: (String) -> Unit) { var expanded by remember { mutableStateOf(false) }; Column { Text("Provincia o jurisdicción", color = Muted, fontSize = 15.sp); OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp)) { Text(entryTerritories.find { it.first == value }?.second ?: "Elegir territorio", Modifier.weight(1f)); Text("⌄") }; DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) { entryTerritories.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { select(id); expanded = false }) } } } }
@Composable private fun Stat(label: String, value: String) { Surface(color = Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(label, color = Muted, fontSize = 16.sp); Text(value, color = Teal, fontSize = 28.sp, fontWeight = FontWeight.Bold) } } }
@Composable private fun ProfilePhoto(uri: String, name: String) {
    val resolver = LocalContext.current.contentResolver
    val bitmap by produceState<android.graphics.Bitmap?>(null, uri) { value = if (uri.isBlank()) null else withContext(Dispatchers.IO) { runCatching { val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; resolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, bounds) }; val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 256).coerceAtLeast(1) }; resolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it, null, options) } }.getOrNull() } }
    Surface(color = Mint, shape = CircleShape, modifier = Modifier.size(76.dp)) { if (bitmap != null) Image(bitmap!!.asImageBitmap(), "Tu foto de perfil", Modifier.clip(CircleShape), contentScale = ContentScale.Crop) else Box(contentAlignment = Alignment.Center) { Text(name.take(1).uppercase().ifBlank { "+" }, color = Teal, fontSize = 30.sp, fontWeight = FontWeight.Bold) } }
}
