package org.nieto.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.nieto.assistant.AppState
import org.nieto.assistant.NietoApp
import org.nieto.assistant.presence.FloatingBubbleService
import android.app.role.RoleManager
import kotlinx.coroutines.delay

@Composable fun AssistantPermissions(app: NietoApp, state: AppState, beforeWakeStart: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var pendingBubble by rememberSaveable { mutableStateOf(false) }
    var wakeDisclosure by rememberSaveable { mutableStateOf(false) }
    var bubbleDisclosure by rememberSaveable { mutableStateOf(false) }
    val assistantRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        app.refreshAssistantRole()
        if (app.state.value.assistantSelected) app.startWakeListening()
        else app.presenceNotice("Elegí TINO como asistente de Android para activar Hola TINO.")
    }
    fun startBubble() {
        if (Settings.canDrawOverlays(context)) runCatching {
            context.startForegroundService(Intent(context, FloatingBubbleService::class.java)); app.presenceNotice(null)
        }.onFailure { app.presenceNotice("No pude mostrar la burbuja. Probá otra vez desde TINO.") }
    }
    fun startWake() {
        beforeWakeStart()
        runCatching {
            app.refreshAssistantRole()
            if (!app.state.value.assistantSelected && Build.VERSION.SDK_INT >= 29) {
                val roles = context.getSystemService(RoleManager::class.java)
                assistantRole.launch(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
            } else app.startWakeListening()
            app.presenceNotice(null)
        }.onFailure { app.presenceNotice("No pude activar «Hola TINO». Probá otra vez desde la app abierta.") }
    }
    val voicePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifications = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (mic && notifications) startWake()
        else app.presenceNotice(if (!mic) "Permití el micrófono para activar «Hola TINO»." else "Permití las notificaciones para ver y detener la escucha de TINO.")
    }
    fun requestWakePermissions() {
        val needed = buildList {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) startWake() else voicePermissions.launch(needed.toTypedArray())
    }
    val bubbleNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startBubble() }
    fun finishBubblePermission() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            bubbleNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startBubble()
    }
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingBubble) {
            pendingBubble = false
            if (Settings.canDrawOverlays(context)) finishBubblePermission()
            else app.presenceNotice("Para mostrar la mascota, permití que TINO aparezca sobre otras apps.")
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (app.state.value.bubbleEnabled && !Settings.canDrawOverlays(context)) context.stopService(Intent(context, FloatingBubbleService::class.java))
                if (app.state.value.wakeEnabled && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    app.stopWakeListening()
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    Text("PERMISOS", color = Color(0xFF4C5B61), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 6.dp))
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White) {
        Column {
            PermissionToggle("Burbuja flotante", "La mascota a mano sobre otras apps.", state.bubbleEnabled) { enabled ->
                if (!enabled) context.stopService(Intent(context, FloatingBubbleService::class.java))
                else if (Settings.canDrawOverlays(context)) finishBubblePermission()
                else bubbleDisclosure = true
            }
            HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = Color(0xFFE6EEED))
            PermissionToggle("Hola TINO", state.wakeStatus, state.wakeEnabled) { enabled ->
                if (!enabled) app.stopWakeListening()
                else if (app.settings.read("wakeDisclosurePolicy") == "1") requestWakePermissions()
                else wakeDisclosure = true
            }
        }
    }
    state.presenceNotice?.let { Text(it, color = Color(0xFF4C5B61), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) }
    if (bubbleDisclosure) AlertDialog(onDismissRequest = { bubbleDisclosure = false },
        title = { Text("Permitir la burbuja") },
        text = { Text("Android necesita tu autorización para mostrar la mascota sobre otras apps. Se abrirán sus ajustes; activá el permiso para TINO y volvé. Vas a regresar a este mismo menú. Este paso solo hace falta mientras ese permiso no esté autorizado.") },
        confirmButton = { TextButton(onClick = {
            bubbleDisclosure = false; pendingBubble = true
            runCatching { overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
                .onFailure { pendingBubble = false; app.presenceNotice("No pude abrir el permiso de Android. La burbuja sigue apagada.") }
        }) { Text("Abrir permiso de Android") } },
        dismissButton = { TextButton(onClick = { bubbleDisclosure = false }) { Text("Ahora no") } })
    if (wakeDisclosure) AlertDialog(onDismissRequest = { wakeDisclosure = false },
        title = { Text("Activar «Hola TINO»") },
        text = { Text("TINO usará el micrófono en segundo plano para reconocer «Hola TINO» en este dispositivo. Al oír el sonido, decí tu consulta.\n\nEl audio no se guarda ni se envía. Solo tu consulta, después de la activación, se envía al chat. Android mostrará el micrófono activo y una notificación para detenerlo. Podés apagarlo cuando quieras.") },
        confirmButton = { TextButton(onClick = {
            wakeDisclosure = false
            app.settings.write("wakeDisclosurePolicy", "1")
            requestWakePermissions()
        }) { Text("Activar") } }, dismissButton = { TextButton(onClick = { wakeDisclosure = false }) { Text("Ahora no") } })
}

@Composable private fun PermissionToggle(title: String, subtitle: String, checked: Boolean, change: (Boolean) -> Unit) {
    var visualChecked by remember { mutableStateOf(checked) }
    var interaction by remember { mutableIntStateOf(0) }
    val currentChecked by rememberUpdatedState(checked)
    LaunchedEffect(checked) { visualChecked = checked }
    LaunchedEffect(interaction) {
        if (interaction > 0) {
            delay(650)
            visualChecked = currentChecked
        }
    }
    val toggle: (Boolean) -> Unit = { value ->
        visualChecked = value
        interaction++
        change(value)
    }
    Row(Modifier.fillMaxWidth().toggleable(value = visualChecked, role = Role.Switch, onValueChange = toggle)
        .semantics { stateDescription = if (visualChecked) "Activado" else "Desactivado" }
        .padding(horizontal = 14.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Color(0xFF101416), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(subtitle, color = Color(0xFF4C5B61), fontSize = 13.sp, lineHeight = 18.sp)
        }
        val progress by animateFloatAsState(if (visualChecked) 1f else 0f, animationSpec = tween(280), label = "Permiso")
        val track by animateColorAsState(if (visualChecked) Color(0xFF00D6C9) else Color(0xFFD6DCDE), animationSpec = tween(280), label = "Color del permiso")
        Canvas(Modifier.size(52.dp, 32.dp)) {
            drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
            if (visualChecked) drawLine(Color(0xFF006B64), Offset(11.dp.toPx(), 12.dp.toPx()), Offset(11.dp.toPx(), 20.dp.toPx()), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
            else drawCircle(Color(0xFF4C5B61), radius = 3.5.dp.toPx(), center = Offset(40.dp.toPx(), size.height / 2), style = Stroke(1.5.dp.toPx()))
            drawCircle(Color.White, radius = 13.dp.toPx(), center = Offset((16 + 20 * progress).dp.toPx(), size.height / 2))
        }
    }
}
