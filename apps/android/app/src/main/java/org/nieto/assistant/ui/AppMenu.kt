package org.nieto.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.nieto.assistant.NietoApp
import org.nieto.assistant.R
import org.nieto.assistant.entry.AccountRole
import org.nieto.assistant.entry.EntryState

/** One drawer for every authenticated screen, with device-wide permission state. */
@Composable fun AppMenu(app: NietoApp, entry: EntryState, beforeWakeStart: () -> Unit,
    navigate: (AccountRole, Int) -> Unit, logout: () -> Unit,
    content: @Composable (openMenu: () -> Unit) -> Unit) {
    val state by app.state.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val closeThen: (() -> Unit) -> Unit = { action -> scope.launch { drawer.close(); action() } }
    val current = when (entry.role) {
        AccountRole.FAMILY -> "Grupo familiar"
        AccountRole.INSTITUTION -> "Instituciones"
        AccountRole.USER -> when (entry.section) { 2 -> "Guías"; 3 -> "Ajustes"; else -> "TINO Chat" }
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                ModalDrawerSheet(Modifier.width(340.dp).fillMaxHeight(), drawerContainerColor = DashPaper,
                    drawerShape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Image(painterResource(R.drawable.tino_logo), "TINO", Modifier.width(100.dp).height(44.dp))
                            IconButton(onClick = { closeThen {} }, modifier = Modifier.semantics { contentDescription = "Cerrar menú" }) { Text("×", color = DashInk, fontSize = 30.sp) }
                        }
                        Text("Estás en $current", color = DashTeal, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 12.dp))
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            fun go(role: AccountRole, section: Int = 0) = closeThen { beforeWakeStart(); app.stopSpeaking(); navigate(role, section) }
                            val canNavigate = !entry.busy && !state.busy
                            if (current != "TINO Chat") MenuDestination("TINO Chat", canNavigate) { go(AccountRole.USER) }
                            if (current != "Grupo familiar") MenuDestination("Grupo familiar", canNavigate) { go(AccountRole.FAMILY) }
                            if (current != "Instituciones") MenuDestination("Instituciones", canNavigate) { go(AccountRole.INSTITUTION) }
                            if (current != "Guías") MenuDestination("Guías", canNavigate) { go(AccountRole.USER, 2) }
                            if (current != "Ajustes") MenuDestination("Ajustes", canNavigate) { go(AccountRole.USER, 3) }
                            if (entry.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = DashCyan)
                            entry.error?.let { Text(it, color = DashRed, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
                            AssistantPermissions(app, state, beforeWakeStart)
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        LogoutButton(enabled = !entry.busy && !state.busy) { closeThen { beforeWakeStart(); app.stopSpeaking(); logout() } }
                    }
                }
            }
        }) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { content { scope.launch { drawer.open() } } }
        }
    }
    // Registered after the screen's own BackHandler so Back closes the drawer first.
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
}

@Composable private fun MenuDestination(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp), colors = ButtonDefaults.textButtonColors(contentColor = DashInk)) {
        Text(label, Modifier.weight(1f), fontSize = 18.sp)
        Text("›", fontSize = 22.sp, color = DashTeal)
    }
}
