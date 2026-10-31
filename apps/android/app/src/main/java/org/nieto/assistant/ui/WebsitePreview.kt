package org.nieto.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Offline illustrative thumbnail for fictional browsing records, not a claimed screenshot. */
@Composable internal fun WebsitePreview(event: JSONObject, compact: Boolean=false) {
    val domain=event.optString("domain")
    val recipe=domain.contains("cocina")
    val color=if(recipe) Color(0xFFE6C28E) else if(domain.contains("smn")) Color(0xFFB8DED8) else Color(0xFFB9C8DD)
    Surface(shape=RoundedCornerShape(if(compact)8.dp else 14.dp),color=Color.White,modifier=if(compact)Modifier.size(86.dp,58.dp) else Modifier.fillMaxWidth()) {
        Column(Modifier.padding(if(compact)5.dp else 14.dp),verticalArrangement=Arrangement.spacedBy(if(compact)3.dp else 8.dp)) {
            Text(domain,fontSize=if(compact)5.sp else 12.sp,color=DashMuted,maxLines=1)
            Box(Modifier.fillMaxWidth().height(if(compact)17.dp else 90.dp).clip(RoundedCornerShape(5.dp)).background(color)) {
                Text(if(recipe) "Recetas para compartir" else "Información del día",Modifier.padding(if(compact)3.dp else 12.dp),fontSize=if(compact)5.sp else 15.sp,fontWeight=FontWeight.Bold,color=DashInk)
            }
            if(!compact) Text(event.optString("title"),color=DashInk,fontWeight=FontWeight.SemiBold)
            Box(Modifier.fillMaxWidth(.85f).height(if(compact)2.dp else 5.dp).background(DashPaper))
            Box(Modifier.fillMaxWidth(.65f).height(if(compact)2.dp else 5.dp).background(DashPaper))
            if(!compact) Text("Vista previa del sitio",fontSize=10.sp,color=DashMuted)
        }
    }
}
