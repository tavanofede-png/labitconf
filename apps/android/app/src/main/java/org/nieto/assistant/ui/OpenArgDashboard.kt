package org.nieto.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable internal fun OpenArgDashboard(data: JSONObject?, territory: String, sources: () -> Unit) {
    var connectivityTab by rememberSaveable { mutableIntStateOf(0) }
    var populationTab by rememberSaveable { mutableIntStateOf(0) }
    val provinces = data?.optJSONArray("provinces").objects()
    val censusPeriod = data?.optString("populationPeriod").orEmpty()
    val internetPeriod = data?.optString("connectivityPeriod").orEmpty()
    val selected = provinces.find { it.optString("territoryId") == territory }
    val internetKey = if (connectivityTab == 0) "internetPer100Homes" else "internetPer100People"
    val internetRows = provinces.filter { it.optDouble(internetKey).isFinite() }
        .sortedByDescending { it.optDouble(internetKey) }
    val populationRows = provinces.filter { it.optDouble("population").isFinite() }
        .sortedByDescending { it.optDouble("population") }
    val best = internetRows.firstOrNull()
    val lowest = internetRows.lastOrNull()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        HorizontalDivider(color = DashTeal.copy(alpha = .18f))
        Text("Contexto territorial · OpenARG", color = DashInk, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Población y conectividad para entender el territorio.", color = DashMuted, fontSize = 16.sp)
        Text("INDEC · Censo $censusPeriod   /   ENACOM · $internetPeriod", color = DashTeal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (provinces.isEmpty()) {
            DashboardCard("Datos públicos no disponibles") {
                Text("No recibimos los indicadores provinciales. Volvé a actualizar el panel.", color = DashMuted)
            }
        } else {
            if (selected != null) {
                Text(selected.optString("name"), color = DashInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                MetricGrid(listOf(
                    DashboardMetric("Población censada", selected.amount("population"), "Personas · INDEC $censusPeriod"),
                    DashboardMetric("Internet fijo / 100 hogares", selected.amount("internetPer100Homes"), "Accesos · ENACOM $internetPeriod"),
                    DashboardMetric("Internet fijo / 100 habitantes", selected.amount("internetPer100People"), "Accesos · ENACOM $internetPeriod")
                ))
            } else {
                MetricGrid(listOf(
                    DashboardMetric("Población de Argentina", data?.amount("nationalPopulation") ?: "—", "Personas · INDEC $censusPeriod"),
                    DashboardMetric("Mayor acceso fijo", best?.amount(internetKey) ?: "—", "${best?.optString("name").orEmpty()} · accesos / 100 ${if (connectivityTab == 0) "hogares" else "habitantes"}"),
                    DashboardMetric("Menor acceso fijo", lowest?.amount(internetKey) ?: "—", "${lowest?.optString("name").orEmpty()} · accesos / 100 ${if (connectivityTab == 0) "hogares" else "habitantes"}")
                ))
            }
            AdaptivePair({
                RankingSheetCard(populationTab, { populationTab = it }, tabs = listOf("Mayor población", "Menor población"),
                    title = "Población por provincia", subtitle = "Personas censadas · INDEC $censusPeriod · 5 jurisdicciones") {
                    RankingRows((if (populationTab == 0) populationRows else populationRows.reversed()).take(5), countKey = "population")
                }
            }, {
                RankingSheetCard(connectivityTab, { connectivityTab = it }, tabs = listOf("Por hogares", "Por habitantes"),
                    title = "Conectividad por provincia", subtitle = "Accesos a Internet fijo / 100 ${if (connectivityTab == 0) "hogares" else "habitantes"} · ENACOM $internetPeriod · top 5") {
                    RankingRows(internetRows.take(5), countKey = internetKey)
                }
            })
            DashboardCard("Dónde es menor el acceso", "Accesos a Internet fijo / 100 hogares · ENACOM $internetPeriod · 5 jurisdicciones") {
                RankingRows(provinces.filter { it.optDouble("internetPer100Homes").isFinite() }.sortedBy { it.optDouble("internetPer100Homes") }.take(5), countKey = "internetPer100Homes")
            }
        }
        TextButton(onClick = sources) { Text("Ver fuentes y metodología ↗", color = DashTeal) }
    }
}
