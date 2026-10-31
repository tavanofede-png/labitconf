package org.nieto.assistant.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import org.nieto.assistant.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal val FamilyLine = Color(0xFFE2EAE8)
internal val FamilyMint = Color(0xFFE6F7F3)
internal val FamilyAmber = Color(0xFF8A5A13)
internal val FamilyCream = Color(0xFFFFF5E4)
internal val FamilyZone: ZoneId = ZoneId.of("America/Argentina/Buenos_Aires")
internal fun relativeFamilyTime(at: String, now: String): String = runCatching {
    val minutes = ((Instant.parse(now).epochSecond - Instant.parse(at).epochSecond) / 60).coerceAtLeast(0)
    when { minutes < 1 -> "Ahora"; minutes < 60 -> "Hace $minutes min"; minutes < 1440 -> "Hace ${minutes / 60} h"; else -> timeLabel(at) }
}.getOrDefault("Sin actividad reciente")
internal fun familyClock(at: String) = runCatching { Instant.parse(at).atZone(FamilyZone).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrDefault("")
internal fun familyMinutes(n: Int): String = if (n < 60) "$n min" else "${n / 60} h${if (n % 60 > 0) " ${n % 60} min" else ""}"

@Composable internal fun FamilyGlyph(kind: String, modifier: Modifier = Modifier.size(22.dp), tint: Color = DashTeal) {
    Canvas(modifier) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(1.7f, cap = StrokeCap.Round)
            fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(tint, Offset(x,y), Offset(xx,yy), 1.7f, StrokeCap.Round)
            fun path(vararg points: Pair<Float, Float>, close: Boolean = false) = Path().apply { points.forEachIndexed { i,p -> if (i == 0) moveTo(p.first,p.second) else lineTo(p.first,p.second) }; if (close) close() }
            when (kind) {
                "clock" -> { drawCircle(tint,9f,Offset(12f,12f),style=stroke); line(12f,7f,12f,12f); line(12f,12f,16f,14f) }
                "age" -> { drawRoundRect(tint,Offset(3f,5f),Size(18f,16f),CornerRadius(3f),style=stroke);line(3f,10f,21f,10f);line(8f,2f,8f,7f);line(16f,2f,16f,7f);drawCircle(tint,1f,Offset(8f,15f));line(12f,15f,16f,15f) }
                "screen", "phone" -> { drawRoundRect(tint,Offset(6f,2f),Size(12f,20f),CornerRadius(3f),style=stroke); line(10f,18.5f,14f,18.5f) }
                "web" -> { drawCircle(tint,9f,Offset(12f,12f),style=stroke); drawOval(tint,Offset(8f,3f),Size(8f,18f),style=stroke); line(3f,12f,21f,12f); line(5f,7f,19f,7f); line(5f,17f,19f,17f) }
                "pin" -> { val p=Path().apply{moveTo(12f,22f);cubicTo(7f,16f,4f,13f,4f,9f);cubicTo(4f,-1f,20f,-1f,20f,9f);cubicTo(20f,13f,17f,16f,12f,22f);close()};drawPath(p,tint,style=stroke);drawCircle(tint,2.5f,Offset(12f,9f),style=stroke) }
                "shield" -> { drawPath(path(12f to 2f,21f to 6f,20f to 15f,16f to 20f,12f to 23f,8f to 20f,4f to 15f,3f to 6f,close=true),tint,style=stroke);line(8f,12f,11f,15f);line(11f,15f,16f,9f) }
                "bell" -> { val p=Path().apply{moveTo(5f,17f);lineTo(7f,14f);lineTo(7f,9f);cubicTo(7f,2f,17f,2f,17f,9f);lineTo(17f,14f);lineTo(19f,17f);close()};drawPath(p,tint,style=stroke);drawArc(tint,0f,180f,false,Offset(9f,18f),Size(6f,4f),style=stroke) }
                "mic" -> { drawRoundRect(tint,Offset(9f,2f),Size(6f,13f),CornerRadius(3f),style=stroke);drawArc(tint,0f,180f,false,Offset(5f,6f),Size(14f,13f),style=stroke);line(12f,19f,12f,22f);line(8f,22f,16f,22f) }
                "send" -> {drawPath(path(3f to 10f,22f to 2f,15f to 22f,11f to 14f,3f to 10f,close=true),tint,style=stroke);line(11f,14f,22f,2f)}
                "lock" -> {drawRoundRect(tint,Offset(5f,10f),Size(14f,11f),CornerRadius(2f),style=stroke);drawArc(tint,180f,180f,false,Offset(8f,2f),Size(8f,14f),style=stroke);line(12f,14f,12f,17f)}
                "check" -> {line(5f,12f,10f,17f);line(10f,17f,20f,6f)}
                "plus" -> {line(12f,4f,12f,20f);line(4f,12f,20f,12f)}
                "activity" -> {line(2f,13f,7f,13f);line(7f,13f,10f,5f);line(10f,5f,14f,20f);line(14f,20f,17f,10f);line(17f,10f,22f,10f)}
                "spark" -> {drawPath(path(12f to 2f,15f to 9f,22f to 12f,15f to 15f,12f to 22f,9f to 15f,2f to 12f,9f to 9f,close=true),tint,style=stroke)}
                "summary" -> {drawRoundRect(tint,Offset(3f,3f),Size(18f,18f),CornerRadius(3f),style=stroke);line(7f,16f,7f,13f);line(12f,16f,12f,8f);line(17f,16f,17f,10f)}
                else -> {line(9f,5f,16f,12f);line(16f,12f,9f,19f)}
            }
        }
    }
}

/** Small native illustrations: no initials, remote portraits or identifying photos. */
@Composable internal fun FamilyPortrait(person: JSONObject, modifier: Modifier = Modifier.size(64.dp), selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val id = person.optString("id")
    val type = when { id == "marta" -> 0; id == "ricardo" -> 1; id == "lucia" -> 2; else -> 3 }
    Canvas(modifier.clip(CircleShape).then(if(onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .semantics { contentDescription = person.optString("name"); if(onClick != null) this.selected = selected }) {
        val w=size.width; val h=size.height
        drawCircle(when(type){0->Color(0xFFF6DDC0);1->Color(0xFFD9E8E8);else->Color(0xFFE7DFF2)})
        val hair = when(type){0->Color(0xFFE8E6E2);1->Color(0xFF63635E);else->Color(0xFF49372E)}
        val skin = if(type==1) Color(0xFFD3A180) else Color(0xFFE9BC98)
        if(type==2) drawOval(hair,Offset(w*.22f,h*.12f),Size(w*.56f,h*.76f))
        drawOval(hair,Offset(w*.22f,h*.13f),Size(w*.56f,h*.59f))
        drawOval(if(type==0) Color(0xFF9CABA4) else if(type==1) Color(0xFF267B74) else Color(0xFF8876A0),Offset(w*.1f,h*.69f),Size(w*.8f,h*.65f))
        drawRoundRect(skin,Offset(w*.43f,h*.56f),Size(w*.14f,h*.22f),CornerRadius(w*.04f))
        drawOval(skin,Offset(w*.28f,h*.25f),Size(w*.44f,h*.44f))
        if(type==0) {
            listOf(.29f,.4f,.52f,.64f).forEachIndexed{i,x->drawCircle(hair,w*.105f,Offset(w*x,h*(.27f-if(i==1).09f else .04f)))}
            drawCircle(Color(0xFF555650),w*.092f,Offset(w*.39f,h*.45f),style=Stroke(w*.024f));drawCircle(Color(0xFF555650),w*.092f,Offset(w*.61f,h*.45f),style=Stroke(w*.024f));drawLine(Color(0xFF555650),Offset(w*.48f,h*.45f),Offset(w*.52f,h*.45f),w*.02f)
        } else {
            val p=Path().apply{moveTo(w*.24f,h*.35f);cubicTo(w*.28f,h*.02f,w*.75f,h*.13f,w*.73f,h*.34f);cubicTo(w*.57f,h*.38f,w*.5f,h*.22f,w*.44f,h*.25f);close()};drawPath(p,hair)
            if(type==1) drawArc(hair,0f,180f,true,Offset(w*.33f,h*.48f),Size(w*.34f,h*.25f))
        }
        drawCircle(Color(0xFF302B29),w*.022f,Offset(w*.39f,h*.45f));drawCircle(Color(0xFF302B29),w*.022f,Offset(w*.61f,h*.45f))
        drawArc(if(type==1) Color(0xFFECD4BF) else Color(0xFF946858),0f,180f,false,Offset(w*.43f,h*.52f),Size(w*.14f,h*.07f),style=Stroke(w*.018f,cap=StrokeCap.Round))
        if(selected) drawCircle(DashCyan,radius=w/2-2.dp.toPx(),style=Stroke(3.dp.toPx()))
    }
}

@Composable internal fun FamilySurface(title: String? = null, subtitle: String? = null, modifier: Modifier = Modifier, tint: Color = Color.White, action: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.then(if(action != null) Modifier.clickable(onClick=action) else Modifier), shape=RoundedCornerShape(20.dp), color=tint, border=BorderStroke(1.dp,FamilyLine)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            title?.let{Text(it,color=DashInk,fontSize=16.sp,fontWeight=FontWeight.Bold,modifier=Modifier.semantics{heading()})}
            subtitle?.let{Text(it,color=DashMuted,fontSize=13.sp,lineHeight=19.sp)}
            content()
        }
    }
}

@Composable internal fun FamilyStatus(online: Boolean, time: String) {
    Surface(color=if(online) FamilyMint else Color(0xFFEDF0F0),shape=CircleShape) {
        Row(Modifier.padding(horizontal=10.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
            Canvas(Modifier.size(6.dp)){drawCircle(if(online) DashGreen else DashMuted)}
            Text(if(online) "TINO activo" else time,color=if(online) DashTeal else DashMuted,fontSize=11.sp,fontWeight=FontWeight.SemiBold)
        }
    }
}

@Composable internal fun FamilyActionButtons(busy: Boolean, allowed: Boolean, vertical: Boolean, ping: () -> Unit, voice: () -> Unit) {
    val pingContent: @Composable RowScope.()->Unit = { FamilyGlyph("bell",Modifier.size(18.dp),DashInk);Spacer(Modifier.width(8.dp));Text("Enviar ping",fontSize=14.sp,fontWeight=FontWeight.Bold) }
    val voiceContent: @Composable RowScope.()->Unit = { FamilyGlyph("mic",Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Aviso de voz",fontSize=14.sp,fontWeight=FontWeight.SemiBold) }
    if(vertical) Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick=ping,enabled=allowed&&!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=RoundedCornerShape(14.dp),colors=ButtonDefaults.buttonColors(containerColor=DashCyan,contentColor=DashInk),content=pingContent)
        OutlinedButton(onClick=voice,enabled=allowed&&!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,FamilyLine),content=voiceContent)
    } else Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Button(onClick=ping,enabled=allowed&&!busy,modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=RoundedCornerShape(14.dp),contentPadding=PaddingValues(horizontal=8.dp),colors=ButtonDefaults.buttonColors(containerColor=DashCyan,contentColor=DashInk),content=pingContent)
        OutlinedButton(onClick=voice,enabled=allowed&&!busy,modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=RoundedCornerShape(14.dp),contentPadding=PaddingValues(horizontal=8.dp),border=BorderStroke(1.dp,FamilyLine),content=voiceContent)
    }
}

@Composable internal fun FamilyStat(label: String, value: String, detail: String, icon: String, modifier: Modifier=Modifier) {
    FamilySurface(modifier=modifier) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){FamilyGlyph(icon,Modifier.size(18.dp));Text(label,color=DashMuted,fontSize=13.sp,fontWeight=FontWeight.Medium)}
        Text(value,color=DashInk,fontSize=29.sp,lineHeight=35.sp,fontWeight=FontWeight.Bold)
        Text(detail,color=DashMuted,fontSize=12.sp,lineHeight=18.sp)
    }
}

@Composable internal fun FamilyAppUsage(member: JSONObject, compact: Boolean=false) {
    val total=member.optInt("screenMinutes").coerceAtLeast(1)
    val apps=member.optJSONArray("appUsage").objects()
    val colors=listOf(DashTeal,DashCyan,Color(0xFFABBDB9),Color(0xFF8B7FA0),Color(0xFFE4B98B))
    if(apps.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().height(if(compact) 8.dp else 12.dp).clip(CircleShape)) { apps.forEachIndexed{i,a-> Box(Modifier.weight(a.optInt("minutes").toFloat().coerceAtLeast(1f)).fillMaxHeight().background(colors[i%colors.size]))} }
        apps.forEachIndexed { i,a -> Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.size(7.dp)){drawCircle(colors[i%colors.size])}
            Text(a.optString("name"),Modifier.weight(1f),fontSize=13.sp,color=DashMuted)
            Text("${a.optInt("minutes")} min",fontSize=13.sp,color=DashInk,fontWeight=FontWeight.SemiBold)
            if(!compact) Text("${(a.optInt("minutes")*100f/total).roundToInt()}%",fontSize=12.sp,color=DashMuted,modifier=Modifier.width(35.dp))
        } }
    } else Text("No hay detalle por aplicación compartido.",color=DashMuted,fontSize=13.sp)
}

@Composable internal fun FamilyWeeklyChart(member: JSONObject, usage: Boolean, progress: Boolean) {
    if(!usage && !progress) { FamilyLockedCard("Métricas y acompañamiento semanal"); return }
    var selected by remember(member.optString("id")){mutableIntStateOf(6)}
    val days=member.optJSONArray("weeklyDays").objects()
    val screen=member.optJSONArray("weeklyScreen").numbers()
    val tino=member.optJSONArray("weeklyTino").numbers()
    val maximum=(screen+tino).maxOrNull()?.coerceAtLeast(1f)?:1f
    FamilySurface("Su semana, de un vistazo", "Tiempo de pantalla y acompañamiento; TINO forma parte del tiempo total.") {
        Row(horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(if(usage) "● Pantalla" else "Pantalla privada",color=DashTeal,fontSize=12.sp)
            Text(if(progress) "● Con TINO" else "Progreso privado",color=Color(0xFF6DADA3),fontSize=12.sp)
        }
        if(screen.isNotEmpty() || tino.isNotEmpty()) Row(Modifier.fillMaxWidth().height(132.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.Bottom) {
            repeat(7) { i ->
                val label=days.getOrNull(i)?.optString("date")?.let{runCatching{Instant.parse(it).atZone(FamilyZone).format(DateTimeFormatter.ofPattern("EEE",Locale.forLanguageTag("es-AR")))}.getOrNull()}?:listOf("−6","−5","−4","−3","−2","Ayer","Hoy")[i]
                val a=if(usage) screen.getOrElse(i){0f} else 0f;val b=if(progress) tino.getOrElse(i){0f} else 0f
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(if(selected==i) FamilyMint else Color.Transparent).clickable{selected=i}.padding(5.dp)
                    .semantics{contentDescription="$label: ${a.toInt()} minutos de pantalla y ${b.toInt()} con TINO";this.selected=selected==i},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Bottom) {
                    Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(3.dp),verticalAlignment=Alignment.Bottom) {
                        Box(Modifier.width(10.dp).height((a/maximum*88).dp.coerceAtLeast(2.dp)).clip(RoundedCornerShape(topStart=4.dp,topEnd=4.dp)).background(if(selected==i) DashTeal else Color(0xFFB9D3CD)))
                        Box(Modifier.width(10.dp).height((b/maximum*88).dp.coerceAtLeast(2.dp)).clip(RoundedCornerShape(topStart=4.dp,topEnd=4.dp)).background(DashCyan))
                    }
                    Text(if(i==6) "Hoy" else label.replaceFirstChar{it.uppercase()},fontSize=10.sp,color=DashMuted,modifier=Modifier.padding(top=8.dp))
                }
            }
        }
        val selectedDay=days.getOrNull(selected)
        val a=selectedDay?.optInt("screenMinutes")?:screen.getOrElse(selected){0f}.toInt()
        val b=selectedDay?.optInt("tinoMinutes")?:tino.getOrElse(selected){0f}.toInt()
        Text("${if(selected==6) "Hoy" else selectedDay?.optString("date")?.let(::timeLabel)?.substringBefore(" ·")?:"Día seleccionado"} · ${if(usage) familyMinutes(a) else "Uso privado"} · ${if(progress) "$b min con TINO" else "Progreso privado"}",fontSize=12.sp,color=DashTeal)
    }
}

/** Deliberately schematic neighborhood, never a real map provider or live tracking claim. */
@Composable internal fun FamilyNeighborhood(label: String, compact: Boolean=false) {
    Box(Modifier.fillMaxWidth().height(if(compact) 118.dp else 230.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFFEBF0E9))) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(Color(0xFFD8E7D0),Offset(size.width*.08f,size.height*.12f),Size(size.width*.3f,size.height*.4f),CornerRadius(14.dp.toPx()))
            drawRoundRect(Color(0xFFE2E5DF),Offset(size.width*.7f,size.height*.62f),Size(size.width*.35f,size.height*.35f),CornerRadius(10.dp.toPx()))
            listOf(.18f,.58f,.86f).forEach { y->drawLine(Color.White,Offset(-size.width*.1f,size.height*y),Offset(size.width*1.1f,size.height*(y-.15f)),18.dp.toPx()) }
            listOf(.15f,.51f,.88f).forEach { x->drawLine(Color.White,Offset(size.width*x,-size.height*.1f),Offset(size.width*(x-.16f),size.height*1.1f),13.dp.toPx()) }
            val center=Offset(size.width*.59f,size.height*.54f)
            drawCircle(DashCyan.copy(alpha=.14f),52.dp.toPx(),center);drawCircle(DashTeal.copy(alpha=.18f),29.dp.toPx(),center)
            drawCircle(Color.White,12.dp.toPx(),center);drawCircle(DashTeal,8.dp.toPx(),center)
        }
        Text("Plaza",color=Color(0xFF71836B),fontSize=11.sp,modifier=Modifier.align(Alignment.TopStart).padding(start=35.dp,top=25.dp))
        Surface(Modifier.align(Alignment.Center).offset(x=28.dp,y=35.dp),color=Color.White,shape=CircleShape,shadowElevation=2.dp){Text(label,Modifier.padding(horizontal=10.dp,vertical=5.dp),fontSize=11.sp,color=DashTeal,fontWeight=FontWeight.Bold)}
        Text("Vista del mapa",Modifier.align(Alignment.BottomEnd).padding(8.dp),fontSize=9.sp,color=DashMuted)
    }
}
