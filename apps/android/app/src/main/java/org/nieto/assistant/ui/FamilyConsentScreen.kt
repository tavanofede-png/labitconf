package org.nieto.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.nieto.assistant.AppState
import org.nieto.assistant.NietoApp
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

@Composable fun FamilyConsentScreen(app: NietoApp,state: AppState) {
    var code by remember { mutableStateOf("") }
    var permissions by remember(state.familyPermissions.toString()) { mutableStateOf(state.familyPermissions) }
    var revoke by remember { mutableStateOf(false) }
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val qrImage=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri->if(uri!=null)scope.launch{try{code=withContext(Dispatchers.IO){readInvitationImage(context,uri)}}catch(_:Exception){app.status("No pude leer una invitación válida en esa imagen.")}} }
    var locationRequest by remember { mutableStateOf<CancellationSignal?>(null) }
    DisposableEffect(Unit) { onDispose { locationRequest?.cancel() } }
    LaunchedEffect(Unit) { app.refreshFamily() }
    fun locate() {
        val manager=context.getSystemService(LocationManager::class.java)
        if(android.os.Build.VERSION.SDK_INT>=28 && !manager.isLocationEnabled){app.status("Activá la ubicación de Android y volvé a intentarlo.");return}
        val provider=listOf(LocationManager.NETWORK_PROVIDER,LocationManager.GPS_PROVIDER).firstOrNull { manager.isProviderEnabled(it) }
        if(provider==null){app.status("No hay un proveedor de ubicación disponible.");return}
        locationRequest?.cancel();val cancellation=CancellationSignal();locationRequest=cancellation
        app.status("Buscando una ubicación para compartir por 15 minutos…")
        scope.launch { delay(30000);if(locationRequest===cancellation){cancellation.cancel();locationRequest=null;app.status("La búsqueda demoró demasiado. Volvé a intentarlo.")} }
        try {
            if(android.os.Build.VERSION.SDK_INT>=30) manager.getCurrentLocation(provider,cancellation,context.mainExecutor) { point ->
                locationRequest=null
                if(point==null)app.status("No pude obtener una ubicación actual. Probá otra vez.")
                else app.publishFamilyLocation(point.latitude,point.longitude,point.accuracy.toDouble())
            } else app.status("Este dispositivo requiere abrir Maps para compartir una ubicación.")
        } catch(_:SecurityException){app.status("Android no autorizó el acceso a la ubicación.")}
    }
    val locationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if(grants.values.any{it}) locate() else app.status("La ubicación sigue privada. Podés continuar sin compartirla.")
    }
    Text("Mi vínculo familiar",style=MaterialTheme.typography.headlineLarge,color=DashInk)
    Text("Vos elegís qué compartir. Tu pantalla, conversaciones y archivos quedan fuera del grupo.",color=DashMuted)
    if(state.familyName==null) OutlinedTextField(code,{code=it.take(32)},label={Text("Código de invitación")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    if(state.familyName==null) TextButton(onClick={qrImage.launch(arrayOf("image/*"))}){Text("Leer invitación QR desde una imagen")}
    else Text("Tu grupo: ${state.familyName}",style=MaterialTheme.typography.titleLarge)
    val labels=listOf("progress" to "Progreso y prácticas","help" to "Pedidos de ayuda y avisos","usage" to "Tiempo de pantalla y aplicaciones","safety" to "Alertas de enlaces","websites" to "Sitios compartidos","location" to "Ubicación temporal")
    labels.forEach { (key,label) ->
        Row(Modifier.fillMaxWidth().heightIn(min=52.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(label,Modifier.weight(1f).padding(vertical=12.dp),color=DashInk)
            Switch(checked=permissions.optBoolean(key),onCheckedChange={value-> permissions=JSONObject(permissions.toString()).put(key,value)},enabled=!state.busy)
        }
    }
    if(state.familyName==null) Button(onClick={app.acceptFamilyPermissions(code,permissions)},enabled=code.isNotBlank()&&!state.busy,modifier=Modifier.fillMaxWidth()){Text("Aceptar invitación y permisos")}
    else {
        Button(onClick={app.updateFamilyPermissions(permissions)},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("Guardar mis permisos")}
        OutlinedButton(onClick={app.pauseFamily(!state.familyPaused)},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text(if(state.familyPaused)"Reanudar lo compartido" else "Pausar todo lo compartido")}
        Button(onClick=app::requestHelp,enabled=state.allowHelp&&!state.familyPaused&&!state.busy,modifier=Modifier.fillMaxWidth()){Text("Pedir ayuda ahora")}
        if(state.familyPermissions.optBoolean("location")&&!state.familyPaused) {
            OutlinedButton(onClick={
                if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED)locate()
                else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION))
            },enabled=!state.busy&&locationRequest==null,modifier=Modifier.fillMaxWidth()){Text("Compartir ubicación por 15 minutos")}
            TextButton(onClick=app::stopFamilyLocation,enabled=!state.busy){Text("Dejar de compartir ubicación",color=DashRed)}
        }
        TextButton(onClick={revoke=true},enabled=!state.busy){Text("Revocar acceso familiar",color=DashRed)}
    }
    if(state.familyPermissions.optBoolean("usage")) {
        OutlinedButton(onClick={org.nieto.assistant.usage.DeviceUsage.enable(context,true);context.startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))},modifier=Modifier.fillMaxWidth()) { Text("Autorizar tiempo de pantalla en Android") }
        Text("Solo se registran aplicaciones y duración después de tu autorización; no contenido ni conversaciones.",color=DashMuted)
    }
    Text(state.status,color=DashMuted)
    if(revoke) AlertDialog(onDismissRequest={revoke=false},title={Text("¿Revocar el acceso?")},text={Text("Tu grupo dejará de recibir tus datos compartidos y la ubicación temporal.")},confirmButton={TextButton(onClick={revoke=false;app.revokeFamily()}){Text("Revocar",color=DashRed)}},dismissButton={TextButton(onClick={revoke=false}){Text("Volver")}})
}
