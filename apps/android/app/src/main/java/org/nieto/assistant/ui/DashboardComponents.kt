package org.nieto.assistant.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import org.nieto.assistant.R
import java.text.NumberFormat
import java.util.Locale
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState

internal val DashCyan = Color(0xFF00D6C9)
internal val DashTeal = Color(0xFF006B64)
internal val DashInk = Color(0xFF101416)
internal val DashMuted = Color(0xFF4C5B61)
internal val DashPaper = Color(0xFFF3F6F6)
internal val DashRed = Color(0xFFC73540)
internal val DashGreen = Color(0xFF16855F)
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONArray?.numbers(): List<Float> = if (this == null) emptyList() else (0 until length()).map { optDouble(it, 0.0).toFloat() }
internal fun number(value: Number): String = NumberFormat.getNumberInstance(Locale.forLanguageTag("es-AR")).apply { maximumFractionDigits = 1 }.format(value)
internal fun JSONObject.amount(key: String, suffix: String = ""): String = if (!has(key) || isNull(key)) "—" else number(optDouble(key)) + suffix
internal fun monthLabel(key: String): String = runCatching { java.time.YearMonth.parse(key).format(java.time.format.DateTimeFormatter.ofPattern("MMM yy", Locale.forLanguageTag("es-AR"))) }.getOrDefault(key)

@Composable internal fun DashboardCard(title: String? = null, subtitle: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, color = Color.White, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Color(0xFFE2EAE8))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            title?.let { Text(it, color = DashInk, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() }) }
            subtitle?.let { Text(it, color = DashMuted, fontSize = 13.sp, lineHeight = 19.sp) }
            content()
        }
    }
}

internal data class DashboardMetric(val label: String, val value: String, val detail: String, val tint: Color = DashTeal)

@Composable internal fun RankingSheetCard(selected: Int, choose: (Int) -> Unit, tabs: List<String> = listOf("Por app", "Por workflow"), title: String = "Lo más pedido", subtitle: String = "Solicitudes de acompañamiento en el período.", content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.selectableGroup(), verticalAlignment = Alignment.Bottom) {
            tabs.forEachIndexed { index, label ->
                val active = selected == index
                Surface(
                    color = if (active) Color.White else Color(0xFFE7ECEB),
                    shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
                    border = BorderStroke(1.dp, Color(0xFFE2EAE8)),
                    modifier = Modifier.width(IntrinsicSize.Max).widthIn(min = 128.dp).selectable(selected = active, role = Role.Tab, onClick = { choose(index) })
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, Modifier.padding(horizontal = 22.dp, vertical = 13.dp), color = if (active) DashTeal else DashMuted,
                            fontSize = 14.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                        Box(Modifier.fillMaxWidth().height(3.dp).background(if (active) DashTeal else Color.Transparent))
                    }
                }
            }
        }
        Surface(color = Color.White, shape = RoundedCornerShape(topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 24.dp), border = BorderStroke(1.dp, Color(0xFFE2EAE8))) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, color = DashInk, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                Text(subtitle, color = DashMuted, fontSize = 13.sp, lineHeight = 19.sp)
                content()
            }
        }
    }
}

@Composable internal fun MetricGrid(items: List<DashboardMetric>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 900.dp) 3 else if (maxWidth >= 330.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.chunked(columns).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { item -> DashboardCard(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.label, color = DashMuted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f, fill = false))
                        if (item.detail.isNotBlank()) MetricHelp(item.label, item.detail)
                    }
                    Text(item.value, color = item.tint, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp)
                } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            } }
        }
    }
}

@Composable private fun MetricHelp(label: String, detail: String) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(Modifier.hoverable(interaction)) {
        IconButton(
            onClick = { open = !open },
            modifier = Modifier.size(32.dp).semantics { contentDescription = "Más información sobre $label" }
        ) {
            Box(Modifier.size(18.dp).border(1.dp, DashTeal, CircleShape), contentAlignment = Alignment.Center) {
                Text("?", color = DashTeal, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold,
                    style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
            }
        }
        DropdownMenu(expanded = open || hovered, onDismissRequest = { open = false },
            modifier = Modifier.widthIn(max = 280.dp), containerColor = DashTeal,
            properties = androidx.compose.ui.window.PopupProperties(focusable = open)) {
            Text(detail, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), color = Color.White, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

@Composable internal fun DashboardTabs(tabs: List<String>, selected: Int, choose: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tabs.forEachIndexed { index, label -> FilterChip(selected == index, onClick = { choose(index) },
            label = { Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = DashCyan, selectedLabelColor = DashInk, containerColor = Color.White, labelColor = DashMuted),
            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected == index, borderColor = Color(0xFFDCE6E3), selectedBorderColor = DashCyan), shape = CircleShape)
        }
    }
}

@Composable internal fun DataPill(text: String, demo: Boolean = false) {
    Surface(color = if (demo) Color(0xFFFFF1D8) else Color(0xFFE4F6EE), shape = CircleShape) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = if (demo) Color(0xFF775014) else DashTeal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable internal fun DashboardAvatar(name: String, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val modifier = Modifier.size(58.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .semantics { contentDescription = name; if (onClick != null) this.selected = selected }
    Surface(modifier, color = if (selected) DashCyan else Color(0xFFE3EFEC), shape = CircleShape,
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) DashTeal else Color(0xFFD5E5DF))) {
        Box(contentAlignment = Alignment.Center) { Text(name.split(' ').take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString(""), color = DashTeal, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable internal fun DashboardHeader(label: String, title: String, onMenu: () -> Unit, showMascot: Boolean = true) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TinoHomeLogo(Modifier.width(100.dp).height(48.dp))
        if (showMascot) Image(painterResource(R.drawable.tino_mascot),null,Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = DashTeal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (title.isNotBlank()) Text(title, color = DashInk, fontSize = 14.sp, maxLines = 1)
        }
        IconButton(onClick = onMenu, modifier = Modifier.size(52.dp).semantics { contentDescription = "Abrir menú" }) { Text("☰", color = DashInk, fontSize = 24.sp) }
    }
}

@Composable internal fun MiniBarChart(values: List<Float>, secondary: List<Float> = emptyList(), labels: List<String> = emptyList(), description: String, modifier: Modifier = Modifier) {
    val maximum = (values + secondary).filter { it.isFinite() && it>=0 }.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(116.dp).semantics { contentDescription = description }) {
            repeat(4) { i -> val y = size.height * i / 3; drawLine(Color(0xFFEDF1F0), Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
            if (values.isNotEmpty()) {
                val stride = size.width / values.size; val width = stride * if (secondary.isEmpty()) .52f else .28f
                values.forEachIndexed { i, value ->
                    if(!value.isFinite() || value<0)return@forEachIndexed
                    val height = value / maximum * (size.height - 8.dp.toPx())
                    drawRoundRect(DashCyan, Offset(stride * i + stride * .15f, size.height - height), androidx.compose.ui.geometry.Size(width, height.coerceAtLeast(2f)), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                    secondary.getOrNull(i)?.takeIf { it.isFinite() && it>=0 }?.let { other -> val h = other / maximum * (size.height - 8.dp.toPx()); drawRoundRect(DashTeal, Offset(stride * i + stride * .5f, size.height - h), androidx.compose.ui.geometry.Size(width, h.coerceAtLeast(2f)), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())) }
                }
            }
        }
        if (labels.isNotEmpty()) Row(Modifier.fillMaxWidth()) { labels.forEach { Text(it, Modifier.weight(1f), color = DashMuted, fontSize = 10.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
    }
}

@Composable internal fun RankingRows(items: List<JSONObject>, countKey: String = "count", appIcons: Boolean = false, onChoose: ((JSONObject) -> Unit)? = null) {
    val maximum = items.map { it.optDouble(countKey) }.filter { it.isFinite() && it>=0 }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    items.forEachIndexed { index, item ->
        Column(Modifier.fillMaxWidth().then(if (onChoose != null) Modifier.clickable { onChoose(item) } else Modifier).padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (appIcons) OriginalAppIcon(item.optString("package"), item.optString("name"))
                else Surface(color = Color(0xFFE7F8F4), shape = CircleShape) { Text("${index + 1}", Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = DashTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                Text(item.optString("name", item.optString("label")), Modifier.weight(1f), color = DashInk, fontSize = 14.sp, lineHeight = 19.sp)
                Text(item.amount(countKey), color = DashTeal, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            if(item.optDouble(countKey).isFinite()) LinearProgressIndicator(progress = { (item.optDouble(countKey) / maximum).toFloat().coerceIn(0f,1f) }, color = DashCyan, trackColor = Color(0xFFECF2F0), modifier = Modifier.fillMaxWidth().height(5.dp))
        }
    }
}
