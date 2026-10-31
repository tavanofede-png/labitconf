package org.nieto.assistant.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.nieto.assistant.NietoApp
import org.nieto.assistant.family.*

@Composable fun FamilyNotificationSettings(app:NietoApp) {
    val context=LocalContext.current;val store=remember{FamilyStore(context)}
    var enabled by remember{mutableStateOf(store.notificationsEnabled && FamilyNotifications.permitted(context))}
    var busy by remember{mutableStateOf(false)};var notice by remember{mutableStateOf("")}
    val scope=rememberCoroutineScope()
    fun activate() { scope.launch {
        busy=true
        try {
            val access=store.access() ?: error("Volvé a ingresar al grupo familiar.")
            FamilyRepository(context).refresh(access,false)
            store.setNotifications(true);FamilyBackground.schedule(context);enabled=true;notice="Avisos activados. Android puede demorar la comprobación por batería o conexión."
        }catch(cancel:CancellationException){throw cancel}catch(_:Exception){enabled=false;notice="No pude activar los avisos. Actualizá el grupo y volvé a intentar."}finally{busy=false}
    } }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){allowed->if(allowed)activate()else{enabled=false;notice="Los avisos siguen desactivados. Podés revisarlos dentro de la app."}}
    DashboardCard("Avisos de ayuda", "Se comprueban aproximadamente cada 15 minutos. No es un servicio de emergencias.") {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Recibir avisos del grupo",Modifier.weight(1f));Switch(enabled,{value->
            if(!value){store.setNotifications(false);FamilyBackground.stop(context);enabled=false}
            else {FamilyNotifications.createChannel(context);if(Build.VERSION.SDK_INT>=33 && !FamilyNotifications.permitted(context))permission.launch(Manifest.permission.POST_NOTIFICATIONS)else activate()}
        },enabled=!busy)}
        if(notice.isNotBlank())Text(notice,color=DashMuted)
    }
}
