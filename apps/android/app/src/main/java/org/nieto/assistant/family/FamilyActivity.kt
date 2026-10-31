package org.nieto.assistant.family

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.nieto.assistant.NietoApp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class FamilyViewState(val signedIn: Boolean = false, val summary: FamilySummary? = null,
    val invitationCode: String = "", val expiresAt: String = "", val busy: Boolean = false,
    val notice: String = "", val notifications: Boolean = false, val permission: Boolean = false, val lastChecked: Long = 0)

/** Caregiver mode in the same APK. It never registers a device or requests accessibility/microphone. */
class FamilyActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: FamilyStore
    private var work: Job? = null
    private var state by mutableStateOf(FamilyViewState())
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        setNotifications(granted)
        if (!granted) state = state.copy(notice = "No se activaron los avisos. Podés revisar los pedidos al abrir Familia.")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store = FamilyStore(this)
        state = FamilyViewState(signedIn = store.access() != null, notifications = store.notificationsEnabled,
            permission = FamilyNotifications.permitted(this), lastChecked = store.lastCheckedAt)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF264C3B), background = Color(0xFFFAF8F2))) {
                FamilyScreen(application as NietoApp, intent.getBooleanExtra("caregiver", false), state, store.serverUrl,
                    onBack = ::finish, onCreate = ::createGroup, onRestore = ::restore,
                    onRefresh = ::refresh, onInvitation = ::invitation, onResolve = ::resolve,
                    onCopyCode = { copyPrivate(state.invitationCode, "Código de invitación") },
                    onBackup = { store.access()?.let { copyPrivate(it.token, "Credencial familiar") } },
                    onNotifications = ::toggleNotifications, onSystemSettings = {
                        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                    }, onClose = {
                        work?.cancel(); FamilyBackground.closeAccess(this)
                        state = FamilyViewState(notice = "Acceso borrado de este teléfono. Los vínculos del grupo siguen en el servidor.")
                    })
            }
        }
    }
    override fun onStart() {
        super.onStart()
        (application as NietoApp).apply { surfaceVisible("family", true); onAssistantBackground() }
        state = state.copy(signedIn = store.access() != null, notifications = store.notificationsEnabled,
            permission = FamilyNotifications.permitted(this), lastChecked = store.lastCheckedAt)
        if (state.signedIn) { FamilyBackground.schedule(this); refresh() }
        else state = state.copy(summary = null)
    }
    override fun onStop() {
        work?.cancel(); work = null; state = state.copy(busy = false)
        (application as NietoApp).surfaceVisible("family", false)
        super.onStop()
    }
    override fun onDestroy() { scope.cancel(); (application as NietoApp).surfaceVisible("family", false); super.onDestroy() }

    private fun action(block: suspend () -> Unit) {
        if (state.busy) return
        state = state.copy(busy = true, notice = "")
        work = scope.launch {
            try { block() }
            catch (_: CancellationException) { }
            catch (error: FamilyApiFailure) {
                if (error.status == 401 || error.status == 403) {
                    FamilyBackground.closeAccess(this@FamilyActivity)
                    state = FamilyViewState(notice = error.message.orEmpty())
                } else state = state.copy(notice = error.message.orEmpty())
            } catch (_: Exception) { state = state.copy(notice = "No pude completar la acción. Revisá el servidor o volvé a intentar.") }
            finally { state = state.copy(busy = false) }
        }
    }
    private fun createGroup(server: String, name: String) = action {
        val normalized = FamilyStore.normalizeServer(server)
        val response = FamilyApi().request(normalized, "/family/groups", body = JSONObject().put("name", name.trim()))
        val access = store.save(normalized, response.getString("caregiverToken"))
        // Keep the saved access and explicit backup available even when the first summary fails.
        state = FamilyViewState(signedIn = true, busy = true, invitationCode = response.getString("invitationCode"),
            expiresAt = response.getString("expiresAt"), permission = FamilyNotifications.permitted(this))
        val summary = FamilyRepository(this).refresh(access, notify = false)
        state = state.copy(summary = summary, lastChecked = store.lastCheckedAt,
            notice = "Grupo creado. Guardá tu credencial con el botón de respaldo.")
    }
    private fun restore(server: String, credential: String) = action {
        val normalized = FamilyStore.normalizeServer(server); val token = credential.trim()
        require(token.matches(Regex("[A-Za-z0-9_.-]{20,512}")))
        val candidate = FamilyAccess(normalized, token, "validation")
        val summary = FamilyApi().summary(candidate)
        val access = store.save(normalized, token)
        FamilyRepository(this).accept(access, summary, notify = false)
        state = FamilyViewState(signedIn = true, summary = summary, lastChecked = store.lastCheckedAt,
            permission = FamilyNotifications.permitted(this), notice = "Acceso familiar restaurado. Los pedidos existentes están abajo.")
    }
    private fun refresh() = action {
        val access = store.access() ?: return@action
        val summary = FamilyRepository(this).refresh(access, notify = false) ?: return@action
        state = state.copy(summary = summary, signedIn = true, lastChecked = store.lastCheckedAt,
            notifications = store.notificationsEnabled, permission = FamilyNotifications.permitted(this), notice = "Familia actualizada.")
    }
    private fun invitation() = action {
        val access = store.access() ?: return@action
        val result = FamilyApi().invitation(access)
        store.ifCurrent(access) { state = state.copy(invitationCode = result.getString("invitationCode"), expiresAt = result.getString("expiresAt")) }
    }
    private fun resolve(id: String) = action {
        val access = store.access() ?: return@action
        FamilyApi().resolve(access, id)
        val summary = FamilyRepository(this).refresh(access, notify = false) ?: return@action
        state = state.copy(summary = summary, lastChecked = store.lastCheckedAt, notice = "Pedido marcado como atendido en el servidor.")
    }
    private fun toggleNotifications() {
        if (store.notificationsEnabled) { setNotifications(false); return }
        FamilyNotifications.createChannel(this)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else if (FamilyNotifications.permitted(this)) setNotifications(true)
        else state = state.copy(notice = "Android tiene los avisos bloqueados. Podés habilitarlos en sus ajustes.")
    }
    private fun setNotifications(enabled: Boolean) {
        if (store.access() == null) return
        store.setNotifications(enabled)
        if (enabled) FamilyBackground.schedule(this) else FamilyBackground.stop(this)
        state = state.copy(notifications = enabled, permission = FamilyNotifications.permitted(this), notice =
            if (enabled) "Avisos periódicos activados. Android puede demorarlos más de 15 minutos." else "Avisos periódicos desactivados.")
    }
    private fun copyPrivate(value: String, label: String) {
        if (value.isBlank()) return
        val clip = ClipData.newPlainText(label, value)
        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        state = state.copy(notice = "$label copiado. Guardalo en un lugar privado; quien tenga la credencial accede al grupo.")
    }
}

@Composable
private fun FamilyScreen(app: NietoApp, initialCaregiver: Boolean, state: FamilyViewState, initialServer: String, onBack: () -> Unit,
    onCreate: (String, String) -> Unit, onRestore: (String, String) -> Unit, onRefresh: () -> Unit,
    onInvitation: () -> Unit, onResolve: (String) -> Unit, onCopyCode: () -> Unit, onBackup: () -> Unit,
    onNotifications: () -> Unit, onSystemSettings: () -> Unit, onClose: () -> Unit) {
    var server by remember { mutableStateOf(initialServer) }
    var name by remember { mutableStateOf("") }
    var credential by remember { mutableStateOf("") }
    var confirmClose by remember { mutableStateOf(false) }
    var caregiver by remember { mutableStateOf(initialCaregiver) }
    LaunchedEffect(state.signedIn) { if (state.signedIn) credential = "" }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = onBack) { Text("Volver a NIETO") }
            Text("NIETO Familia", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text("Acompañá desde esta app, con los permisos que cada persona elige.", fontSize = 19.sp)
            OutlinedButton(onClick = { caregiver = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (!caregiver) "✓ Este teléfono recibe ayuda" else "Este teléfono recibe ayuda")
            }
            OutlinedButton(onClick = { caregiver = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (caregiver) "✓ Acompaño a mi familia" else "Acompaño a mi familia")
            }
            if (!caregiver) {
                AssistedFamilyPanel(app)
            } else {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.notice.isNotBlank()) Text(state.notice, fontSize = 17.sp)
            if (!state.signedIn) {
                OutlinedTextField(server, { server = it }, label = { Text("Dirección del servidor") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                Text("Usá la misma dirección que el teléfono asistido, sin /api. El acceso familiar se guarda separado.")
                Text("Crear mi grupo", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Nombre del grupo") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { onCreate(server, name) }, enabled = !state.busy && name.trim().length >= 2,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Crear grupo y obtener invitación") }
                HorizontalDivider()
                Text("Ya tengo una credencial familiar", fontSize = 22.sp)
                OutlinedTextField(credential, { credential = it.take(512) }, label = { Text("Credencial de respaldo") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { onRestore(server, credential) }, enabled = !state.busy && credential.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Restaurar acceso familiar") }
            } else {
                Text(state.summary?.groupName ?: "Tu grupo", fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                Text("Servidor: $initialServer", fontSize = 15.sp)
                OutlinedButton(onClick = onRefresh, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Comprobar pedidos ahora") }
                Text("Personas vinculadas", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                if (state.summary?.members.isNullOrEmpty()) Text("Todavía no hay personas vinculadas. Compartí una invitación y esperá su aceptación en el teléfono asistido.")
                state.summary?.members?.forEach { member ->
                    HorizontalDivider()
                    Text(member.name, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Text("Progreso: ${if (member.shareProgress) "compartido" else "privado"} · Ayuda: ${if (member.allowHelp) "habilitada" else "privada"}")
                    if (member.shareProgress) Text("${member.reportedFlows ?: 0} envíos reportados · ${member.practiceReports ?: 0} prácticas reportadas · ${(member.accompaniedSeconds ?: 0) / 60} minutos acompañados")
                }
                HorizontalDivider()
                Text("Invitar a una persona", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                Text("La persona abre NIETO → Familia en su teléfono, ingresa el código y decide si comparte progreso y ayuda.")
                if (state.invitationCode.isNotBlank()) {
                    Text(state.invitationCode, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("Un solo uso. Vence: ${familyDate(state.expiresAt)}")
                    OutlinedButton(onClick = onCopyCode) { Text("Copiar código") }
                }
                Button(onClick = onInvitation, enabled = !state.busy && state.summary != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Generar nueva invitación") }
                HorizontalDivider()
                Text("Pedidos de ayuda", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                val allowed = state.summary?.members?.filter { it.allowHelp }?.map { it.id }?.toSet().orEmpty()
                val alerts = state.summary?.alerts?.filter { it.memberId in allowed }.orEmpty()
                if (alerts.isEmpty()) Text("No hay pedidos disponibles con los permisos actuales.")
                alerts.sortedByDescending { it.createdAt }.forEach { alert ->
                    Text("${alert.requesterName.ifBlank { "Una persona de tu grupo" }} pidió acompañamiento.", fontSize = 19.sp)
                    Text("${familyDate(alert.createdAt)} · ${if (alert.resolved) "Atendido" else "Pendiente"}")
                    if (!alert.resolved) OutlinedButton(onClick = { onResolve(alert.id) }, enabled = !state.busy) { Text("Marcar atendido") }
                }
                HorizontalDivider()
                Text("Avisos fuera de la app", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                Text("Son notificaciones locales tras una consulta periódica. Android intenta comprobar cada 15 minutos y puede demorarlo por batería o red. No son push instantáneos ni un servicio de emergencias.")
                Text(if (state.lastChecked == 0L) "Todavía no hubo una comprobación." else "Última comprobación: ${familyDate(Instant.ofEpochMilli(state.lastChecked).toString())}", fontSize = 15.sp)
                Button(onClick = onNotifications, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (state.notifications) "Desactivar avisos periódicos" else "Activar avisos periódicos")
                }
                if (!state.permission) TextButton(onClick = onSystemSettings) { Text("Abrir ajustes de notificaciones de Android") }
                Text("Solo se avisan pedidos nuevos de personas que mantienen el permiso de ayuda. La próxima consulta retira avisos de pedidos atendidos o de permisos revocados. Los pedidos existentes se ven aquí y no se vuelven a notificar al restaurar el acceso.", fontSize = 15.sp)
                HorizontalDivider()
                Text("Tu acceso privado", fontSize = 23.sp)
                Text("La credencial se cifra con Android Keystore. Quien tenga su respaldo puede consultar este grupo. No se muestra automáticamente.")
                OutlinedButton(onClick = onBackup, modifier = Modifier.fillMaxWidth()) { Text("Copiar credencial para respaldo privado") }
                TextButton(onClick = { confirmClose = true }, modifier = Modifier.fillMaxWidth()) { Text("Cerrar y borrar acceso de este teléfono") }
            }
            }
            Text("La familia no recibe conversaciones, capturas ni mensajes de WhatsApp. La persona asistida puede revocar su vínculo desde NIETO.", fontSize = 16.sp)
        }
    }
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text("¿Borrar el acceso local?") },
        text = { Text("Se borra la credencial de este teléfono y se cancelan sus avisos. El grupo y sus vínculos siguen en el servidor; una copia de la credencial continúa siendo válida. Guardá tu respaldo antes si querés volver a entrar.") },
        confirmButton = { TextButton(onClick = { confirmClose = false; onClose() }) { Text("Borrar acceso local") } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Volver") } })
}

@Composable
private fun AssistedFamilyPanel(app: NietoApp) {
    val state by app.state.collectAsState()
    var code by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var revoke by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (app.settings.token.isNotBlank()) app.refreshFamily() }
    Text("Vos elegís quién puede acompañarte", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
    Text("Este vínculo usa el acceso de tu teléfono. Es independiente del acceso de quien acompaña.")
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (state.familyName == null) {
        Text("Pedile a tu familiar un código de invitación. Al aceptarlo elegís cada permiso por separado.")
        OutlinedTextField(code, { code = it.take(40) }, label = { Text("Código de invitación") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Row { Checkbox(progress, { progress = it }, enabled = !state.busy); Text("Compartir mis reportes de envío, práctica y duración.", Modifier.padding(top = 12.dp)) }
        Row { Checkbox(help, { help = it }, enabled = !state.busy); Text("Permitir mis pedidos de ayuda al grupo.", Modifier.padding(top = 12.dp)) }
        Button(onClick = { app.acceptFamily(code, progress, help) }, enabled = code.isNotBlank() && !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Aceptar invitación y mis permisos") }
    } else {
        Text("Tu grupo: ${state.familyName}", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text("Progreso: ${if (state.shareProgress) "compartido" else "privado"} · Ayuda: ${if (state.allowHelp) "autorizada" else "privada"}")
        Button(onClick = { app.requestHelp() }, enabled = state.allowHelp && !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Pedir ayuda a mi familia") }
        Text("Envía un pedido real al grupo. Sus avisos periódicos pueden demorarse; no realiza una llamada ni es un servicio de emergencias.")
        OutlinedButton(onClick = { revoke = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Revocar vínculo y permisos familiares") }
        Text("Para cambiar los permisos, revocá este vínculo y aceptá una invitación nueva.")
    }
    OutlinedButton(onClick = { app.refreshFamily() }, enabled = !state.busy && app.settings.token.isNotBlank(),
        modifier = Modifier.fillMaxWidth()) { Text("Actualizar mi vínculo") }
    Text(state.status, fontSize = 17.sp)
    if (revoke) AlertDialog(onDismissRequest = { revoke = false }, title = { Text("¿Revocar el vínculo?") },
        text = { Text("El grupo dejará de consultar tu progreso y tus pedidos de ayuda. Los avisos de su teléfono se retirarán en su próxima comprobación.") },
        confirmButton = { TextButton(onClick = { revoke = false; app.revokeFamily() }) { Text("Revocar vínculo") } },
        dismissButton = { TextButton(onClick = { revoke = false }) { Text("Volver") } })
}

private fun familyDate(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault("Fecha no disponible")
