package org.nieto.assistant.entry

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nieto.assistant.R
import android.app.DatePickerDialog
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val Cyan = Color(0xFF00D6C9)
private val Dark = Color(0xFF101416)
private val Grey = Color(0xFF4C5B61)
private val Paper = Color(0xFFF3F6F6)

/** One answer at a time, with native keyboard actions and retained answers when going back. */
@Composable fun TinoOnboardingScreen(s: EntryState, vm: EntryViewModel, pickPhoto: () -> Unit) {
    val profile = s.page == EntryPage.PROFILE
    val steps = profileStepsFor(s.role, s.emailVerified)
    val index = if (profile) s.profileStep.coerceIn(0, steps.lastIndex) else s.question
    val count = if (profile) steps.size else questionsFor(s.role).size
    val step = steps[s.profileStep.coerceIn(0, steps.lastIndex)]
    LaunchedEffect(s.page, index, s.field("username"), s.usernameEdited) {
        if (profile && step == ProfileStep.USERNAME) vm.lookupUsername()
    }
    Scaffold(containerColor = Paper, topBar = {
        Column(Modifier.statusBarsPadding().fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Image(painterResource(R.drawable.tino_logo), "TINO", Modifier.width(108.dp).height(44.dp))
                Text(s.role.label, color = Grey, fontSize = 14.sp)
            }
            LinearProgressIndicator(progress = { (index + 1f) / count }, color = Cyan, trackColor = Color(0xFFE0E8E7), modifier = Modifier.fillMaxWidth().height(4.dp))
        }
    }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val minHeight = maxHeight
            val tablet = maxWidth >= 600.dp
            AnimatedContent(targetState = index, modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(240)) { it / 5 * direction } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(200)) { -it / 5 * direction } + fadeOut(tween(140)))
                }, label = "Pregunta de onboarding") { slide ->
                val scroll = rememberScrollState()
                Column(Modifier.fillMaxWidth().verticalScroll(scroll).heightIn(min = minHeight).padding(horizontal = if (tablet) 40.dp else 24.dp).padding(top = 12.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = vm::back, enabled = !s.busy, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 12.dp)) { Text("← Volver", color = Dark, fontSize = 16.sp) }
                        Text("${slide + 1} / $count", color = Grey, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(if (tablet) 32.dp else 8.dp))
                    if (profile) {
                        val shownStep = steps[slide.coerceIn(0, steps.lastIndex)]
                        ProfileQuestion(s, shownStep, vm, pickPhoto)
                    } else {
                        val q = questionsFor(s.role)[slide]
                        QuestionTitle(q.title, "Elegí una opción para seguir. Podés volver y cambiarla.", tablet)
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            q.options.forEachIndexed { option, text ->
                                val selected = s.answers[q.id] == option
                                Surface(color = if (selected) Color(0xFFD9F7F3) else Color.White, shape = RoundedCornerShape(18.dp),
                                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Color(0xFF007C74) else Color(0xFFD8E1E0)),
                                    modifier = Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton) { if (slide == index) vm.answer(option) }) {
                                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Surface(color = if (selected) Cyan else Paper, shape = RoundedCornerShape(8.dp)) { Text(('A' + option).toString(), Modifier.padding(horizontal = 11.dp, vertical = 7.dp), fontWeight = FontWeight.Bold, color = Dark) }
                                        Text(text, Modifier.weight(1f), color = Dark, fontSize = 18.sp, lineHeight = 26.sp)
                                        if (selected) Text("✓", color = Dark, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 16.sp, lineHeight = 24.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            s.message?.let { Text(it, color = Grey, fontSize = 16.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cyan)
            if (profile) ProfileContinue(s, step, vm)
            else if (s.answers[questionsFor(s.role)[s.question].id] != null) WizardButton("Continuar", true, vm::nextQuestion)
        }
        }
    }
}

@Composable private fun ProfileQuestion(s: EntryState, step: ProfileStep, vm: EntryViewModel, pickPhoto: () -> Unit) {
    val title = when (step) {
        ProfileStep.NAME -> "¿Cómo te llamás?"
        ProfileStep.USERNAME -> "Tu nombre en TINO."
        ProfileStep.PHONE -> "¿Cuál es tu teléfono?"
        ProfileStep.EMAIL -> "¿Cuál es tu correo?"
        ProfileStep.BIRTH_DATE -> "¿Cuándo naciste?"
        ProfileStep.TERRITORY -> "¿Dónde estás?"
        ProfileStep.PHOTO -> "¿Sumamos una foto?"
        ProfileStep.ORGANIZATION -> "¿Cómo se llama tu institución?"
        ProfileStep.POSITION -> "¿Cuál es tu función?"
    }
    val detail = when (step) {
        ProfileStep.NAME -> if (s.role == AccountRole.FAMILY) "Empecemos por vos, la persona que acompaña." else "Así vamos a dirigirnos a vos."
        ProfileStep.USERNAME -> "Lo creamos con tu nombre. Podés editarlo; es único y te identifica dentro de TINO."
        ProfileStep.PHONE -> "Incluí tu código de país y de área. Este dato es obligatorio."
        ProfileStep.EMAIL -> "Te vamos a enviar un código para verificar tu cuenta, sin contraseña."
        ProfileStep.BIRTH_DATE -> "Para adaptar tu experiencia. Las instituciones solo reciben edades agregadas cuando autorizás compartir estadísticas."
        ProfileStep.TERRITORY -> "Elegí tu provincia o jurisdicción."
        ProfileStep.PHOTO -> "Es opcional. Podés agregarla ahora o más adelante."
        ProfileStep.ORGANIZATION -> "Este será el nombre de tu institución en el portal."
        ProfileStep.POSITION -> "Contanos qué hacés en el equipo. Este dato es opcional."
    }
    QuestionTitle(title, detail, false)
    when (step) {
        ProfileStep.BIRTH_DATE -> {
            val context = LocalContext.current
            val selected = runCatching { LocalDate.parse(s.field("birthDate")) }.getOrDefault(LocalDate.of(1990, 1, 1))
            OutlinedButton(onClick = {
                DatePickerDialog(context, { _, year, month, day ->
                    vm.profileField("birthDate", LocalDate.of(year, month + 1, day).toString()); vm.nextProfile()
                }, selected.year, selected.monthValue - 1, selected.dayOfMonth).apply {
                    datePicker.maxDate = System.currentTimeMillis()
                    datePicker.minDate = java.util.Calendar.getInstance().apply { add(java.util.Calendar.YEAR, -125) }.timeInMillis
                }.show()
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp), shape = RoundedCornerShape(18.dp)) {
                Text(if (s.field("birthDate").isBlank()) "Elegir fecha de nacimiento" else selected.format(DateTimeFormatter.ofPattern("dd / MM / yyyy")), color = Dark, fontSize = 23.sp)
            }
        }
        ProfileStep.PHOTO -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ProfilePhoto(s.field("photo"), s.field("name"))
                OutlinedButton(onClick = pickPhoto, enabled = !s.busy, shape = CircleShape) { Text(if (s.field("photo").isBlank()) "Elegir foto" else "Cambiar foto", color = Dark) }
            }
        }
        ProfileStep.TERRITORY -> {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 66.dp)) {
                    Text(entryTerritories.find { it.first == s.field("territoryId") }?.second ?: "Elegir provincia", Modifier.weight(1f), fontSize = 20.sp, color = Dark); Text("⌄", color = Dark)
                }
                DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                    entryTerritories.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { vm.profileField("territoryId", id); expanded = false; vm.nextProfile() }) }
                }
            }
        }
        else -> {
            val keyboard = when (step) { ProfileStep.PHONE -> KeyboardType.Phone; ProfileStep.EMAIL -> KeyboardType.Email; ProfileStep.USERNAME -> KeyboardType.Ascii; else -> KeyboardType.Text }
            TextField(value = s.field(step.field), onValueChange = { vm.profileField(step.field, it) }, enabled = !s.busy,
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                prefix = if (step == ProfileStep.USERNAME) ({ Text("@", color = Grey, fontSize = 28.sp) }) else null,
                placeholder = { Text(when (step) { ProfileStep.NAME -> "Tu nombre y apellido"; ProfileStep.USERNAME -> "tu.usuario"; ProfileStep.PHONE -> "+54 11 1234 5678"; ProfileStep.EMAIL -> "vos@ejemplo.com"; else -> "Escribí tu respuesta" }, fontSize = 23.sp, color = Grey.copy(alpha = .6f)) },
                textStyle = MaterialTheme.typography.headlineMedium.copy(color = Dark, fontSize = 28.sp, lineHeight = 36.sp),
                keyboardOptions = KeyboardOptions(capitalization = if (keyboard == KeyboardType.Text) KeyboardCapitalization.Words else KeyboardCapitalization.None, autoCorrectEnabled = keyboard == KeyboardType.Text, keyboardType = keyboard, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { vm.nextProfile() }),
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent, cursorColor = Dark, focusedIndicatorColor = Color(0xFF007C74), unfocusedIndicatorColor = Color(0xFF9EADAB)))
        }
    }
    if (step == ProfileStep.USERNAME) {
        val status = when (s.usernameStatus) { "available" -> "✓ Disponible. Este usuario puede ser tuyo."; "checking" -> "Comprobando disponibilidad…"; "taken" -> "Ese usuario ya está en uso. Probá con otro."; "invalid" -> "Entre 3 y 30 letras, números, puntos o guiones bajos."; "error" -> "No pudimos comprobar la disponibilidad."; else -> "Comprobando disponibilidad…" }
        Text(status, color = if (s.usernameStatus in listOf("taken", "invalid", "error")) MaterialTheme.colorScheme.error else Grey, fontSize = 16.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (s.usernameStatus == "error") TextButton(onClick = vm::lookupUsername) { Text("Volver a comprobar", color = Dark) }
    }
}

@Composable private fun ProfileContinue(s: EntryState, step: ProfileStep, vm: EntryViewModel) {
    val enabled = !s.busy && (step != ProfileStep.USERNAME || s.usernameStatus == "available" && s.usernameChecked == s.field("username"))
    WizardButton(if (step == ProfileStep.PHOTO && s.field("photo").isBlank()) "Ahora no" else if (step == ProfileStep.PHOTO && !s.emailVerified) "Verificar mi correo" else if (step == ProfileStep.POSITION && s.field("position").isBlank()) "Omitir este paso" else "Continuar", enabled, vm::nextProfile)
}

@Composable private fun QuestionTitle(title: String, detail: String, tablet: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, color = Dark, fontWeight = FontWeight.Bold, fontSize = if (tablet) 36.sp else 32.sp, lineHeight = 40.sp, modifier = Modifier.semantics { heading() })
        Text(detail, color = Grey, fontSize = 18.sp, lineHeight = 27.sp)
    }
}
@Composable private fun WizardButton(label: String, enabled: Boolean, action: () -> Unit) {
    Button(onClick = action, enabled = enabled, shape = CircleShape, modifier = Modifier.heightIn(min = 56.dp), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 15.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Dark, disabledContainerColor = Cyan.copy(alpha = .22f), disabledContentColor = Grey)) {
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(16.dp)); Text("→", fontSize = 22.sp)
    }
}
