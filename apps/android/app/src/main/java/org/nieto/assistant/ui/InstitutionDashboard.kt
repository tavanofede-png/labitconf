package org.nieto.assistant.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.nieto.assistant.entry.EntryState
import org.nieto.assistant.entry.EntryViewModel
import org.nieto.assistant.entry.entryTerritories
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable fun InstitutionDashboard(s: EntryState, vm: EntryViewModel, onMenu: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var layer by rememberSaveable { mutableIntStateOf(0) }
    var rankingTab by rememberSaveable { mutableIntStateOf(0) }
    var sources by remember { mutableStateOf(false) }
    var workflow by remember { mutableStateOf<JSONObject?>(null) }
    var openDateMenu by remember { mutableStateOf<Int?>(null) }
    var draftStart by rememberSaveable { mutableStateOf(s.dashboardStart) }
    var draftEnd by rememberSaveable { mutableStateOf(s.dashboardEnd) }
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(s.role,lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while(kotlinx.coroutines.currentCoroutineContext().isActive) { kotlinx.coroutines.delay(30000);vm.refreshDashboardIfIdle() }
        }
    }
    val context = LocalContext.current
    val data = s.dashboard
    val dashboardAlpha by animateFloatAsState(if (s.dashboardRefreshing) .35f else 1f, tween(400), label = "dashboardRefresh")
    val metrics = data?.optJSONObject("metrics") ?: JSONObject()
    val trend = data?.optJSONArray("trend").objects()
    val flows = data?.optJSONArray("flows").objects()
    val public = data?.optJSONObject("publicContext") ?: s.context
    var exporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val reportStart = remember(s.dashboardStart) { LocalDate.parse(s.dashboardStart).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
    val reportEnd = remember(s.dashboardEnd) { LocalDate.parse(s.dashboardEnd).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
    val save: (android.net.Uri?, String) -> Unit = { uri, format ->
        if (uri != null) scope.launch {
            exporting = true
            try {
                withContext(Dispatchers.IO) {
                    val bytes = if (format == "pdf") InstitutionReport.pdf(context,reportStart,reportEnd,data) else InstitutionReport.csv(context,reportStart,reportEnd,data)
                    val output = context.contentResolver.openOutputStream(uri) ?: error("No pude abrir el destino.")
                    output.use { it.write(bytes) }
                }
                vm.message("Reporte ${format.uppercase()} guardado: ${InstitutionReport.date(reportStart)} al ${InstitutionReport.date(reportEnd)}.")
            } catch (_: Exception) { vm.message("No pude guardar el reporte. Probá de nuevo.") }
            finally { exporting = false }
        }
    }
    val csvExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { save(it,"csv") }
    val pdfExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { save(it,"pdf") }
    LaunchedEffect(s.dashboardStart, s.dashboardEnd) {
        draftStart = s.dashboardStart
        draftEnd = s.dashboardEnd
    }
    LaunchedEffect(Unit) { vm.state.first { !it.busy }; vm.dashboard() }
    Scaffold(containerColor = DashPaper, topBar = { DashboardHeader("Instituciones", "", onMenu, showMascot = false) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 1400.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(s.field("organization").ifBlank { "Tu institución" }, color = DashInk, fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold)
                Text("Necesidades digitales, aprendizaje y seguridad por territorio.", color = DashMuted, fontSize = 16.sp, lineHeight = 24.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1.4f)) {
                            DashboardDropdown(entryTerritories.find { it.first == s.dashboardTerritory }?.second ?: "Todo el país", listOf("AR" to "Todo el país") + entryTerritories, !s.busy, fieldLabel = "Localidad") { vm.dashboardFilters(territory = it) }
                        }
                        DashboardDateButton("Desde", draftStart, true, openDateMenu == 0, Modifier.weight(1f), { openDateMenu = if (openDateMenu == 0) null else 0 }) {
                            draftStart = it
                            if (LocalDate.parse(draftEnd).isBefore(LocalDate.parse(it))) draftEnd = it
                            openDateMenu = null
                        }
                        DashboardDateButton("Hasta", draftEnd, true, openDateMenu == 1, Modifier.weight(1f), { openDateMenu = if (openDateMenu == 1) null else 1 }) {
                            draftEnd = it
                            if (LocalDate.parse(draftStart).isAfter(LocalDate.parse(it))) draftStart = it
                            openDateMenu = null
                        }
                        Button(
                            onClick = {
                                val from = LocalDate.parse(draftStart).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                                val to = LocalDate.parse(draftEnd).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                                vm.dashboardDateRange(from, to)
                            },
                            enabled = !s.dashboardRefreshing,
                            modifier = Modifier.weight(.72f).heightIn(min = 62.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DashCyan, contentColor = DashInk)
                        ) { Text(if (s.dashboardRefreshing) "Actualizando…" else "Mostrar datos", fontWeight = FontWeight.SemiBold) }
                    }
                DashboardTabs(listOf("Resumen", "Territorio", "Uso y aprendizaje", "Evolución"), tab) { tab = it }
                if (s.busy || s.dashboardRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = DashCyan)
                s.error?.let { DashboardCard("No pudimos actualizar") { Text(it, color = DashRed); TextButton(onClick = vm::dashboard, enabled = !s.busy) { Text("Volver a intentar") } } }
                s.message?.let { Text(it, color = DashTeal, fontSize = 14.sp) }
                if (data == null && !s.busy) DashboardCard("Tu panel está listo") { Text("Actualizá para consultar los indicadores de tu cuenta.", color = DashMuted) }
                if (data != null) {
                    Column(Modifier.graphicsLayer { alpha = dashboardAlpha }, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (data.optBoolean("suppressed")) DashboardCard("Cuidamos la privacidad") { Text("Se necesitan al menos ${data.optInt("minimumCohort", 5)} personas para publicar indicadores. No hay datos individuales en este panel.", color = DashMuted) }
                    when (tab) {
                        0 -> {
                            MetricGrid(listOf(
                                DashboardMetric("Personas activas", metrics.amount("activeUsers"), "Personas distintas en el período"),
                                DashboardMetric("Edad media", metrics.amount("averageAge", " años"), "Edad cumplida · fechas de nacimiento"),
                                DashboardMetric("Pantalla por día", metrics.amount("screenMinutesPerDay", " min"), "Promedio por día-persona activo"),
                                DashboardMetric("Autonomía", metrics.amount("autonomyRate", "%"), "Prácticas sin ayuda / tareas completadas"),
                                DashboardMetric("Tareas solicitadas", metrics.amount("requests"), "Solicitudes de acompañamiento"),
                                DashboardMetric("Actividad vulnerable", metrics.amount("unsafeLinks"), "Eventos agregados autorizados", DashRed)))
                            AdaptivePair({
                                DashboardCard("Dónde se usa TINO") {
                                    ArgentinaMap(data.optJSONArray("territories").objects(), s.dashboardTerritory, 0, s.demoAccount) { vm.dashboardFilters(territory = it, month = s.dashboardMonth); tab = 1 }
                                }
                                DashboardCard("Top 5 de provincias", "Personas activas en el período.") {
                                    val topProvinces = data.optJSONArray("territories").objects()
                                        .filter { !it.isNull("activeUsers") && it.optDouble("activeUsers", 0.0) > 0 }
                                        .sortedByDescending { it.optDouble("activeUsers") }
                                        .take(5)
                                    RankingRows(topProvinces, countKey = "activeUsers")
                                }
                            }, {
                                RankingSheetCard(rankingTab, { rankingTab = it }) {
                                    if (rankingTab == 0) RankingRows(institutionAppRanking(flows), appIcons = true)
                                    else RankingRows(flows) { workflow = it }
                                }
                                DashboardCard("Aprender, a su ritmo", "Evolución de las prácticas realizadas sin ayuda.") {
                                    Text(metrics.amount("autonomyRate", "%"), color = DashTeal, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                                    MiniBarChart(trend.map { it.optDouble("autonomyRate").toFloat() }, labels = trend.map { monthLabel(it.optString("month")) }, description = "Autonomía por mes: " + trend.joinToString { "${it.optString("month")}: ${it.amount("autonomyRate", "%")}" })
                                }
                            })
                        }
                        1 -> {
                            DashboardCard("Territorio") {
                                DashboardTabs(listOf("Ambos", "Uso de TINO", "Actividad Vulnerable"), layer) { layer = it }
                                ArgentinaMap(data.optJSONArray("territories").objects(), s.dashboardTerritory, layer, s.demoAccount) { vm.dashboardFilters(territory = it, month = s.dashboardMonth) }
                                if (s.dashboardTerritory != "AR") TextButton(onClick = { vm.dashboardFilters(territory = "AR", month = s.dashboardMonth) }) { Text("Ver todo el país", color = DashTeal) }
                            }
                            DashboardCard("Detalle por provincia") {
                                data.optJSONArray("territories").objects().filter { s.dashboardTerritory == "AR" || it.optString("id") == s.dashboardTerritory }.sortedByDescending { it.optInt("activeUsers") }.forEach { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(row.optString("name"), Modifier.weight(1f), color = DashInk, fontSize = 14.sp)
                                        Text("${row.amount("activeUsers")} uso", color = DashGreen, fontSize = 13.sp)
                                        Text("${row.amount("unsafeLinks")} alertas", color = DashRed, fontSize = 13.sp)
                                    }; HorizontalDivider(color = DashPaper)
                                    Text("Censo ${row.optString("populationPeriod")}: ${row.amount("population")} habitantes · ENACOM ${row.optString("connectivityPeriod")}: ${row.amount("internetPer100Homes")} accesos / 100 hogares", color = DashMuted, fontSize = 12.sp, lineHeight = 18.sp)
                                    Text("${row.amount("usersPer10000")} usuarios TINO / 10.000 habitantes censados", color = DashTeal, fontSize = 12.sp)
                                }
                            }
                        }
                        2 -> {
                            MetricGrid(listOf(DashboardMetric("Con TINO por día", metrics.amount("tinoMinutesPerDay", " min"), "Tiempo acompañado"), DashboardMetric("Pantalla por día", metrics.amount("screenMinutesPerDay", " min"), "Días-persona activos"), DashboardMetric("Tareas completadas", metrics.amount("completionRate", "%"), "Completadas / solicitadas")))
                            DashboardCard("Tiempo de pantalla y acompañamiento", "Cian: pantalla total · verde oscuro: tiempo con TINO.") {
                                MiniBarChart(trend.map { it.optDouble("screenMinutes").toFloat() }, trend.map { it.optDouble("tinoMinutes").toFloat() }, trend.map { monthLabel(it.optString("month")) }, "Tiempo total de pantalla y tiempo con TINO por mes")
                            }
                            DashboardCard("Edades de quienes usan TINO") { RankingRows(data.optJSONArray("ageBands").objects()) }
                            DashboardCard("Necesidades de acompañamiento") { RankingRows(flows) { workflow = it } }
                        }
                        else -> {
                            MetricGrid(listOf(DashboardMetric("Prácticas sin ayuda", metrics.amount("independent"), "Dentro de tareas completadas"), DashboardMetric("Alertas por 1.000 enlaces", metrics.amount("unsafePerThousand"), "Alertas / enlaces revisados × 1.000", DashRed)))
                            DashboardCard("Participación por mes") { MiniBarChart(trend.map { it.optDouble("activeUsers").toFloat() }, labels = trend.map { monthLabel(it.optString("month")) }, description = "Personas activas mensuales") }
                            DashboardCard("Autonomía en el tiempo") { MiniBarChart(trend.map { it.optDouble("autonomyRate").toFloat() }, labels = trend.map { monthLabel(it.optString("month")) }, description = "Porcentaje de prácticas realizadas sin ayuda por mes") }
                        }
                    }
                    OpenArgDashboard(public, s.dashboardTerritory) { sources = true }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { pdfExport.launch(InstitutionReport.filename(reportStart, reportEnd, "pdf")) },
                            enabled = !exporting,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DashTeal)
                        ) { Text(if (exporting) "Guardando…" else "Exportar PDF", color = Color.White) }
                        OutlinedButton(
                            onClick = { csvExport.launch(InstitutionReport.filename(reportStart, reportEnd, "csv")) },
                            enabled = !exporting,
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                        ) { Text(if (exporting) "Guardando…" else "Exportar CSV", color = DashTeal) }
                    }
                    }
                }
            }
        }
    }
    if (sources) AlertDialog(onDismissRequest = { sources = false }, title = { Text("Fuentes y períodos") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("TINO: actividad consentida y agregada. OpenARG: valores oficiales con el período original.")
            public?.optJSONArray("indicators").objects().forEach { indicator ->
                Text("${indicator.optString("label")}\n${indicator.amount("value")} ${indicator.optString("unit")} · ${indicator.optString("period")}", fontWeight = FontWeight.SemiBold)
                Text(indicator.optString("sourceTitle"), color = DashMuted)
                TextButton(onClick = { val uri = Uri.parse(indicator.optString("sourceUrl")); if (uri.scheme == "https") context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }) { Text("Abrir fuente oficial ↗", color = DashTeal) }
            }
            Text("Cartografía provincial: Georef / IGN. El mapa muestra agregados provinciales, no personas ni sitios concretos.")
            public?.optJSONArray("sources").objects().forEach { source ->
                Text("${source.optString("title")} · ${source.optString("period")}", fontWeight = FontWeight.SemiBold)
                Text("Consulta MCP: ${timeLabel(source.optString("retrievedAt"))}", color = DashMuted, fontSize = 12.sp)
                TextButton(onClick = { val uri = Uri.parse(source.optString("url")); if (uri.scheme == "https") context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }) { Text("Abrir publicación original ↗", color = DashTeal) }
            }
            public?.optJSONArray("notes")?.let { notes -> (0 until notes.length()).forEach { Text(notes.optString(it), color = DashMuted, fontSize = 13.sp, lineHeight = 19.sp) } }
        }
    }, confirmButton = { TextButton(onClick = { sources = false }) { Text("Listo") } })
    workflow?.let { item -> AlertDialog(onDismissRequest = { workflow = null }, title = { Text(item.optString("name")) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${item.amount("count")} solicitudes en el período.", fontWeight = FontWeight.Bold)
        val denominator = flows.sumOf { it.optDouble("count") }.coerceAtLeast(1.0)
        Text("${number(item.optDouble("count") / denominator * 100)}% de las tareas solicitadas.")
    } }, confirmButton = { TextButton(onClick = { workflow = null }) { Text("Listo") } }) }
}

private val DashboardDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DashboardDateButton(label: String, isoDate: String, enabled: Boolean, expanded: Boolean, modifier: Modifier, toggle: () -> Unit, choose: (String) -> Unit) {
    val formatted = runCatching { LocalDate.parse(isoDate).format(DashboardDateFormat) }.getOrDefault(isoDate)
    Box(modifier) {
        OutlinedButton(onClick = toggle, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, color = DashMuted, fontSize = 12.sp)
                Text(formatted, color = DashInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("▣", color = DashTeal, fontSize = 20.sp)
        }
        val initialMillis = remember(isoDate) { LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        LaunchedEffect(pickerState.selectedDateMillis) {
            val selected = pickerState.selectedDateMillis ?: return@LaunchedEffect
            if (selected != initialMillis) choose(java.time.Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate().toString())
        }
        DropdownMenu(expanded = expanded, onDismissRequest = toggle, modifier = Modifier.width(360.dp)) {
            DatePicker(
                state = pickerState,
                modifier = Modifier.width(360.dp),
                title = null,
                headline = null,
                showModeToggle = false,
                colors = DatePickerDefaults.colors(containerColor = Color.White, selectedDayContainerColor = DashCyan, selectedDayContentColor = DashInk)
            )
        }
    }
}

@Composable internal fun AdaptivePair(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    BoxWithConstraints {
        if (maxWidth >= 850.dp) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) { first() }; Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) { second() } }
        else Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { first(); second() }
    }
}
@Composable internal fun DashboardDropdown(label: String, options: List<Pair<String, String>>, enabled: Boolean = true, fieldLabel: String? = null, choose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = if (fieldLabel == null) 50.dp else 62.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                fieldLabel?.let { Text(it, color = DashMuted, fontSize = 12.sp) }
                Text(label, color = DashInk, fontSize = if (fieldLabel == null) 14.sp else 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Text("⌄", color = DashTeal)
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 360.dp)) { options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; choose(id) }) } }
    }
}
@Composable private fun PublicContextCard(data: JSONObject?, demo: Boolean, sources: () -> Unit) {
    DashboardCard("OpenARG + contexto territorial", "Fuentes oficiales separadas de la actividad de TINO.") {
        DataPill(if (data?.optString("status") == "live") "OpenARG · consulta en vivo" else "OpenARG · referencia verificada")
        data?.optJSONArray("indicators").objects().forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text(item.optString("label"), Modifier.weight(1f), color = DashInk, fontSize = 14.sp); Text(item.amount("value"), color = DashTeal, fontWeight = FontWeight.Bold) }
            Text("${item.optString("sourceTitle")} · período ${item.optString("period")}", color = DashMuted, fontSize = 12.sp)
        }
        data?.optJSONArray("crosses").objects().forEach { cross ->
            HorizontalDivider(color = DashPaper)
            Text(cross.optString("label"), fontWeight = FontWeight.SemiBold, color = DashInk, fontSize = 14.sp)
            Text(cleanInstitutionCopy("${cross.amount("value")} ${cross.optString("unit")}"), color = DashTeal, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(cleanInstitutionCopy(cross.optString("note")), color = DashMuted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Text("INDEC · Censo ${data?.optString("populationPeriod").orEmpty()}  |  ENACOM · ${data?.optString("connectivityPeriod").orEmpty()}\nCobertura oficial: 24 jurisdicciones. Los accesos por 100 hogares no son un porcentaje de hogares conectados.", color = DashMuted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("Cada indicador conserva su fuente, período y cobertura. Los cruces se presentan de forma agregada.", color = DashMuted, fontSize = 12.sp, lineHeight = 18.sp)
        TextButton(onClick = sources) { Text("Ver fuentes y metodología ↗", color = DashTeal) }
    }
}

private fun cleanInstitutionCopy(value: String): String = value
    .replace("usuarios demo", "usuarios TINO", ignoreCase = true)
    .replace("TINO simulado", "TINO", ignoreCase = true)
    .replace("simulado", "agregado", ignoreCase = true)
