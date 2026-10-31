package org.nieto.assistant.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.time.Instant

@Composable internal fun FamilyMemberPicker(members: List<JSONObject>, selected: String, compact: Boolean, choose: (String)->Unit, invite: ()->Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(if(compact) 10.dp else 18.dp),verticalAlignment=Alignment.Top) {
        members.forEach{person->Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)){
            Box(Modifier.size(if(compact) 38.dp else 56.dp)) {
                FamilyPortrait(person,Modifier.fillMaxSize(),person.optString("id")==selected){choose(person.optString("id"))}
                if(person.has("age") && !person.isNull("age")) Surface(
                    modifier=Modifier.align(Alignment.BottomEnd).offset(x=5.dp,y=4.dp).semantics{contentDescription="Edad: ${person.optInt("age")} años"},
                    color=Color.White,shape=CircleShape,border=BorderStroke(1.dp,FamilyLine)) {
                    Row(Modifier.padding(horizontal=4.dp,vertical=2.dp),horizontalArrangement=Arrangement.spacedBy(2.dp),verticalAlignment=Alignment.CenterVertically) {
                        FamilyGlyph("age",Modifier.size(10.dp))
                        Text("${person.optInt("age")}",fontSize=10.sp,lineHeight=12.sp,fontWeight=FontWeight.Bold,color=DashTeal)
                    }
                }
            }
            Text(person.optString("name"),fontSize=if(compact) 10.sp else 12.sp,color=if(person.optString("id")==selected) DashTeal else DashMuted,fontWeight=if(person.optString("id")==selected) FontWeight.Bold else FontWeight.Medium)
        }}
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick=invite,modifier=Modifier.size(if(compact) 38.dp else 56.dp).semantics{contentDescription="Invitar a alguien al grupo"},contentPadding=PaddingValues(0.dp),shape=CircleShape,border=BorderStroke(1.dp,FamilyLine)){FamilyGlyph("plus",Modifier.size(24.dp))}
            Text("Invitar",color=DashMuted,fontSize=if(compact) 10.sp else 12.sp)
        }
    }
}

@Composable internal fun FamilyNavigation(tab: Int, alerts: Int, vertical: Boolean, choose: (Int)->Unit) {
    val labels=listOf("Resumen","Actividad","Ubicación","Alertas")
    val icons=listOf("summary","activity","pin","shield")
    val item: @Composable (Int)->Unit={i->
        Surface(color=if(tab==i) FamilyMint else Color.Transparent,shape=RoundedCornerShape(13.dp),modifier=Modifier.then(if(vertical) Modifier.fillMaxWidth() else Modifier).clickable{choose(i)}.semantics{role=Role.Tab;selected=tab==i}) {
            Row(Modifier.heightIn(min=48.dp).padding(horizontal=if(vertical) 12.dp else 13.dp,vertical=if(vertical) 10.dp else 14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                if(vertical) FamilyGlyph(icons[i],Modifier.size(19.dp),if(tab==i) DashTeal else DashMuted)
                Text(labels[i],color=if(tab==i) DashTeal else DashMuted,fontSize=13.sp,fontWeight=if(tab==i) FontWeight.Bold else FontWeight.Medium,modifier=if(vertical) Modifier.weight(1f) else Modifier)
                if(i==3 && alerts>0) Surface(color=FamilyCream,shape=CircleShape){Text("$alerts",Modifier.padding(horizontal=6.dp,vertical=2.dp),fontSize=10.sp,color=FamilyAmber,fontWeight=FontWeight.Bold)}
            }
        }
    }
    if(vertical) Column(verticalArrangement=Arrangement.spacedBy(5.dp)){repeat(4){item(it)}}
    else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)){repeat(4){item(it)}}
}

@Composable internal fun FamilyPrivacyFooter(name: String, compact: Boolean=false, permissions: ()->Unit) {
    if(compact) {
        TextButton(onClick=permissions,contentPadding=PaddingValues(0.dp),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
            FamilyGlyph("lock",Modifier.size(15.dp),DashMuted);Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){Text("Compartido por $name",color=DashMuted,fontSize=10.sp,lineHeight=15.sp);Text("Ver permisos",color=DashTeal,fontSize=12.sp,lineHeight=17.sp,fontWeight=FontWeight.SemiBold)}
        }
        return
    }
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){FamilyGlyph("lock",Modifier.size(13.dp),DashMuted);Text("Compartido por $name",color=DashMuted,fontSize=11.sp)}
        TextButton(onClick=permissions,contentPadding=PaddingValues(horizontal=0.dp),modifier=Modifier.heightIn(min=48.dp)){Text("Ver permisos",color=DashTeal,fontSize=12.sp,fontWeight=FontWeight.SemiBold)}
    }
}

@Composable internal fun FamilyResponsivePair(first: @Composable ()->Unit, second: @Composable ()->Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if(maxWidth>=600.dp) Row(horizontalArrangement=Arrangement.spacedBy(14.dp)){Column(Modifier.weight(1f)){first()};Column(Modifier.weight(1f)){second()}}
        else Column(verticalArrangement=Arrangement.spacedBy(14.dp)){first();second()}
    }
}

@Composable internal fun FamilyRecentSites(member: JSONObject, permissions: JSONObject, detail: (JSONObject)->Unit, activity: ()->Unit) {
    FamilySurface {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Últimos sitios visitados",Modifier.weight(1f),fontSize=16.sp,color=DashInk,fontWeight=FontWeight.Bold);TextButton(onClick=activity,contentPadding=PaddingValues(0.dp)){Text("Ver actividad",fontSize=12.sp,color=DashTeal)}}
        if(!permissions.optBoolean("websites")) Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){FamilyGlyph("lock");Text("El detalle de los sitios es privado.",fontSize=13.sp,color=DashMuted,lineHeight=20.sp)}
        else {
            val sites=member.optJSONArray("websites").objects().take(3)
            if(sites.isEmpty()) Text("Todavía no hay sitios compartidos.",color=DashMuted,fontSize=13.sp)
            sites.forEachIndexed { i,event->
                Row(Modifier.fillMaxWidth().clickable{detail(event)}.padding(vertical=4.dp).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    WebsitePreview(event,compact=true)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(event.optString("title"),fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=DashInk);Text(event.optString("domain"),fontSize=11.sp,color=DashMuted)}
                    Text(familyClock(event.optString("at")),fontSize=11.sp,color=DashMuted);FamilyGlyph("chevron",Modifier.size(15.dp),DashMuted)
                }
                if(i<sites.lastIndex) HorizontalDivider(color=FamilyLine)
            }
        }
    }
}

private fun activeFamilyLocation(member: JSONObject, permissions: JSONObject): JSONObject? = member.optJSONObject("location")?.takeIf{permissions.optBoolean("location") && runCatching{Instant.parse(it.getString("expiresAt")).isAfter(Instant.now())}.getOrDefault(false)}

@Composable internal fun FamilyLocationPreview(member: JSONObject, permissions: JSONObject, now: String, open: ()->Unit) {
    val location=activeFamilyLocation(member,permissions)
    FamilySurface(action=open) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){FamilyGlyph(if(location!=null) "pin" else "lock",Modifier.size(20.dp));Text(if(location!=null) "Ubicación compartida" else "Ubicación privada",Modifier.weight(1f),fontSize=14.sp,color=DashInk,fontWeight=FontWeight.Bold);FamilyGlyph("chevron",Modifier.size(15.dp))}
        if(location!=null){FamilyNeighborhood(member.optString("name"),true);Text("${location.optString("label")} · ${relativeFamilyTime(location.optString("updatedAt",location.optString("sharedAt")),now).lowercase()}",fontSize=12.sp,color=DashMuted)}
        else Text("${member.optString("name")} elige cuándo compartirla. Podés enviarle una solicitud.",fontSize=13.sp,lineHeight=20.sp,color=DashMuted)
    }
}

@Composable internal fun FamilySafetyPreview(alerts: List<JSONObject>, permissions: JSONObject, open: ()->Unit) {
    val pending=alerts.filter{it.isNull("resolvedAt")}
    val canView=permissions.optBoolean("safety") || (permissions.optBoolean("help") && alerts.any{it.optString("type")=="help_requested"})
    val active=canView && pending.isNotEmpty()
    FamilySurface(tint=if(active) FamilyCream else Color.White,action=open) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){FamilyGlyph("shield",Modifier.size(22.dp),if(active) FamilyAmber else DashTeal);Text(if(active) "Para revisar juntos" else "Por ahora, todo tranquilo",fontSize=14.sp,fontWeight=FontWeight.Bold,color=DashInk)}
        Text(if(!canView) "Sus señales de seguridad son privadas." else if(active) pending.first().optString("title",pending.first().optString("message")) else if(alerts.isNotEmpty()) "Los avisos compartidos ya fueron atendidos." else "No hay avisos de seguridad pendientes.",fontSize=14.sp,lineHeight=21.sp,color=if(active) FamilyAmber else DashMuted)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(if(active) "Revisar aviso" else "Ver avisos",Modifier.weight(1f),color=if(active) FamilyAmber else DashTeal,fontSize=12.sp,fontWeight=FontWeight.Bold);FamilyGlyph("chevron",Modifier.size(16.dp),if(active) FamilyAmber else DashTeal)}
    }
}

@Composable internal fun FamilyAutonomy(member: JSONObject) {
    val rate=if(member.has("autonomyRate")) member.optInt("autonomyRate") else if(member.optInt("completedFlows")>0) member.optInt("autonomyChecks")*100/member.optInt("completedFlows") else 0
    FamilySurface("Confianza que se construye", "Aprender no es usar más el teléfono: es necesitar menos ayuda.") {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(76.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(progress={rate.coerceIn(0,100)/100f},modifier=Modifier.fillMaxSize(),color=DashTeal,trackColor=FamilyMint,strokeWidth=7.dp);Text("$rate%",color=DashTeal,fontSize=21.sp,fontWeight=FontWeight.Bold)}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)){
                Text("${member.optInt("autonomyChecks")} prácticas por su cuenta",color=DashInk,fontWeight=FontWeight.SemiBold,fontSize=15.sp)
                Text("De ${member.optInt("completedFlows")} tareas completadas desde que usa TINO.",color=DashMuted,fontSize=12.sp,lineHeight=18.sp)
            }
        }
    }
}

@Composable internal fun FamilyActivityPanel(member: JSONObject, permissions: JSONObject, now: String, alerts: List<JSONObject>, detail: (JSONObject)->Unit, safety: ()->Unit) {
    var filter by remember(member.optString("id")){mutableIntStateOf(0)}
    if(permissions.optBoolean("usage")) FamilySurface {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){Text("Tiempo de pantalla",fontSize=16.sp,color=DashInk,fontWeight=FontWeight.Bold);Text(if(permissions.optBoolean("progress")) "${member.optInt("tinoMinutes")} min con TINO" else "Progreso privado",color=DashMuted,fontSize=12.sp)};Text(familyMinutes(member.optInt("screenMinutes")),fontSize=28.sp,color=DashInk,fontWeight=FontWeight.Bold)}
        FamilyAppUsage(member)
    }
    if(!permissions.optBoolean("progress")) FamilySurface("Su actividad es privada","Solo aparece lo que esta persona eligió compartir."){FamilyGlyph("lock",Modifier.size(28.dp))}
    else {
        val activities=member.optJSONArray("activities").objects()
            .filter{permissions.optBoolean("websites") || it.optString("kind")!="website"}
            .filter{permissions.optBoolean("safety") || it.optString("kind")!="safety"}
        val visible=activities.filter{when(filter){1->it.optString("kind")=="website";2->it.optBoolean("assisted");3->it.optBoolean("independent");else->true}}
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            listOf("Todo","Sitios web","Con TINO","Por su cuenta").forEachIndexed{i,label->FilterChip(selected=filter==i,onClick={filter=i},enabled=i!=1||permissions.optBoolean("websites"),label={Text(label,fontSize=12.sp)},shape=CircleShape,colors=FilterChipDefaults.filterChipColors(selectedContainerColor=DashTeal,selectedLabelColor=Color.White,containerColor=Color.White,labelColor=DashMuted))}
        }
        FamilySurface {
            Row(Modifier.fillMaxWidth()){Text("HOY",Modifier.weight(1f),fontSize=11.sp,color=DashMuted,fontWeight=FontWeight.Bold,letterSpacing=1.sp);Text("${visible.size} eventos",fontSize=11.sp,color=DashMuted)}
            if(visible.isEmpty()) Text("No hay eventos compartidos con este filtro.",color=DashMuted,fontSize=14.sp)
            visible.forEachIndexed{i,event->
                val isSafety=event.optString("kind")=="safety"
                val resolved=isSafety&&alerts.find{it.optString("id")==event.optString("alertId")}?.isNull("resolvedAt")==false
                Row(Modifier.fillMaxWidth().clickable{if(isSafety) safety() else detail(event)}.padding(vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top) {
                    Text(familyClock(event.optString("at")),color=DashMuted,fontSize=11.sp,modifier=Modifier.width(36.dp).padding(top=10.dp))
                    Surface(color=if(isSafety) FamilyCream else FamilyMint,shape=RoundedCornerShape(12.dp)){Box(Modifier.size(36.dp),contentAlignment=Alignment.Center){FamilyGlyph(when{isSafety->"shield";event.optString("kind")=="website"->"web";event.optBoolean("independent")->"check";else->"phone"},Modifier.size(18.dp),if(isSafety) FamilyAmber else DashTeal)}}
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(event.optString("title"),color=DashInk,fontWeight=FontWeight.SemiBold,fontSize=14.sp,lineHeight=20.sp)
                        Text(if(isSafety) if(resolved) "Revisado en familia" else "Pendiente de revisión" else "${event.optString("app",event.optString("detail"))}${if(event.optInt("durationMinutes")>0) " · ${event.optInt("durationMinutes")} min" else ""}",color=if(isSafety&&!resolved) FamilyAmber else DashMuted,fontSize=12.sp,lineHeight=18.sp)
                        if(event.optString("domain").isNotBlank()&&!isSafety) Text(event.optString("domain"),color=DashMuted,fontSize=11.sp)
                        if(!isSafety&&(event.optBoolean("assisted")||event.optBoolean("independent"))) Surface(color=FamilyMint,shape=RoundedCornerShape(6.dp)){Text(if(event.optBoolean("independent")) "Lo hizo por su cuenta" else "Con la guía de TINO",Modifier.padding(horizontal=8.dp,vertical=4.dp),fontSize=10.sp,color=DashTeal,fontWeight=FontWeight.SemiBold)}
                    }
                    FamilyGlyph("chevron",Modifier.size(14.dp).padding(top=3.dp),DashMuted)
                }
                if(i<visible.lastIndex) HorizontalDivider(color=FamilyLine)
            }
        }
        if(permissions.optBoolean("usage") || permissions.optBoolean("progress")) FamilyWeeklyChart(member,permissions.optBoolean("usage"),permissions.optBoolean("progress"))
    }
}

@Composable internal fun FamilyLocationPanel(member: JSONObject, permissions: JSONObject, now: String, busy: Boolean, demo: Boolean, request: ()->Unit) {
    val location=activeFamilyLocation(member,permissions)
    if(location==null) FamilySurface("Su ubicación es privada","Cada persona elige cuándo compartirla y por cuánto tiempo.") {
        FamilyGlyph("lock",Modifier.size(32.dp))
        Text("${member.optString("name")} no tiene una ubicación temporal activa.",color=DashMuted,fontSize=15.sp,lineHeight=22.sp)
        Button(onClick=request,enabled=permissions.optBoolean("help")&&!busy,shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=DashCyan,contentColor=DashInk)){Text("Pedir ubicación",fontSize=14.sp)}
        Text("La solicitud no activa la ubicación. Esa persona decide si acepta.",color=DashMuted,fontSize=12.sp,lineHeight=18.sp)
    } else {
        FamilySurface {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(location.optString("label"),Modifier.weight(1f),fontSize=23.sp,color=DashInk,fontWeight=FontWeight.Bold);FamilyStatus(true,"")}
            FamilyNeighborhood(member.optString("name"))
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically){FamilyGlyph("pin");Column(verticalArrangement=Arrangement.spacedBy(4.dp)){Text(location.optString("area",location.optString("label")),fontSize=15.sp,color=DashInk,fontWeight=FontWeight.SemiBold);Text("Actualizada ${relativeFamilyTime(location.optString("updatedAt",location.optString("sharedAt")),now).lowercase()}",color=DashMuted,fontSize=12.sp)}}
            val remaining=runCatching{((Instant.parse(location.getString("expiresAt")).epochSecond-Instant.parse(now).epochSecond)/60).coerceAtLeast(0)}.getOrDefault(0)
            val fraction=runCatching{val end=Instant.parse(location.getString("expiresAt")).epochSecond;val start=Instant.parse(location.getString("sharedAt")).epochSecond;((end-Instant.parse(now).epochSecond).toFloat()/(end-start)).coerceIn(0f,1f)}.getOrDefault(0f)
            Surface(color=FamilyMint,shape=RoundedCornerShape(14.dp)){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){Text("Le quedan $remaining min de ubicación compartida",fontSize=13.sp,color=DashTeal,fontWeight=FontWeight.SemiBold);LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth(),color=DashTeal,trackColor=Color.White);Text("${member.optString("name")} puede dejar de compartirla antes.",fontSize=12.sp,color=DashMuted)}}
            if(demo) Text("Ubicación y mapa ilustrativos. No se está localizando ningún teléfono.",color=DashMuted,fontSize=11.sp,lineHeight=17.sp)
        }
        FamilySurface("El control sigue en sus manos") {Text("La ubicación es temporal. Al vencer el plazo deja de aparecer; no guardamos un recorrido ni mostramos un historial de lugares.",color=DashMuted,fontSize=14.sp,lineHeight=22.sp)}
    }
}

@Composable internal fun FamilyAlertsPanel(alerts: List<JSONObject>, permissions: JSONObject, busy: Boolean, now: String, resolve: (JSONObject)->Unit, activity: ()->Unit) {
    val canView=permissions.optBoolean("safety") || (permissions.optBoolean("help") && alerts.any{it.optString("type")=="help_requested"})
    if(!canView) FamilySurface("Sus avisos son privados","Solo se muestran las señales que esa persona autoriza compartir."){FamilyGlyph("lock",Modifier.size(30.dp))}
    else if(alerts.isEmpty()) FamilySurface("Por ahora, todo tranquilo","No hay avisos de seguridad compartidos para esta persona."){FamilyGlyph("shield",Modifier.size(36.dp));TextButton(onClick=activity){Text("Ver su actividad",color=DashTeal,fontSize=14.sp)}}
    else alerts.forEach{alert->
        val pending=alert.isNull("resolvedAt")
        FamilySurface(tint=if(pending) FamilyCream else Color.White) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){FamilyGlyph(if(pending) "shield" else "check",Modifier.size(26.dp),if(pending) FamilyAmber else DashTeal);Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(if(pending) "PARA REVISAR JUNTOS" else "AVISO ATENDIDO",fontSize=10.sp,color=if(pending) FamilyAmber else DashTeal,fontWeight=FontWeight.Bold,letterSpacing=.6.sp);Text(relativeFamilyTime(alert.optString("createdAt"),now),fontSize=11.sp,color=DashMuted)}}
            Text(alert.optString("title",alert.optString("message")),fontSize=20.sp,lineHeight=27.sp,color=DashInk,fontWeight=FontWeight.Bold)
            Text(alert.optString("message"),fontSize=14.sp,lineHeight=22.sp,color=DashMuted)
            alert.optString("domain").takeIf{it.isNotBlank()}?.let{Surface(color=Color.White,shape=RoundedCornerShape(10.dp)){Text(it,Modifier.padding(12.dp),color=FamilyAmber,fontSize=13.sp,fontWeight=FontWeight.SemiBold)}}
            val signals=alert.optJSONArray("signals")
            if(signals!=null) {Text("Señales que conviene revisar",fontSize=13.sp,color=DashInk,fontWeight=FontWeight.Bold);repeat(signals.length()){i->Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Text("•",color=FamilyAmber);Text(signals.getString(i),fontSize=13.sp,color=DashMuted)}}}
            alert.optString("recommendation").takeIf{it.isNotBlank()}?.let{Text(it,fontSize=14.sp,lineHeight=22.sp,color=DashInk)}
            if(pending) Button(onClick={resolve(alert)},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=DashTeal,contentColor=Color.White)){FamilyGlyph("check",Modifier.size(18.dp),Color.White);Spacer(Modifier.width(8.dp));Text("Marcar como revisado",fontSize=14.sp)}
            else Text("Revisado ${timeLabel(alert.optString("resolvedAt"))}",color=DashTeal,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
            TextButton(onClick=activity){Text("Ver actividad relacionada",color=DashTeal,fontSize=13.sp)}
        }
    }
}
