package org.nieto.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.core.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.onFocusChanged
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
import kotlin.math.sin
import org.nieto.assistant.AppState
import org.nieto.assistant.NietoApp
import org.nieto.assistant.R
import org.nieto.assistant.flow.GuidanceEngine

private val Cyan = Color(0xFF00D6C9)
private val Ink = Color(0xFF101416)
private val Muted = Color(0xFF4C5B61)
private val Paper = Color(0xFFF3F6F6)
private val AssistantBubbleShape = GenericShape { size, _ ->
    val radius = 24f
    val tailWidth = 18f
    val tailTop = size.height - 34f
    moveTo(tailWidth + radius, 0f)
    lineTo(size.width - radius, 0f)
    quadraticBezierTo(size.width, 0f, size.width, radius)
    lineTo(size.width, size.height - radius)
    quadraticBezierTo(size.width, size.height, size.width - radius, size.height)
    lineTo(tailWidth + radius, size.height)
    quadraticBezierTo(tailWidth, size.height, tailWidth, size.height - radius)
    lineTo(tailWidth, tailTop)
    lineTo(0f, size.height - 5f)
    lineTo(tailWidth, size.height - 12f)
    lineTo(tailWidth, radius)
    quadraticBezierTo(tailWidth, 0f, tailWidth + radius, 0f)
    close()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TinoChatScreen(app: NietoApp, state: AppState, listening: Boolean, transcribing: Boolean, voiceDraft: String, onVoice: () -> Unit, onCancelVoice: () -> Unit,
    onStartGuide: (String) -> Unit, onOpenTask: () -> Unit, onSettings: () -> Unit, onFamily: () -> Unit, onMenu: () -> Unit) {
    var message by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val guideSuggested = state.suggestedFlow != null
    var consentText by remember { mutableStateOf<String?>(null) }
    var consentVoice by remember { mutableStateOf(false) }
    var introTyping by remember(state.chat.isEmpty()) { mutableStateOf(state.chat.isEmpty()) }
    val send = { if (message.isNotBlank() && !state.busy && !state.voice.active) { if(app.settings.openAiConsent || app.settings.juryDemo){val sent=message;app.sendMessage(sent,onComplete={accepted -> if(accepted && message==sent)message=""})} else consentText=message } }
    if(consentText!=null || consentVoice) AlertDialog(onDismissRequest={consentText=null;consentVoice=false},title={Text("Hablar con TINO")},text={Text("Para responder, TINO envía tu consulta y, si elegís hablar, tu voz a OpenAI. Podés revocar este permiso en Ajustes. Tu grupo familiar no recibe la conversación.")},confirmButton={TextButton(onClick={app.settings.openAiConsent=true;val draft=consentText;val voice=consentVoice;consentText=null;consentVoice=false;if(draft!=null){app.sendMessage(draft);message=""};if(voice)onVoice()}){Text("Aceptar y continuar")}},dismissButton={TextButton(onClick={consentText=null;consentVoice=false}){Text("Volver")}})
    LaunchedEffect(state.foreground) { while (isActive && state.foreground) { app.refreshFamilyInbox(); delay(15000) } }
    LaunchedEffect(Unit) {
        if (state.chat.isEmpty()) {
            introTyping = true
            delay(2000)
            app.showWelcomeMessage()
            introTyping = false
        }
    }
    LaunchedEffect(state.chat.size, state.busy, guideSuggested, state.task, state.familyInbox.isNotEmpty()) {
        if(state.chat.size>1) list.animateScrollToItem(1 + state.chat.size + (if (state.busy) 1 else 0) + (if (guideSuggested) 1 else 0) + (if (state.task != null) 1 else 0) + (if (state.familyInbox.isNotEmpty()) 1 else 0))
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
                    if (state.voice.phase != org.nieto.assistant.voice.VoicePhase.IDLE && state.voice.message.isNotBlank()) Text(state.voice.message, color = Muted, fontSize = 15.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (listening || transcribing) VoiceRecorderPanel(listening, voiceDraft, onCancelVoice, onVoice)
                    else Surface(shape = RoundedCornerShape(28.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFD7E2E0))) {
                        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextField(value = message, onValueChange = { message = it.take(1000) }, placeholder = { Text("Escribile a TINO…", color = Muted) },
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp).onFocusChanged { if(it.isFocused)app.selectWrittenMode() }.semantics { contentDescription = "Mensaje para TINO" }, maxLines = 4,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp, color = Ink),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Ink))
                            FilledIconButton(onClick = { if(app.settings.openAiConsent || app.settings.juryDemo)onVoice() else consentVoice=true }, enabled = !state.busy || listening, modifier = Modifier.size(52.dp).semantics { contentDescription = if (listening) "Terminar de hablar" else "Hablar con TINO" },
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (listening) Cyan else Color(0xFFE3F9F6), contentColor = Ink)) { ChatIcon("mic") }
                            FilledIconButton(onClick = send, enabled = message.isNotBlank() && !state.busy && !state.voice.active, modifier = Modifier.size(52.dp).semantics { contentDescription = "Enviar mensaje" },
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
                if (introTyping && state.chat.isEmpty()) item { TypingBubble() }
                if (state.familyInbox.isNotEmpty()) item {
                    DashboardCard("Tu grupo está cerca") {
                        state.familyInbox.forEach { incoming ->
                            Text(incoming.optString("author", "Tu grupo familiar"), fontWeight = FontWeight.SemiBold, color = DashTeal)
                            Text(when (incoming.optString("type")) { "voice" -> "Te dejó un aviso de voz."; "location_request" -> "Te pidió que compartas tu ubicación. Vos decidís si hacerlo."; else -> "Te mandó un ping para decirte que está disponible." }, color = DashInk, fontSize = 15.sp)
                            if (incoming.optString("type") == "voice") AudioMessageButton(incoming.optString("audioBase64"))
                            if (incoming.optString("type") == "location_request") TextButton(onClick = { onFamily() }) { Text("Quiero compartir mi ubicación", color = DashTeal) }
                            HorizontalDivider(color = DashPaper)
                        }
                    }
                }
                items(state.chat) { line ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.Bottom) {
                        if (!line.fromUser) {
                            AssistantAvatar()
                            Spacer(Modifier.width(10.dp))
                        }
                        Surface(color = if (line.fromUser) Color(0xFFD8F8F3) else Color.White,
                            shape = if (line.fromUser) RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp) else AssistantBubbleShape,
                            modifier = Modifier.widthIn(max = 620.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                            Column(Modifier.padding(start = if (line.fromUser) 20.dp else 28.dp, end = 20.dp, top = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (line.fromUser) Text("Vos", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(line.text, color = Ink, fontSize = 18.sp, lineHeight = 28.sp)
                                line.attachments.forEach { file -> Text((if (file.mimeType == "application/pdf") "PDF · " else "Imagen · ") + file.name, color = Muted, fontSize = 14.sp) }
                            }
                        }
                    }
                }
                if (state.busy) item { TypingBubble() }
                if (guideSuggested) item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.suggestedFlow == "email-send") state.emailDraft?.let { EmailDraftPreview(it) }
                        Button(onClick = { state.suggestedFlow?.let(onStartGuide) }, enabled = !state.busy && state.task?.active != true, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)) {
                            Text(when (state.suggestedFlow) { "whatsapp-group" -> "Crear mi grupo con TINO"; "email-send" -> "Abrir mi correo y seguir la guía"; else -> "Empezar guía de ubicación" }, Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
                        }
                    }
                }
                if(state.assistanceEnabled) item { TextButton(onClick=app::continueWithoutTino) { Text("Seguir por mi cuenta",color=DashTeal) } }
                state.task?.let { task -> item { TaskGuideCard(task, state.busy, app::advanceTask, onOpenTask, app::repeatTask, app::cancelTask, app::dismissTask, !state.accessibilityConnected || !app.settings.screenConsent, onSettings) } }
            }
        }
    }
}

@Composable private fun TypingBubble() {
    val phase by rememberInfiniteTransition(label = "TINO está escribiendo").animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "Puntos escribiendo"
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        AssistantAvatar()
        Spacer(Modifier.width(10.dp))
        Surface(color = Color.White, shape = AssistantBubbleShape,
            modifier = Modifier.widthIn(min = 88.dp, max = 110.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "TINO está escribiendo" }) {
            Row(Modifier.padding(start = 27.dp, end = 16.dp, top = 18.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { index ->
                    val active = phase.toInt().coerceIn(0, 2) == index
                    Box(Modifier.size(if (active) 10.dp else 8.dp).background(if (active) Cyan else Color(0xFFB8C5C3), CircleShape))
                }
            }
        }
    }
}

@Composable private fun AssistantAvatar() {
    Surface(Modifier.size(42.dp), shape = CircleShape, color = Cyan.copy(alpha = .10f), border = BorderStroke(1.dp, Cyan.copy(alpha = .28f))) {
        Image(painterResource(R.drawable.tino_mascot), contentDescription = "TINO", modifier = Modifier.fillMaxSize())
    }
}

@Composable private fun VoiceRecorderPanel(recording: Boolean, transcript: String, onCancel: () -> Unit, onFinish: () -> Unit) {
    var seconds by remember(recording) { mutableIntStateOf(0) }
    LaunchedEffect(recording) {
        if (recording) while (isActive) { delay(1000); seconds++ }
    }
    val pulse by rememberInfiniteTransition(label = "Grabando audio").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(720, easing = LinearEasing), RepeatMode.Restart), label = "Ondas de voz"
    )
    Surface(shape = RoundedCornerShape(28.dp), color = Color.White, border = BorderStroke(1.dp, if (recording) Cyan else Color(0xFFD7E2E0))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = onCancel, modifier = Modifier.size(44.dp).semantics { contentDescription = "Cancelar grabación" }) { ChatIcon("close") }
            Canvas(Modifier.size(64.dp, 36.dp).semantics { contentDescription = "Audio grabándose" }) {
                repeat(7) { index ->
                    val height = if (recording) (9f + 19f * kotlin.math.abs(sin((pulse * 6.28f) + index * .82f))) else 6f
                    val x = (8 + index * 8).dp.toPx()
                    drawLine(Cyan, Offset(x, center.y - height.dp.toPx() / 2), Offset(x, center.y + height.dp.toPx() / 2), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (recording) "Grabando  ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" else "Transcribiendo…", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text(transcript.ifBlank { if (recording) "Te estoy escuchando…" else "Convirtiendo tu audio en texto…" }, color = Muted, fontSize = 14.sp, maxLines = 2)
            }
            FilledIconButton(onClick = onFinish, enabled = recording, modifier = Modifier.size(48.dp).semantics { contentDescription = "Terminar grabación" }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Cyan, contentColor = Ink)) {
                Canvas(Modifier.size(18.dp)) { drawRoundRect(Ink, cornerRadius = CornerRadius(3.dp.toPx())) }
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
