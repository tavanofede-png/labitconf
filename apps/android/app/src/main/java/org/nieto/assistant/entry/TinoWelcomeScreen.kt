package org.nieto.assistant.entry

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nieto.assistant.R

private val WelcomeBackground = Color(0xFFF3F6F6)
private val WelcomePrimary = Color(0xFF00D6C9)
private val WelcomeInk = Color(0xFF101416)
private val WelcomeSecondary = Color(0xFF4C5B61)

/** Native Material 3 welcome, following the team's hand-drawn composition. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TinoWelcomeScreen(
    state: EntryState,
    onRole: (AccountRole) -> Unit,
    onLogin: () -> Unit,
    onConnection: () -> Unit,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Scaffold(containerColor = WelcomeBackground, topBar = {
        BoxWithConstraints(Modifier.fillMaxWidth().statusBarsPadding()) {
            val tablet = maxWidth >= 600.dp
            Row(Modifier.fillMaxWidth().padding(horizontal = if (tablet) 40.dp else 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Image(painterResource(R.drawable.tino_logo), contentDescription = "TINO",
                    modifier = Modifier.width(if (tablet) 142.dp else 116.dp).height(if (tablet) 58.dp else 48.dp))
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(52.dp).semantics { contentDescription = "Abrir menú" }) {
                    Canvas(Modifier.size(26.dp)) {
                        listOf(.25f, .5f, .75f).forEach { y -> drawLine(WelcomeInk, Offset(size.width * .12f, size.height * y), Offset(size.width * .88f, size.height * y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round) }
                    }
                }
            }
        }
    }) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
            val tablet = maxWidth >= 600.dp
            val compactHeight = maxHeight < 580.dp
            val circleSize = when { compactHeight -> 124.dp; tablet -> 224.dp; else -> 184.dp }
            val buttonHeight = if (tablet) 72.dp else 62.dp
            val gap = if (tablet) 18.dp else 14.dp
            val minimumHeight = maxHeight
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = minimumHeight)
                .padding(horizontal = 24.dp, vertical = if (compactHeight) 16.dp else 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                // Official team-supplied mascot; preserve the original transparent artwork.
                Surface(modifier = Modifier.size(circleSize), shape = CircleShape,
                    color = WelcomePrimary.copy(alpha = .08f), border = BorderStroke(1.dp, WelcomePrimary.copy(alpha = .20f))) {
                    Image(painterResource(R.drawable.tino_mascot), contentDescription = "TINO, tu asistente",
                        modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(if (compactHeight) 26.dp else 36.dp))
                Column(Modifier.widthIn(max = if (tablet) 420.dp else 320.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    AccountRole.entries.forEach { role ->
                        Button(onClick = { onRole(role) }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = buttonHeight), shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = WelcomePrimary, contentColor = WelcomeInk,
                                disabledContainerColor = WelcomePrimary.copy(alpha = .32f), disabledContentColor = WelcomeInk.copy(alpha = .5f)),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
                            Text(when (role) { AccountRole.USER -> "Usuario"; AccountRole.FAMILY -> "Grupo familiar"; AccountRole.INSTITUTION -> "Institución" },
                                fontSize = if (tablet) 24.sp else 21.sp, lineHeight = if (tablet) 32.sp else 28.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(Modifier.height(if (tablet) 18.dp else 12.dp))
                TextButton(onClick = onLogin, enabled = !state.busy, modifier = Modifier.heightIn(min = 52.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)) {
                    Text("Ya tengo cuenta", color = WelcomeInk, fontSize = if (tablet) 19.sp else 17.sp, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline)
                }
                if (state.busy) CircularProgressIndicator(Modifier.padding(top = 12.dp).size(26.dp), color = WelcomeInk, strokeWidth = 2.dp)
                state.error?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, fontSize = 16.sp, lineHeight = 24.sp,
                        modifier = Modifier.widthIn(max = 620.dp).padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite })
                }
                state.message?.let { message -> Text(message, color = WelcomeSecondary, fontSize = 16.sp,
                    modifier = Modifier.widthIn(max = 620.dp).padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        }
    }
    if (menuOpen) ModalBottomSheet(onDismissRequest = { menuOpen = false }, containerColor = WelcomeBackground) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tu espacio TINO", fontWeight = FontWeight.Bold, fontSize = 24.sp, modifier = Modifier.padding(bottom = 12.dp))
            WelcomeMenuAction("Ingresar a mi cuenta", !state.busy) { menuOpen = false; onLogin() }
            WelcomeMenuAction("Conexión", !state.busy) { menuOpen = false; onConnection() }
            WelcomeMenuAction("Cerrar menú", true) { menuOpen = false }
        }
    }
}

@Composable private fun WelcomeMenuAction(label: String, enabled: Boolean, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp)) {
        Text(label, color = WelcomeInk, fontSize = 18.sp, modifier = Modifier.weight(1f))
    }
}
