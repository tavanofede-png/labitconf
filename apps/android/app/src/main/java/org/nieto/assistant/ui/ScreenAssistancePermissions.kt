package org.nieto.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.nieto.assistant.NietoApp

@Composable fun ScreenAssistancePermissions(app: NietoApp) {
    var openAi by remember { mutableStateOf(app.settings.openAiConsent) }
    var screen by remember { mutableStateOf(app.settings.remoteScreenConsent) }
    var images by remember { mutableStateOf(app.settings.screenImagesConsent) }
    DashboardCard("Asistencia con IA", "Elegís qué puede recibir el asistente. Tu grupo familiar no recibe estos datos.") {
        Text("La consulta escrita y la voz se envían a OpenAI para responder. La pantalla requiere un permiso separado.",color=DashMuted)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("Consultas y voz con OpenAI",Modifier.weight(1f));Switch(openAi,{value->openAi=value;app.settings.openAiConsent=value;if(!value){screen=false;images=false;app.revokeOpenAiConsent()}}) }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("Controles de la pantalla",Modifier.weight(1f));Switch(screen,{value->screen=value;app.settings.remoteScreenConsent=value;if(value)app.enableAssistance() else { images=false;app.settings.screenImagesConsent=false;app.stopAssistance() }},enabled=openAi) }
        Text("Se excluyen campos editables y pantallas sensibles. Activá Accesibilidad en Android para usar esta función.",color=DashMuted)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("Captura temporal saneada",Modifier.weight(1f));Switch(images,{value->images=value;app.settings.screenImagesConsent=value},enabled=openAi&&screen) }
        Text("La captura se procesa para el turno actual y no se comparte con instituciones ni se guarda como historial familiar.",color=DashMuted)
    }
}
