package org.nieto.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.nieto.assistant.NietoApp

@Composable fun HistoryScreen(app: NietoApp) {
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(revision) {
        loading=true;error=null
        try { app.api.register();rows=app.api.request("/assistant/history").optJSONArray("sessions").objects() }
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){error="No pude consultar tu historial. Revisá la conexión y volvé a intentar."}
        finally{loading=false}
    }
    Text("Tus recorridos",style=MaterialTheme.typography.headlineLarge,color=DashInk)
    Text("Este historial registra guías y tus confirmaciones. No guarda conversaciones, capturas ni archivos.",color=DashMuted)
    if(loading)LinearProgressIndicator(Modifier.fillMaxWidth(),color=DashCyan)
    error?.let{Text(it,color=DashRed)}
    OutlinedButton(onClick={revision++},enabled=!loading){Text("Actualizar historial")}
    if(!loading && error==null && rows.isEmpty())DashboardCard("Tu próxima práctica empieza acá"){Text("Cuando recorras una guía, vas a poder revisarla en este espacio.",color=DashMuted)}
    rows.take(50).forEach { row->
        val flow=row.optString("flowId")
        val title=when(flow){"whatsapp-group"->"Crear un grupo de WhatsApp";"email-send"->"Enviar un correo";"whatsapp-location"->"Compartir ubicación";else->"Asistencia de pantalla"}
        DashboardCard(title,timeLabel(row.optString("createdAt"))) {
            Text(when(row.optString("state")){"reported","completed"->"Confirmaste que terminaste";"cancelled"->"Guía cancelada";else->"Recorrido en curso"},color=DashTeal)
            Text("Duración registrada: ${row.optInt("durationSeconds")/60} min",color=DashMuted)
        }
    }
}
