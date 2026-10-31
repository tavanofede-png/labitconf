package org.nieto.assistant.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import org.nieto.assistant.NietoApp
import org.nieto.assistant.entry.EntryState
import org.nieto.assistant.entry.EntryViewModel
import org.nieto.assistant.entry.entryTerritories
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun timeLabel(value: String): String = runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM · HH:mm")) }.getOrDefault("")

@Composable fun FamilyDashboard(s: EntryState, vm: EntryViewModel, app: NietoApp) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var invitation by rememberSaveable { mutableStateOf(false) }
    var permissions by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val data = s.dashboard
    val members = data?.optJSONArray("members").objects()
    val member = members.find { it.optString("id") == selectedId } ?: members.firstOrNull()
    val id = member?.optString("id").orEmpty()
    val allowed = member?.optJSONObject("permissions") ?: JSONObject()
    val alerts = data?.optJSONArray("alerts").objects().filter { it.optString("memberId") == id }
    LaunchedEffect(Unit) { vm.state.first { !it.busy }; vm.dashboard() }
    Scaffold(containerColor = DashPaper, topBar = { DashboardHeader("Grupo familiar", data?.optJSONObject("group")?.optString("name") ?: "Tu red, más cerca", s.field("name"), s.busy, vm::dashboard, vm::logout) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 1280.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Tu familia,\nsiempre cerca.", color = DashInk, fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold)
                Text("Acompañá a su ritmo. Cada persona decide qué compartir.", color = DashMuted, fontSize = 16.sp, lineHeight = 24.sp)
                if (s.demoAccount) DataPill("Familia demo · actividad y ubicación simuladas", true)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
                    members.forEach { person -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DashboardAvatar(person.optString("name"), person.optString("id") == id) { selectedId = person.optString("id") }
                        Text(person.optString("name"), color = if (person.optString("id") == id) DashTeal else DashMuted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    } }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        OutlinedButton(onClick = { invitation = true; vm.invitation() }, enabled = !s.busy, modifier = Modifier.size(58.dp), contentPadding = PaddingValues(0.dp), shape = androidx.compose.foundation.shape.CircleShape) { Text("+", fontSize = 28.sp, color = DashTeal) }
                        Text("Invitar", color = DashMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = DashCyan)
                s.error?.let { DashboardCard("No pudimos completar la acción") { Text(it, color = DashRed); TextButton(onClick = vm::dashboard, enabled = !s.busy) { Text("Volver a intentar") } } }
                s.message?.let { Text(it, color = DashTeal, fontSize = 14.sp) }
                DashboardTabs(listOf("Resumen", "Actividad", "Ubicación", "Alertas${if (alerts.any { it.isNull("resolvedAt") }) " •" else ""}"), tab) { tab = it }
                if (member == null) DashboardCard("Sumá a tu primera persona") {
                    Text("Creá una invitación. Cuando esa persona la acepte, elegirá qué compartir con el grupo.", color = DashMuted, lineHeight = 23.sp)
                    Button(onClick = { invitation = true; vm.invitation() }, enabled = !s.busy, colors = ButtonDefaults.buttonColors(containerColor = DashCyan, contentColor = DashInk)) { Text("Crear invitación") }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(member.optString("name"), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = DashInk); Text(if (allowed.optBoolean("progress")) "Comparte su progreso" else "Mantiene su progreso privado", color = DashMuted, fontSize = 13.sp) }
                        TextButton(onClick = { permissions = true }) { Text("Permisos", color = DashTeal) }
                    }
                    when (tab) {
                        0 -> {
                            AdaptivePair({
                                DashboardCard("Pantalla hoy", if (allowed.optBoolean("usage")) "Uso compartido voluntariamente" else "Esta persona no autorizó compartir su tiempo de pantalla") {
                                    Text(if (allowed.optBoolean("usage")) member.amount("screenMinutes", " min") else "Privado", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = DashInk)
                                    if (allowed.optBoolean("usage")) MiniBarChart(member.optJSONArray("weeklyScreen").numbers(), labels = listOf("−6", "−5", "−4", "−3", "−2", "Ayer", "Hoy"), description = "Tiempo de pantalla de los últimos siete días")
                                }
                                DashboardCard("Aprendió con TINO") {
                                    Text(if (allowed.optBoolean("progress")) member.optString("learned").takeUnless { it.isBlank() || it == "null" } ?: "Sus prácticas aparecerán acá." else "Su progreso es privado.", fontSize = 20.sp, lineHeight = 28.sp, color = DashTeal, fontWeight = FontWeight.SemiBold)
                                    if (allowed.optBoolean("progress")) Text("${member.amount("completedFlows")} tareas completadas · ${member.amount("autonomyChecks")} prácticas sin ayuda", color = DashMuted, fontSize = 14.sp, lineHeight = 21.sp)
                                }
                            }, {
                                DashboardCard("Un rato con TINO", "Acompañamiento durante el día") {
                                    Text(if (allowed.optBoolean("progress")) member.amount("tinoMinutes", " min") else "Privado", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = DashTeal)
                                    if (allowed.optBoolean("progress")) MiniBarChart(member.optJSONArray("weeklyTino").numbers(), labels = listOf("−6", "−5", "−4", "−3", "−2", "Ayer", "Hoy"), description = "Minutos de acompañamiento TINO de los últimos siete días")
                                }
                                FamilyActions(allowed.optBoolean("help"), s.busy, { vm.familyAction(id, "ping") }, { recording = true })
                            })
                            if (alerts.isNotEmpty()) AlertCards(alerts, s.busy) { alert -> vm.familyAction(if (s.demoAccount) id else alert.getString("id"), "resolve") }
                        }
                        1 -> {
                            DashboardCard("Actividad compartida", "Tareas y prácticas; nunca conversaciones ni contenido de otras apps.") {
                                val activities = member.optJSONArray("activities").objects()
                                if (!allowed.optBoolean("progress")) Text("Esta persona no comparte su actividad.", color = DashMuted)
                                else if (activities.isEmpty()) Text("Todavía no hay actividad compartida.", color = DashMuted)
                                else activities.forEach { item ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Text("✓", color = DashGreen, fontSize = 22.sp)
                                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(item.optString("title"), color = DashInk, fontWeight = FontWeight.SemiBold); Text(item.optString("detail"), color = DashMuted, fontSize = 15.sp); Text(timeLabel(item.optString("at")), color = DashMuted, fontSize = 12.sp) }
                                    }; HorizontalDivider(color = DashPaper)
                                }
                            }
                        }
                        2 -> {
                            val location = member.optJSONObject("location")?.takeIf { runCatching { Instant.parse(it.getString("expiresAt")).isAfter(Instant.now()) }.getOrDefault(false) }
                            DashboardCard(if (location != null) "Ubicación compartida" else "Su ubicación es privada", "Solo aparece cuando esa persona la comparte y durante el tiempo que elige.") {
                                if (location == null) {
                                    Text("No hay una ubicación temporal activa.", color = DashMuted)
                                    OutlinedButton(onClick = { vm.familyAction(id, "location_request") }, enabled = allowed.optBoolean("help") && !s.busy) { Text("Pedir que comparta ubicación", color = DashTeal) }
                                } else {
                                    Text(location.optString("label"), fontSize = 24.sp, color = DashTeal, fontWeight = FontWeight.Bold)
                                    Text("Compartida ${timeLabel(location.optString("sharedAt"))} · hasta ${timeLabel(location.optString("expiresAt"))}", color = DashMuted, fontSize = 13.sp)
                                    val point = JSONObject().put("id", member.optString("territoryId")).put("point", location.getJSONArray("point")).put("activeUsers", 1).put("unsafeLinks", 0)
                                    ArgentinaMap(listOf(point), member.optString("territoryId"), 1) { }
                                    if (s.demoAccount) Text("Ubicación aproximada simulada para la demo.", color = DashMuted, fontSize = 12.sp)
                                }
                            }
                        }
                        else -> {
                            DashboardCard("Avisos importantes", "Señales para acompañar, no para vigilar.") {
                                if (alerts.isEmpty()) Text("No hay alertas compartidas para esta persona.", color = DashMuted)
                                else AlertCards(alerts, s.busy) { alert -> vm.familyAction(if (s.demoAccount) id else alert.getString("id"), "resolve") }
                            }
                        }
                    }
                    val messages = data?.optJSONArray("messages").objects().filter { it.optString("memberId") == id }
                    if (messages.isNotEmpty()) DashboardCard("Lo que enviaste") {
                        messages.forEach { message ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Text(when (message.optString("type")) { "voice" -> "Aviso de voz"; "location_request" -> "Solicitud de ubicación"; else -> "Ping de acompañamiento" }, color = DashInk, fontWeight = FontWeight.SemiBold); Text(timeLabel(message.optString("createdAt")), fontSize = 12.sp, color = DashMuted) }
                                if (message.optString("type") == "voice") AudioMessageButton(message.optString("audioBase64"))
                            }
                        }
                    }
                }
                val requests = data?.optJSONArray("joinRequests").objects()
                if (requests.isNotEmpty()) DashboardCard("Solicitudes para sumarse") { requests.forEach { request ->
                    Text(request.optString("name"), color = DashInk, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { vm.approveMember(request.getString("id")) }, enabled = !s.busy) { Text("Aprobar incorporación", color = DashTeal) }
                } }
                Text("Acompañar sin invadir. Cada integrante puede dejar de compartir sus datos cuando quiera.", color = DashMuted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
    if (invitation) AlertDialog(onDismissRequest = { invitation = false }, title = { Text("Invitá a tu red") }, text = { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Compartí este código. La persona debe aceptarlo desde TINO y elegir sus permisos.", lineHeight = 23.sp)
        if (s.busy) LinearProgressIndicator(color = DashCyan)
        if (s.invitationCode.isNotBlank()) {
            Text(s.invitationCode, color = DashTeal, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Vence en 15 minutos y se usa una sola vez.", color = DashMuted, fontSize = 13.sp)
            TextButton(onClick = { clipboard.setText(AnnotatedString(s.invitationCode)); vm.message("Código copiado.") }) { Text("Copiar código", color = DashTeal) }
            OutlinedButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Te invito a mi grupo de TINO. Código: ${s.invitationCode}. Abrí Familia en TINO para aceptar y elegir qué compartir."), "Compartir invitación")) }) { Text("Compartir invitación", color = DashTeal) }
        }
        s.error?.let { Text(it, color = DashRed) }
    } }, confirmButton = { TextButton(onClick = { invitation = false }) { Text("Listo") } })
    if (permissions && member != null) AlertDialog(onDismissRequest = { permissions = false }, title = { Text("${member.optString("name")} decide") }, text = { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf("progress" to "Progreso y prácticas", "help" to "Acompañamiento y avisos", "usage" to "Tiempo de pantalla", "safety" to "Señales de seguridad", "location" to "Ubicación temporal").forEach { (key, label) ->
            Row { Text(label, Modifier.weight(1f)); Text(if (allowed.optBoolean(key)) "Compartido" else "Privado", color = if (allowed.optBoolean(key)) DashTeal else DashMuted, fontSize = 13.sp) }
        }
        Text("Estos permisos se eligen desde el dispositivo de esa persona. Ser parte de la familia no habilita a ver su pantalla ni sus mensajes.", color = DashMuted, fontSize = 14.sp, lineHeight = 21.sp)
    } }, confirmButton = { TextButton(onClick = { permissions = false }) { Text("Entendido") } })
    if (recording && member != null) VoiceNoteDialog(member.optString("name"), s.busy, s.error, s.message, { recording = false }) { audio, requestId -> vm.familyAction(id, "voice", audio, requestId) }
}

@Composable private fun FamilyActions(allowed: Boolean, busy: Boolean, ping: () -> Unit, voice: () -> Unit) {
    DashboardCard("Estoy cerca") {
        Button(onClick = ping, enabled = allowed && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), colors = ButtonDefaults.buttonColors(containerColor = DashCyan, contentColor = DashInk)) { Text("↗  Enviar ping", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
        OutlinedButton(onClick = voice, enabled = allowed && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("Aviso de voz", color = DashTeal, fontSize = 17.sp) }
        Text(if (allowed) "Un aviso breve para que sepa que estás disponible." else "Los avisos requieren su autorización de acompañamiento.", color = DashMuted, fontSize = 12.sp, lineHeight = 18.sp)
    }
}
@Composable private fun AlertCards(alerts: List<JSONObject>, busy: Boolean, resolve: (JSONObject) -> Unit) {
    alerts.forEach { alert -> DashboardCard(if (alert.isNull("resolvedAt")) "Un aviso para revisar juntos" else "Aviso atendido") {
        Text(alert.optString("title", alert.optString("message")), color = if (alert.isNull("resolvedAt")) DashRed else DashTeal, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(alert.optString("message"), color = DashMuted, fontSize = 14.sp, lineHeight = 21.sp)
        Text(timeLabel(alert.optString("createdAt")), color = DashMuted, fontSize = 12.sp)
        if (alert.isNull("resolvedAt")) OutlinedButton(onClick = { resolve(alert) }, enabled = !busy) { Text("Marcar como atendido", color = DashTeal) }
    } }
}
