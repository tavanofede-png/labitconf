package org.nieto.assistant.ui

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import org.nieto.assistant.NietoApp
import org.nieto.assistant.entry.EntryState
import org.nieto.assistant.entry.EntryViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun timeLabel(value: String): String = runCatching { Instant.parse(value).atZone(FamilyZone).format(DateTimeFormatter.ofPattern("dd/MM · HH:mm")) }.getOrDefault("")

@Composable fun FamilyDashboard(s: EntryState, vm: EntryViewModel, app: NietoApp, onMenu: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var invitation by rememberSaveable { mutableStateOf(false) }
    var permissions by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(s.role,lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while(kotlinx.coroutines.currentCoroutineContext().isActive) { kotlinx.coroutines.delay(30000);vm.refreshDashboardIfIdle() }
        }
    }
    val context=LocalContext.current
    val clipboard=LocalClipboardManager.current
    val data=s.dashboard
    val members=data?.optJSONArray("members").objects()
    val member=members.find{it.optString("id")==selectedId}?:members.firstOrNull()
    val id=member?.optString("id").orEmpty()
    val allowed=member?.optJSONObject("permissions")?:JSONObject()
    val alerts=data?.optJSONArray("alerts").objects().filter{it.optString("memberId")==id}
    val activeAlerts=alerts.count{it.isNull("resolvedAt")}
    val now=data?.optString("snapshotAt")?.takeIf{it.isNotBlank()}?:Instant.now().toString()
    val invite={ invitation=true;vm.invitation() }
    val resolve: (JSONObject)->Unit = { alert -> vm.familyAction(if(s.demoAccount) id else alert.getString("id"),"resolve",alertId=alert.optString("id")) }
    LaunchedEffect(Unit){vm.state.first{!it.busy};vm.dashboard()}
    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(fontSize=14.sp,lineHeight=20.sp)) {
    Scaffold(containerColor=DashPaper,topBar={DashboardHeader("Grupo familiar",data?.optJSONObject("group")?.optString("name")?:"Tu círculo de confianza",onMenu)}) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide=maxWidth>=720.dp
            Row(Modifier.fillMaxSize()) {
                if(wide && member!=null) {
                    Surface(Modifier.width(218.dp).fillMaxHeight(),color=Color.White) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            FamilyMemberPicker(members,id,compact=true,choose={selectedId=it},invite=invite)
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                FamilyPortrait(member,Modifier.size(44.dp))
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                    Text(member.optString("name"),fontSize=19.sp,lineHeight=23.sp,color=DashInk,fontWeight=FontWeight.Bold)
                                    Text(member.optString("relationship","Integrante") + if(member.has("age")) " · ${member.optInt("age")} años" else "",color=DashMuted,fontSize=11.sp,lineHeight=16.sp)
                                    Text(member.optString("device","Dispositivo vinculado"),color=DashMuted,fontSize=9.sp,lineHeight=14.sp)
                                }
                            }
                            FamilyStatus(member.optBoolean("online"),relativeFamilyTime(member.optString("lastActiveAt"),now))
                            FamilyActionButtons(s.busy,allowed.optBoolean("help"),true,{vm.familyAction(id,"ping")},{recording=true})
                            HorizontalDivider(color=FamilyLine)
                            FamilyNavigation(tab,activeAlerts,true){tab=it}
                            FamilyPrivacyFooter(member.optString("name"),compact=true){permissions=true}
                        }
                    }
                    VerticalDivider(color=FamilyLine)
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal=if(wide) 26.dp else 20.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                    if(!wide && member!=null) {
                        FamilyMemberPicker(members,id,false,{selectedId=it},invite)
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text(member.optString("name")+member.optString("relationship").takeIf{it.isNotBlank()}?.let{" · $it"}.orEmpty(),fontSize=22.sp,color=DashInk,fontWeight=FontWeight.Bold)
                                Text(if(member.has("age")) "${member.optInt("age")} años · ${member.optString("device","Dispositivo vinculado")}" else "Comparte lo que elige, a su ritmo.",fontSize=11.sp,color=DashMuted)
                            }
                            FamilyStatus(member.optBoolean("online"),relativeFamilyTime(member.optString("lastActiveAt"),now))
                        }
                        FamilyActionButtons(s.busy,allowed.optBoolean("help"),false,{vm.familyAction(id,"ping")},{recording=true})
                        FamilyNavigation(tab,activeAlerts,false){tab=it}
                    }
                    if(s.busy) LinearProgressIndicator(Modifier.fillMaxWidth(),color=DashCyan)
                    s.error?.let{FamilySurface("No pudimos completar la acción"){Text(it,color=DashRed,fontSize=14.sp);TextButton(onClick=vm::dashboard,enabled=!s.busy){Text("Volver a intentar",color=DashTeal)}}}
                    s.message?.let{Surface(color=FamilyMint,shape=RoundedCornerShape(12.dp)){Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){FamilyGlyph("check",Modifier.size(18.dp));Text(it,color=DashTeal,fontSize=13.sp,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})}}}
                    if(member==null) FamilySurface(if(s.busy) "Preparando tu grupo…" else "Tu red empieza con una invitación","Cada persona elige desde su teléfono qué quiere compartir.") {
                        if(!s.busy) Button(onClick=invite,colors=ButtonDefaults.buttonColors(containerColor=DashCyan,contentColor=DashInk)){Text("Invitar a alguien")}
                    } else {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                Text(when(tab){0->"Así viene su día";1->"Actividad de ${member.optString("name")}";2->"Ubicación compartida";else->"Avisos para acompañar"},color=DashInk,fontSize=if(wide) 27.sp else 23.sp,fontWeight=FontWeight.Bold,modifier=Modifier.semantics{heading()})
                                Text(runCatching{Instant.parse(now).atZone(FamilyZone).format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM",Locale.forLanguageTag("es-AR"))).replaceFirstChar{it.uppercase()}}.getOrDefault("Hoy"),color=DashMuted,fontSize=12.sp)
                            }
                            if(s.demoAccount) Surface(color=FamilyCream,shape=CircleShape){Text("Datos demo",Modifier.padding(horizontal=10.dp,vertical=6.dp),fontSize=10.sp,color=FamilyAmber,fontWeight=FontWeight.SemiBold)}
                        }
                        when(tab) {
                            0 -> {
                                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    FamilyStat("Última actividad",relativeFamilyTime(member.optString("lastActiveAt"),now),if(allowed.optBoolean("progress")) member.optString("lastActivity","Sin actividad compartida") else "Su actividad es privada","clock",Modifier.weight(1f))
                                    FamilyStat("Pantalla hoy",if(allowed.optBoolean("usage")) familyMinutes(member.optInt("screenMinutes")) else "Privado",if(allowed.optBoolean("progress")) "${member.optInt("tinoMinutes")} min con acompañamiento" else "Progreso privado","screen",Modifier.weight(1f))
                                }
                                FamilyHelpRequestPreview(member,allowed.optBoolean("progress"))
                                FamilyRecentSites(member,allowed,detail={detail=it},activity={tab=1})
                                FamilyResponsivePair({FamilyLocationPreview(member,allowed,now){tab=2}},{FamilySafetyPreview(alerts,allowed){tab=3}})
                                FamilyWeeklyChart(member,allowed.optBoolean("usage"),allowed.optBoolean("progress"))
                                if(allowed.optBoolean("progress")) FamilyHelpSummary(member)
                            }
                            1 -> FamilyActivityPanel(member,allowed,now,alerts,{detail=it},{tab=3})
                            2 -> FamilyLocationPanel(member,allowed,now,s.busy,s.demoAccount){vm.familyAction(id,"location_request")}
                            else -> { if(!s.demoAccount)FamilyNotificationSettings(app); FamilyAlertsPanel(alerts,allowed,s.busy,now,resolve,{tab=1}) }
                        }
                        val messages=data?.optJSONArray("messages").objects().filter{it.optString("memberId")==id}
                        if(messages.isNotEmpty()) FamilySurface("Tus avisos a ${member.optString("name")}","Quedan en este resumen durante 24 horas.") {
                            messages.forEach{message->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){
                                FamilyGlyph(if(message.optString("type")=="voice") "mic" else if(message.optString("type")=="location_request") "pin" else "bell",Modifier.size(19.dp))
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(when(message.optString("type")){"voice"->"Aviso de voz enviado";"location_request"->"Le pediste que comparta su ubicación";else->"Le avisaste que estás cerca"},fontSize=13.sp,color=DashInk);Text(relativeFamilyTime(message.optString("createdAt"),now),fontSize=11.sp,color=DashMuted)}
                                if(message.optString("type")=="voice") AudioMessageButton(message.optString("audioBase64"))
                            }}
                        }
                        if(!wide) FamilyPrivacyFooter(member.optString("name")){permissions=true}
                    }
                    val requests=data?.optJSONArray("joinRequests").objects()
                    if(requests.isNotEmpty()) FamilySurface("Solicitudes para sumarse") {requests.forEach{request->Text(request.optString("name"),color=DashInk,fontSize=15.sp,fontWeight=FontWeight.Bold);OutlinedButton(onClick={vm.approveMember(request.getString("id"))},enabled=!s.busy){Text("Aprobar incorporación",color=DashTeal)}}}
                    if(s.demoAccount) Text("Familia y actividad simuladas para explorar TINO. No corresponden a personas ni teléfonos reales.",color=DashMuted,fontSize=10.sp,lineHeight=15.sp)
                }
            }
        }
    }
    }
    if (invitation) AlertDialog(onDismissRequest = { invitation = false }, title = { Text("Invitá a tu red") }, text = { Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if(s.invitationCode.isNotBlank()) InvitationQr(s.invitationCode)
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
        listOf("progress" to "Progreso y prácticas", "help" to "Acompañamiento y avisos", "usage" to "Tiempo de pantalla", "safety" to "Señales de seguridad", "location" to "Ubicación temporal", "websites" to "Sitios visitados").forEach { (key, label) ->
            Row { Text(label, Modifier.weight(1f)); Text(if (allowed.optBoolean(key)) "Compartido" else "Privado", color = if (allowed.optBoolean(key)) DashTeal else DashMuted, fontSize = 13.sp) }
        }
        Text("Estos permisos se eligen desde el dispositivo de esa persona. Ser parte de la familia no habilita a ver su pantalla ni sus mensajes.", color = DashMuted, fontSize = 14.sp, lineHeight = 21.sp)
    } }, confirmButton = { TextButton(onClick = { permissions = false }) { Text("Entendido") } })
    if (recording && member != null) VoiceNoteDialog(member.optString("name"), s.busy, s.error, s.message, { recording = false }) { audio, requestId -> vm.familyAction(id, "voice", audio, requestId) }
    detail?.let { event -> AlertDialog(onDismissRequest={detail=null},title={Text(event.optString("title"),fontSize=22.sp)},text={Column(verticalArrangement=Arrangement.spacedBy(14.dp)){
        if(event.optString("kind")=="website") WebsitePreview(event)
        Text("${familyClock(event.optString("at"))} · ${event.optString("app")} · ${event.optInt("durationMinutes")} min",fontSize=14.sp,color=DashMuted)
        event.optString("domain").takeIf{it.isNotBlank()}?.let{Text(it,color=DashTeal,fontSize=17.sp,fontWeight=FontWeight.SemiBold)}
        Text(event.optString("detail"),fontSize=16.sp,lineHeight=24.sp)
        Text(if(event.optBoolean("helpRequested")) "Solicitó ayuda de TINO" else "Registro de uso compartido",color=DashTeal,fontSize=14.sp)
        Text("El resumen no incluye mensajes, correos ni contenido de conversaciones.",fontSize=12.sp,lineHeight=18.sp,color=DashMuted)
    }},confirmButton={TextButton(onClick={detail=null}){Text("Listo",color=DashTeal)}}) }

}
