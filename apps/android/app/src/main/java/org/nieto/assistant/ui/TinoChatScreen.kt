package org.nieto.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.nieto.assistant.AppState
import org.nieto.assistant.NietoApp
import org.nieto.assistant.R
import org.nieto.assistant.flow.GuidanceEngine

private val Cyan = Color(0xFF00D6C9)
private val Ink = Color(0xFF101416)
private val Muted = Color(0xFF4C5B61)
private val Paper = Color(0xFFF3F6F6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TinoChatScreen(app: NietoApp, state: AppState, listening: Boolean, onVoice: () -> Unit,
    onStartGuide: (String) -> Unit, onOpenTask: () -> Unit, onSettings: () -> Unit, onMenu: () -> Unit) {
    var message by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val guideSuggested = state.suggestedFlow != null
    val send = { if (message.isNotBlank() && !state.busy) { app.sendMessage(message); message = "" } }
    LaunchedEffect(state.foreground) { while (isActive && state.foreground) { app.refreshFamilyInbox(); delay(15000) } }
    LaunchedEffect(state.chat.size, state.busy, guideSuggested, state.task, state.familyInbox.isNotEmpty()) {
        list.animateScrollToItem(state.chat.size + (if (state.busy) 1 else 0) + (if (guideSuggested) 1 else 0) + (if (state.task != null) 1 else 0) + (if (state.familyInbox.isNotEmpty()) 1 else 0))
    }
    Scaffold(containerColor = Paper, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TinoHomeLogo(Modifier.width(116.dp).height(48.dp))
            IconButton(onClick = onMenu, modifier = Modifier.size(52.dp).semantics { contentDescription = "Abrir menú" }) { ChatIcon("menu") }
        }
    }, bottomBar = {
        Surface(color = Paper) {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 800.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (listening) Text("Te escucho…", color = Muted, fontSize = 16.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Surface(shape = RoundedCornerShape(28.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFD7E2E0))) {
                        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextField(value = message, onValueChange = { message = it.take(1000) }, placeholder = { Text("Escribile a TINO…", color = Muted) },
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp).semantics { contentDescription = "Mensaje para TINO" }, maxLines = 4,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp, color = Ink),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Ink))
                            FilledIconButton(onClick = onVoice, enabled = !state.busy || listening, modifier = Modifier.size(52.dp).semantics { contentDescription = if (listening) "Terminar de hablar" else "Hablar con TINO" },
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (listening) Cyan else Color(0xFFE3F9F6), contentColor = Ink)) { ChatIcon("mic") }
                            FilledIconButton(onClick = send, enabled = message.isNotBlank() && !state.busy, modifier = Modifier.size(52.dp).semantics { contentDescription = "Enviar mensaje" },
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Cyan, contentColor = Ink, disabledContainerColor = Paper, disabledContentColor = Muted)) { ChatIcon("send") }
                        }
                    }
                }
            }
        }
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(state = list, modifier = Modifier.widthIn(max = 800.dp).fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = CircleShape, color = Cyan.copy(alpha = .08f), modifier = Modifier.size(148.dp)) { Image(painterResource(R.drawable.tino_mascot), "TINO, tu asistente", Modifier.fillMaxSize()) }
                        Text("¿En qué te ayudo hoy?", color = Ink, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    }
                }
                if (state.familyInbox.isNotEmpty()) item {
                    DashboardCard("Tu grupo está cerca") {
                        state.familyInbox.forEach { incoming ->
                            Text(incoming.optString("author", "Tu grupo familiar"), fontWeight = FontWeight.SemiBold, color = DashTeal)
                            Text(when (incoming.optString("type")) { "voice" -> "Te dejó un aviso de voz."; "location_request" -> "Te pidió que compartas tu ubicación. Vos decidís si hacerlo."; else -> "Te mandó un ping para decirte que está disponible." }, color = DashInk, fontSize = 15.sp)
                            if (incoming.optString("type") == "voice") AudioMessageButton(incoming.optString("audioBase64"))
                            if (incoming.optString("type") == "location_request") TextButton(onClick = { app.sendMessage("Quiero compartir mi ubicación por WhatsApp") }) { Text("Quiero compartir mi ubicación", color = DashTeal) }
                            HorizontalDivider(color = DashPaper)
                        }
                    }
                }
                items(state.chat) { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) {
                        Surface(color = if (line.fromUser) Color(0xFFD8F8F3) else Color.White, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = if (line.fromUser) 22.dp else 6.dp, bottomEnd = if (line.fromUser) 6.dp else 22.dp), modifier = Modifier.widthIn(max = 620.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(if (line.fromUser) "Vos" else "TINO", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(line.text, color = Ink, fontSize = 18.sp, lineHeight = 28.sp)
                                line.attachments.forEach { file -> Text((if (file.mimeType == "application/pdf") "PDF · " else "Imagen · ") + file.name, color = Muted, fontSize = 14.sp) }
                            }
                        }
                    }
                }
                if (state.busy) item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(Modifier.size(18.dp), color = Color(0xFF007C74), strokeWidth = 2.dp); Text("TINO está escribiendo…", color = Muted, fontSize = 15.sp) } }
                if (guideSuggested) item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.suggestedFlow == "email-send") state.emailDraft?.let { EmailDraftPreview(it) }
                        Button(onClick = { state.suggestedFlow?.let(onStartGuide) }, enabled = !state.busy && state.task?.active != true, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)) {
                            Text(when (state.suggestedFlow) { "whatsapp-group" -> "Crear mi grupo con TINO"; "email-send" -> "Abrir mi correo y seguir la guía"; else -> "Empezar guía de ubicación" }, Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
                        }
                    }
                }
                state.task?.let { task -> item { TaskGuideCard(task, state.busy, app::advanceTask, onOpenTask, app::repeatTask, app::cancelTask, app::dismissTask, !state.accessibilityConnected || !app.settings.screenConsent, onSettings) } }
            }
        }
    }
}

@Composable private fun ChatIcon(kind: String) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val stroke = 2.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(size.width * x1, size.height * y1), Offset(size.width * x2, size.height * y2), strokeWidth = stroke, cap = StrokeCap.Round)
        when (kind) {
            "close" -> { line(.22f, .22f, .78f, .78f); line(.78f, .22f, .22f, .78f) }
            "menu" -> listOf(.25f, .5f, .75f).forEach { y -> line(.12f, y, .88f, y) }
            "mic" -> {
                drawRoundRect(color, Offset(size.width * .35f, size.height * .06f), Size(size.width * .3f, size.height * .52f), CornerRadius(size.width * .15f), style = Stroke(stroke))
                drawArc(color, 0f, 180f, false, Offset(size.width * .18f, size.height * .28f), Size(size.width * .64f, size.height * .5f), style = Stroke(stroke, cap = StrokeCap.Round))
                line(.5f, .78f, .5f, .94f); line(.32f, .94f, .68f, .94f)
            }
            else -> { line(.5f, .85f, .5f, .16f); line(.24f, .42f, .5f, .16f); line(.76f, .42f, .5f, .16f) }
        }
    }
}
