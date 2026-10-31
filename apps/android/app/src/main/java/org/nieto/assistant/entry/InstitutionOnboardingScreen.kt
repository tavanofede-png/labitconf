package org.nieto.assistant.entry

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nieto.assistant.R

private val InstitutionCyan = Color(0xFF00D6C9)
private val InstitutionDark = Color(0xFF101416)
private val InstitutionGrey = Color(0xFF4C5B61)
private val InstitutionPaper = Color(0xFFF3F6F6)
private const val InstitutionStepCount = 5

@Composable
fun InstitutionOnboardingScreen(s: EntryState, vm: EntryViewModel) {
    val step = s.profileStep.coerceIn(0, InstitutionStepCount - 1)
    Scaffold(containerColor = InstitutionPaper, topBar = {
        Column(Modifier.statusBarsPadding().fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Image(painterResource(R.drawable.tino_logo), "TINO", Modifier.width(108.dp).height(44.dp))
                Text("Instituciones", color = InstitutionGrey, fontSize = 14.sp)
            }
            LinearProgressIndicator(
                progress = { (step + 1f) / InstitutionStepCount },
                color = InstitutionCyan,
                trackColor = Color(0xFFE0E8E7),
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        }
    }) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f).widthIn(max = 680.dp).fillMaxWidth(),
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(240)) { it / 5 * direction } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(200)) { -it / 5 * direction } + fadeOut(tween(140)))
                },
                label = "Onboarding institucional",
            ) { shownStep ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 32.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = vm::back, enabled = !s.busy, contentPadding = PaddingValues(0.dp)) {
                            Text("← Volver", color = InstitutionDark, fontSize = 16.sp)
                        }
                        Text("${shownStep + 1} / $InstitutionStepCount", color = InstitutionGrey, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    when (shownStep) {
                        0 -> InstitutionTextStep(
                            title = "¿Cómo se llama la institución?",
                            detail = "Usaremos este nombre para identificar su espacio y sus reportes en TINO.",
                            value = s.field("organization"),
                            placeholder = "Nombre de la institución",
                            onValue = { vm.field("organization", it) },
                        )
                        1, 2, 3 -> InstitutionChoiceStep(s, shownStep, vm)
                        else -> InstitutionContactStep(s, vm)
                    }
                }
            }
            Column(
                Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                s.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 16.sp, lineHeight = 24.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = InstitutionCyan)
                if (step == 0 || step == 4) InstitutionButton(
                    label = if (step == 4) "Verificar correo" else "Continuar",
                    enabled = !s.busy,
                    action = vm::nextInstitutionStep,
                )
            }
        }
    }
}

@Composable
private fun InstitutionChoiceStep(s: EntryState, step: Int, vm: EntryViewModel) {
    val question = questionsFor(AccountRole.INSTITUTION)[step - 1]
    InstitutionTitle(question.title, "Elegí la opción que mejor represente a la institución.")
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        question.options.forEachIndexed { index, option ->
            val selected = s.answers[question.id] == index
            Surface(
                color = if (selected) Color(0xFFD9F7F3) else Color.White,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Color(0xFF007C74) else Color(0xFFD8E1E0)),
                modifier = Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton) { vm.institutionChoice(index) },
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(color = if (selected) InstitutionCyan else InstitutionPaper, shape = RoundedCornerShape(8.dp)) {
                        Text(('A' + index).toString(), Modifier.padding(horizontal = 11.dp, vertical = 7.dp), fontWeight = FontWeight.Bold)
                    }
                    Text(option, Modifier.weight(1f), color = InstitutionDark, fontSize = 18.sp, lineHeight = 26.sp)
                    if (selected) Text("✓", color = InstitutionDark, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun InstitutionTextStep(title: String, detail: String, value: String, placeholder: String, onValue: (String) -> Unit) {
    InstitutionTitle(title, detail)
    TextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder, fontSize = 22.sp, color = InstitutionGrey.copy(alpha = .6f)) },
        textStyle = MaterialTheme.typography.headlineMedium.copy(color = InstitutionDark, fontSize = 27.sp, lineHeight = 34.sp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            cursorColor = InstitutionDark,
            focusedIndicatorColor = Color(0xFF007C74),
            unfocusedIndicatorColor = Color(0xFF9EADAB),
        ),
    )
}

@Composable
private fun InstitutionContactStep(s: EntryState, vm: EntryViewModel) {
    InstitutionTitle("Por último, ¿con quién hablamos?", "Estos datos identifican a la persona responsable de la cuenta institucional.")
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1f)) { InstitutionContactField("Nombre y apellido", s.field("name"), { vm.profileField("name", it) }, KeyboardType.Text, "María González") }
                    Box(Modifier.weight(1f)) { InstitutionContactField("Correo institucional", s.field("email"), { vm.field("email", it) }, KeyboardType.Email, "nombre@institucion.org") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1f)) { InstitutionContactField("Teléfono de contacto", s.field("phone"), { vm.field("phone", it) }, KeyboardType.Phone, "+54 11 1234 5678") }
                    Box(Modifier.weight(1f)) { InstitutionTerritoryField(s, vm) }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                InstitutionContactField("Nombre y apellido", s.field("name"), { vm.profileField("name", it) }, KeyboardType.Text, "María González")
                InstitutionContactField("Correo institucional", s.field("email"), { vm.field("email", it) }, KeyboardType.Email, "nombre@institucion.org")
                InstitutionContactField("Teléfono de contacto", s.field("phone"), { vm.field("phone", it) }, KeyboardType.Phone, "+54 11 1234 5678")
                InstitutionTerritoryField(s, vm)
            }
        }
    }
}

@Composable
private fun InstitutionTerritoryField(s: EntryState, vm: EntryViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Provincia o jurisdicción", color = InstitutionGrey, fontSize = 15.sp)
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp)) {
                Text(entryTerritories.find { it.first == s.field("territoryId") }?.second ?: "Elegir provincia", Modifier.weight(1f), fontSize = 18.sp, color = InstitutionDark)
                Text("⌄", color = InstitutionDark)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                entryTerritories.forEach { (id, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { vm.field("territoryId", id); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun InstitutionContactField(label: String, value: String, onValue: (String) -> Unit, keyboardType: KeyboardType, placeholder: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = InstitutionGrey, fontSize = 15.sp)
        TextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(placeholder, color = InstitutionGrey.copy(alpha = .55f)) },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White),
            shape = RoundedCornerShape(14.dp),
        )
    }
}

@Composable
private fun InstitutionTitle(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = InstitutionDark, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 42.sp, modifier = Modifier.semantics { heading() })
        Text(detail, color = InstitutionGrey, fontSize = 18.sp, lineHeight = 27.sp)
    }
}

@Composable
private fun InstitutionButton(label: String, enabled: Boolean, action: () -> Unit) {
    Button(
        onClick = action,
        enabled = enabled,
        shape = CircleShape,
        modifier = Modifier.heightIn(min = 56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = InstitutionCyan, contentColor = InstitutionDark),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 15.dp),
    ) {
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(16.dp))
        Text("→", fontSize = 22.sp)
    }
}
