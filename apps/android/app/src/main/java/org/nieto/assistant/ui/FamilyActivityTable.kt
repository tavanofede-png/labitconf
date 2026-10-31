package org.nieto.assistant.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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

@Composable internal fun FamilyLockedCard(title: String, detail: String = "Esta persona decidió no compartir estos datos.") {
    FamilySurface {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            FamilyGlyph("lock",Modifier.size(20.dp),DashMuted)
            Text(title,Modifier.weight(1f),fontSize=14.sp,color=DashInk,fontWeight=FontWeight.SemiBold)
            Surface(color=FamilyLine,shape=RoundedCornerShape(6.dp)){Text("Bloqueado",Modifier.padding(horizontal=8.dp,vertical=4.dp),fontSize=11.sp,color=DashMuted)}
        }
        Text(detail,fontSize=12.sp,color=DashMuted,lineHeight=18.sp)
    }
}

@Composable internal fun FamilyMemberStatus(member: JSONObject, permissions: JSONObject, now: String) {
    if(permissions.optBoolean("progress") || permissions.optBoolean("usage")) FamilyStatus(member.optBoolean("online"),relativeFamilyTime(member.optString("lastActiveAt"),now))
    else Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
        FamilyGlyph("lock",Modifier.size(13.dp),DashMuted)
        Text("Actividad bloqueada",fontSize=10.sp,color=DashMuted)
    }
}

@Composable internal fun FamilyAssistanceBadge(event: JSONObject) {
    val partial=event.optString("assistanceLevel")=="partial"
    Surface(color=if(partial) FamilyCream else FamilyMint,shape=RoundedCornerShape(5.dp)) {
        Text(if(partial) "AYUDA PARCIAL DE TINO" else "CON AYUDA DE TINO",Modifier.padding(horizontal=8.dp,vertical=5.dp),
            color=if(partial) FamilyAmber else DashTeal,fontSize=10.sp,lineHeight=14.sp,fontWeight=FontWeight.Bold)
    }
}

/** Only consented TINO involvement, never a general app/browser history. */
@Composable internal fun FamilyActivityTable(member: JSONObject, permissions: JSONObject, detail: (JSONObject)->Unit) {
    if(!permissions.optBoolean("progress")) {
        FamilyLockedCard("Actividad con TINO", "${member.optString("name")} no comparte sus acciones ni el acompañamiento de TINO.")
        return
    }
    var filter by remember(member.optString("id")){mutableIntStateOf(0)}
    val all=member.optJSONArray("activities").objects().filter{it.optBoolean("helpRequested")}
        .filter{permissions.optBoolean("websites") || it.optString("kind")!="website"}
        .filter{permissions.optBoolean("safety") || it.optString("kind")!="safety"}
    val rows=all.filter{when(filter){1->it.optString("assistanceLevel")!="partial";2->it.optString("assistanceLevel")=="partial";else->true}}
    FamilySurface {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("Actividad con TINO",Modifier.weight(1f),fontSize=16.sp,color=DashInk,fontWeight=FontWeight.Bold)
            Text("${rows.size} acciones",fontSize=11.sp,color=DashMuted)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("Todas","Con ayuda","Ayuda parcial").forEachIndexed{i,label->
                FilterChip(selected=filter==i,onClick={filter=i},label={Text(label,fontSize=11.sp)},
                    colors=FilterChipDefaults.filterChipColors(selectedContainerColor=DashTeal,selectedLabelColor=Color.White))
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width=maxOf(maxWidth,560.dp)
            Column(Modifier.horizontalScroll(rememberScrollState()).width(width)) {
                Row(Modifier.fillMaxWidth().background(DashPaper).padding(horizontal=10.dp,vertical=10.dp)) {
                    Text("FECHA / HORA",Modifier.width(100.dp),fontSize=10.sp,color=DashMuted,fontWeight=FontWeight.Bold)
                    Text("ACCIÓN",Modifier.weight(1f),fontSize=10.sp,color=DashMuted,fontWeight=FontWeight.Bold)
                    Text("ACOMPAÑAMIENTO",Modifier.width(184.dp),fontSize=10.sp,color=DashMuted,fontWeight=FontWeight.Bold)
                    Spacer(Modifier.width(20.dp))
                }
                if(rows.isEmpty()) Text("No hay acciones compartidas con este filtro.",Modifier.padding(12.dp),fontSize=13.sp,color=DashMuted)
                rows.forEach{event->
                    Row(Modifier.fillMaxWidth().clickable{detail(event)}.semantics{contentDescription="${event.optString("title")}. ${if(event.optString("assistanceLevel")=="partial") "Ayuda parcial de TINO" else "Con ayuda de TINO"}. Ver detalle"}.heightIn(min=54.dp).padding(horizontal=10.dp,vertical=8.dp),
                        verticalAlignment=Alignment.CenterVertically) {
                        Text(timeLabel(event.optString("at")),Modifier.width(100.dp),fontSize=10.sp,color=DashMuted)
                        Column(Modifier.weight(1f).padding(end=12.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                            Text(event.optString("title"),fontSize=13.sp,lineHeight=18.sp,color=DashInk,fontWeight=FontWeight.SemiBold)
                            Text(event.optString("app"),fontSize=10.sp,color=DashMuted)
                        }
                        Box(Modifier.width(184.dp)){FamilyAssistanceBadge(event)}
                        FamilyGlyph("chevron",Modifier.size(14.dp),DashMuted)
                    }
                    HorizontalDivider(color=FamilyLine)
                }
            }
        }
        Text("Solo acciones con participación de TINO. Los resultados se muestran según la confirmación de la persona.",fontSize=11.sp,lineHeight=16.sp,color=DashMuted)
    }
}
