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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.nieto.assistant.entry.EntryState
import org.nieto.assistant.entry.EntryViewModel
import org.nieto.assistant.entry.entryTerritories

@Composable fun InstitutionDashboard(s: EntryState, vm: EntryViewModel, onMenu: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var layer by rememberSaveable { mutableIntStateOf(0) }
    var sources by remember { mutableStateOf(false) }
    var workflow by remember { mutableStateOf<JSONObject?>(null) }
    val context = LocalContext.current
    val data = s.dashboard
    val metrics = data?.optJSONObject("metrics") ?: JSONObject()
    val trend = data?.optJSONArray("trend").objects()
    val flows = data?.optJSONArray("flows").objects()
    val public = data?.optJSONObject("publicContext") ?: s.context
    var exportDialog by remember { mutableStateOf(false) }
    var exportStart by rememberSaveable { mutableStateOf<Long?>(null) }
    var exportEnd by rememberSaveable { mutableStateOf<Long?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val save: (android.net.Uri?, String) -> Unit = { uri, format ->
        val from = exportStart; val to = exportEnd
        if (uri != null && from != null && to != null) scope.launch {
            exporting = true
            try {
                withContext(Dispatchers.IO) {
                    val bytes = if (format == "pdf") InstitutionReport.pdf(context,from,to) else InstitutionReport.csv(context,from,to)
                    val output = context.contentResolver.openOutputStream(uri) ?: error("No pude abrir el destino.")
                    output.use { it.write(bytes) }
                }
                vm.message("Reporte ${format.uppercase()} guardado: ${InstitutionReport.date(from)} al ${InstitutionReport.date(to)}.")
            } catch (_: Exception) { vm.message("No pude guardar el reporte. Probá de nuevo.") }
            finally { exporting = false }
        }
    }
    val csvExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { save(it,"csv") }
    val pdfExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { save(it,"pdf") }
    if (exportDialog) InstitutionExportDialog(exportStart,exportEnd,{ exportDialog = false }) { from,to,format ->
        exportStart = from; exportEnd = to; exportDialog = false
        if (format == "pdf") pdfExport.launch(InstitutionReport.filename(from,to,format)) else csvExport.launch(InstitutionReport.filename(from,to,format))
    }
    LaunchedEffect(Unit) { vm.state.first { !it.busy }; vm.dashboard() }
    Scaffold(containerColor = DashPaper, topBar = { DashboardHeader("Instituciones", s.field("organization").ifBlank { "Inteligencia para acompañar" }, onMenu) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 1400.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("Entender para\nacompañar mejor.", color = DashInk, fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold)
                Text("Necesidades digitales, aprendizaje y seguridad por territorio.", color = DashMuted, fontSize = 16.sp, lineHeight = 24.sp)
                if (s.demoAccount) DataPill("TINO simulado · escenario de seis meses", true) else DataPill("TINO · estadísticas consentidas y agregadas")
                DashboardDropdown(entryTerritories.find { it.first == s.dashboardTerritory }?.second ?: "Todo el país", listOf("AR" to "Todo el país") + entryTerritories, !s.busy) { vm.dashboardFilters(territory = it, month = s.dashboardMonth) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { DashboardDropdown("Últimos ${s.dashboardMonths} meses", listOf("6" to "Últimos 6 meses", "3" to "Últimos 3 meses", "1" to "Último mes"), !s.busy) { vm.dashboardFilters(months = it.toInt()) } }
                    Box(Modifier.weight(1f)) { DashboardDropdown(if (s.dashboardMonth.isBlank()) "Todos los meses" else monthLabel(s.dashboardMonth), listOf("" to "Todos los meses") + (data?.optJSONArray("availableMonths")?.let { a -> (0 until a.length()).map { a.getString(it) to monthLabel(a.getString(it)) } } ?: emptyList()), !s.busy && s.demoAccount) { vm.dashboardFilters(month = it) } }
                }
                DashboardTabs(listOf("Resumen", "Territorio", "Uso y aprendizaje", "Evolución"), tab) { tab = it }
                if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = DashCyan)
                s.error?.let { DashboardCard("No pudimos actualizar") { Text(it, color = DashRed); TextButton(onClick = vm::dashboard, enabled = !s.busy) { Text("Volver a intentar") } } }
                s.message?.let { Text(it, color = DashTeal, fontSize = 14.sp) }
                if (data == null && !s.busy) DashboardCard("Tu panel está listo") { Text("Actualizá para consultar los indicadores de tu cuenta.", color = DashMuted) }
                if (data != null) {
                    if (data.optBoolean("suppressed")) DashboardCard("Cuidamos la privacidad") { Text("Se necesitan al menos ${data.optInt("minimumCohort", 5)} personas para publicar indicadores. No hay datos individuales en este panel.", color = DashMuted) }
                    when (tab) {
                        0 -> {
                            MetricGrid(listOf(
                                DashboardMetric("Personas activas", metrics.amount("activeUsers"), "Personas distintas en el período"),
                                DashboardMetric("Edad media", metrics.amount("averageAge", " años"), "Edad cumplida · fechas de nacimiento"),
                                DashboardMetric("Pantalla por día", metrics.amount("screenMinutesPerDay", " min"), "Promedio por día-persona activo"),
                                DashboardMetric("Autonomía", metrics.amount("autonomyRate", "%"), "Prácticas sin ayuda / tareas completadas"),
                                DashboardMetric("Tareas solicitadas", metrics.amount("requests"), "Solicitudes de acompañamiento"),
                                DashboardMetric("Enlaces inseguros", metrics.amount("unsafeLinks"), "Alertas del escenario · no sitios reales", DashRed)))
                            AdaptivePair({
                                DashboardCard("Dónde se usa TINO", "Verde: uso · rojo: alertas de enlaces") {
                                    ArgentinaMap(data.optJSONArray("territories").objects(), s.dashboardTerritory, 0) { vm.dashboardFilters(territory = it, month = s.dashboardMonth); tab = 1 }
                                    TextButton(onClick = { tab = 1 }) { Text("Explorar territorio →", color = DashTeal) }
                                }
                            }, {
                                DashboardCard("Las 10 tareas más pedidas", "Elegí una tarea para ver su participación.") { RankingRows(flows) { workflow = it } }
                                DashboardCard("Aprender, a su ritmo", "Evolución de las prácticas realizadas sin ayuda.") {
                                    Text(metrics.amount("autonomyRate", "%"), color = DashTeal, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                                    MiniBarChart(trend.map { it.optDouble("autonomyRate").toFloat() }, labels = trend.map { monthLabel(it.optString("month")) }, description = "Autonomía por mes: " + trend.joinToString { "${it.optString("month")}: ${it.amount("autonomyRate", "%")}" })
                                }
                            })
                        }
                        1 -> {
                            DashboardCard("Territorio", "Tocá un círculo o elegí una provincia en el filtro.") {
                                DashboardTabs(listOf("Ambos", "Uso de TINO", "Enlaces inseguros"), layer) { layer = it }
                                ArgentinaMap(data.optJSONArray("territories").objects(), s.dashboardTerritory, layer) { vm.dashboardFilters(territory = it, month = s.dashboardMonth) }
                                if (s.dashboardTerritory != "AR") TextButton(onClick = { vm.dashboardFilters(territory = "AR", month = s.dashboardMonth) }) { Text("Ver todo el país", color = DashTeal) }
                            }
                            DashboardCard("Detalle por provincia", "Las alertas y el uso de TINO son simulados. Los puntos corresponden a referencias provinciales.") {
                                data.optJSONArray("territories").objects().filter { s.dashboardTerritory == "AR" || it.optString("id") == s.dashboardTerritory }.sortedByDescending { it.optInt("activeUsers") }.forEach { row ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(row.optString("name"), Modifier.weight(1f), color = DashInk, fontSize = 14.sp)
                                        Text("${row.amount("activeUsers")} uso", color = DashGreen, fontSize = 13.sp)
                                        Text("${row.amount("unsafeLinks")} alertas", color = DashRed, fontSize = 13.sp)
                                    }; HorizontalDivider(color = DashPaper)
                                    Text("Censo ${row.optString("populationPeriod")}: ${row.amount("population")} habitantes · ENACOM ${row.optString("connectivityPeriod")}: ${row.amount("internetPer100Homes")} accesos / 100 hogares", color = DashMuted, fontSize = 12.sp, lineHeight = 18.sp)
                                    Text("Escenario: ${row.amount("usersPer10000")} usuarios TINO / 10.000 habitantes censados", color = DashTeal, fontSize = 12.sp)
                                }
                            }
                        }
                        2 -> {
                            MetricGrid(listOf(DashboardMetric("Con TINO por día", metrics.amount("tinoMinutesPerDay", " min"), "Tiempo acompañado"), DashboardMetric("Pantalla por día", metrics.amount("screenMinutesPerDay", " min"), "Días-persona activos"), DashboardMetric("Tareas completadas", metrics.amount("completionRate", "%"), "Completadas / solicitadas")))
                            DashboardCard("Tiempo de pantalla y acompañamiento", "Cian: pantalla total · verde oscuro: tiempo con TINO. Series del mismo período.") {
                                MiniBarChart(trend.map { it.optDouble("screenMinutes").toFloat() }, trend.map { it.optDouble("tinoMinutes").toFloat() }, trend.map { monthLabel(it.optString("month")) }, "Tiempo total de pantalla y tiempo con TINO por mes")
                            }
                            DashboardCard("Edades de quienes usan TINO", "Todas las personas que necesitan ayuda digital.") { RankingRows(data.optJSONArray("ageBands").objects()) }
                            DashboardCard("Necesidades de acompañamiento") { RankingRows(flows) { workflow = it } }
                        }
                        else -> {
                            MetricGrid(listOf(DashboardMetric("Prácticas sin ayuda", metrics.amount("independent"), "Dentro de tareas completadas"), DashboardMetric("Alertas por 1.000 enlaces", metrics.amount("unsafePerThousand"), "Alertas / enlaces revisados × 1.000", DashRed)))
                            DashboardCard("Participación por mes") { MiniBarChart(trend.map { it.optDouble("activeUsers").toFloat() }, labels = trend.map { monthLabel(it.optString("month")) }, description = "Personas activas mensuales") }
                            DashboardCard("Autonomía en el tiempo") { MiniBarChart(trend.map { it.optDouble("autonomyRate").toFloat() }, labels = trend.map { monthLabel(it.optString("month")) }, description = "Porcentaje de prácticas realizadas sin ayuda por mes") }
                            DashboardCard("Cómo leer esta evolución") { Text(if (s.demoAccount) "Este escenario sirve para explorar decisiones y demostrar el producto. La evolución de TINO es simulada: no demuestra impacto causal ni representa resultados de un programa real." else "Una evolución descriptiva no demuestra causalidad. Compará períodos, cobertura y composición de las cohortes.", color = DashMuted, fontSize = 15.sp, lineHeight = 23.sp) }
                        }
                    }
                    PublicContextCard(public, s.demoAccount, { sources = true })
                    OutlinedButton(onClick = { exportDialog = true }, enabled = !s.busy && !exporting, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (exporting) "Guardando reporte…" else "Exportar reporte · CSV / PDF", color = DashTeal) }
                    Text("Datos agregados, nunca personas. Las fechas de nacimiento y las ubicaciones individuales no se muestran a instituciones.", color = DashMuted, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
    }
    if (sources) AlertDialog(onDismissRequest = { sources = false }, title = { Text("Fuentes y períodos") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("TINO: ${if (s.demoAccount) "seis meses de actividad simulada" else "actividad consentida"}. OpenARG: valores oficiales con el período original.")
            public?.optJSONArray("indicators").objects().forEach { indicator ->
                Text("${indicator.optString("label")}\n${indicator.amount("value")} ${indicator.optString("unit")} · ${indicator.optString("period")}", fontWeight = FontWeight.SemiBold)
                Text(indicator.optString("sourceTitle"), color = DashMuted)
                TextButton(onClick = { val uri = Uri.parse(indicator.optString("sourceUrl")); if (uri.scheme == "https") context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }) { Text("Abrir fuente oficial ↗", color = DashTeal) }
            }
            Text("Cartografía provincial: Georef / IGN. El mapa muestra agregados provinciales del escenario, no personas ni sitios concretos.")
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
        Text("Una oportunidad para diseñar talleres y materiales de acompañamiento. La demanda no indica por sí sola que la tarea sea más difícil.", color = DashMuted)
    } }, confirmButton = { TextButton(onClick = { workflow = null }) { Text("Listo") } }) }
}

@Composable internal fun AdaptivePair(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    BoxWithConstraints {
        if (maxWidth >= 850.dp) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) { first() }; Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) { second() } }
        else Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { first(); second() }
    }
}
@Composable internal fun DashboardDropdown(label: String, options: List<Pair<String, String>>, enabled: Boolean = true, choose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text(label, Modifier.weight(1f), color = DashInk); Text("⌄", color = DashTeal) }
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
            Text("${cross.amount("value")} ${cross.optString("unit")}", color = DashTeal, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(cross.optString("note"), color = DashMuted, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Text("INDEC · Censo ${data?.optString("populationPeriod").orEmpty()}  |  ENACOM · ${data?.optString("connectivityPeriod").orEmpty()}\nCobertura oficial: 24 jurisdicciones. Los accesos por 100 hogares no son un porcentaje de hogares conectados.", color = DashMuted, fontSize = 12.sp, lineHeight = 18.sp)
        Text(if (demo) "Actividad TINO simulada; denominadores y conectividad oficiales consultados mediante OpenARG. El cruce no mide impacto causal." else "Cada indicador conserva su fuente, período y cobertura.", color = DashMuted, fontSize = 12.sp, lineHeight = 18.sp)
        TextButton(onClick = sources) { Text("Ver fuentes y metodología ↗", color = DashTeal) }
    }
}
