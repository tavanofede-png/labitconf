package org.nieto.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.sqrt

private data class ProvinceShape(val id: String, val rings: List<List<Offset>>)
private fun project(point: Offset, width: Float, height: Float): Offset {
    val scale = min(width / (21f * .79f), height / 35f)
    return Offset((width - 21f * .79f * scale) / 2 + (point.x + 74f) * .79f * scale,
        (height - 35f * scale) / 2 + (-point.y - 21f) * scale)
}
@Composable internal fun ArgentinaMap(territories: List<JSONObject>, selected: String, layer: Int, simulated: Boolean = true, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val motion = remember { android.provider.Settings.Global.getFloat(context.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f }
    val transition=rememberInfiniteTransition(label="Actividad territorial")
    val animatedPulse by transition.animateFloat(1f,1.8f,infiniteRepeatable(tween(2200,easing=LinearEasing),RepeatMode.Restart),label="Pulso agregado")
    val pulse=if(motion)animatedPulse else 1f
    val shapes = remember {
        val root = JSONObject(context.assets.open("argentina-provinces.json").bufferedReader().use { it.readText() })
        root.getJSONArray("provinces").objects().map { province ->
            val rings = province.getJSONArray("rings")
            ProvinceShape(province.getString("id"), (0 until rings.length()).map { i ->
                val ring = rings.getJSONArray(i)
                (0 until ring.length()).map { j -> val point = ring.getJSONArray(j); Offset(point.getDouble(0).toFloat(), point.getDouble(1).toFloat()) }
            })
        }
    }
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    fun metric(t: JSONObject, key: String) = t.optDouble(key, 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0
    val maxUsers = territories.maxOfOrNull { metric(it, "activeUsers") }?.coerceAtLeast(1.0) ?: 1.0
    val maxRisk = territories.maxOfOrNull { metric(it, "unsafeLinks") }?.coerceAtLeast(1.0) ?: 1.0
    fun point(t: JSONObject): Offset { val p = t.getJSONArray("point"); return Offset(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()) }
    Canvas(Modifier.fillMaxWidth().height(470.dp).onSizeChanged { dimensions = it }
        .semantics { contentDescription = "Mapa de Argentina por provincia. Verde: usuarios activos de TINO. Rojo: alertas simuladas de enlaces inseguros. También podés elegir provincia en el filtro accesible de arriba." }
        .pointerInput(territories, layer) { detectTapGestures { tap ->
            val nearest = territories.minByOrNull { (project(point(it), dimensions.width.toFloat(), dimensions.height.toFloat()) - tap).getDistance() }
            if (nearest != null && (project(point(nearest), dimensions.width.toFloat(), dimensions.height.toFloat()) - tap).getDistance() <= 32.dp.toPx()) onSelect(nearest.getString("id"))
        } }) {
        shapes.forEach { province ->
            province.rings.forEach { ring ->
                if (ring.size >= 3) {
                    val path = Path(); ring.forEachIndexed { index, coordinate -> val p = project(coordinate, size.width, size.height); if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }; path.close()
                    drawPath(path, if (selected == province.id) Color(0xFFB3E9DB) else Color(0xFFE4ECE9))
                    drawPath(path, Color.White, style = Stroke(1.dp.toPx()))
                    if (selected == province.id) drawPath(path, DashTeal, style = Stroke(1.5.dp.toPx()))
                }
            }
        }
        territories.sortedByDescending { metric(it, "activeUsers") }.forEach { territory ->
            val center = project(point(territory), size.width, size.height)
            val dim = selected != "AR" && selected != territory.optString("id")
            val alpha = if (dim) .25f else .82f
            if (layer != 2 && metric(territory, "activeUsers") > 0) {
                val radius = (4 + 13 * sqrt(metric(territory, "activeUsers") / maxUsers)).toFloat().dp.toPx()
                val p = center + Offset(if (layer == 0) -5.dp.toPx() else 0f, 0f)
                drawCircle(DashGreen.copy(alpha = .18f*(2f-pulse)), radius * pulse, p)
                drawCircle(DashGreen.copy(alpha = alpha), radius, p); drawCircle(Color.White, radius, p, style = Stroke(1.dp.toPx()))
            }
            if (layer != 1 && metric(territory, "unsafeLinks") > 0) {
                val radius = (3 + 10 * sqrt(metric(territory, "unsafeLinks") / maxRisk)).toFloat().dp.toPx()
                val p = center + Offset(if (layer == 0) 7.dp.toPx() else 0f, 2.dp.toPx())
                drawCircle(DashRed.copy(alpha = .16f*(2f-pulse)), radius * pulse, p); drawCircle(DashRed.copy(alpha = alpha), radius, p); drawCircle(Color.White, radius, p, style = Stroke(1.dp.toPx()))
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("● Uso de TINO", color = DashGreen, fontSize = 13.sp)
        Text("● Enlaces inseguros", color = DashRed, fontSize = 13.sp)
    }
    Text("Tamaños relativos por capa · " + (if(simulated) "datos TINO simulados" else "datos consentidos; se omiten cohortes pequeñas") + ". El pulso es visual. Puntos provinciales agregados. Cartografía Georef / IGN; vista continental y Tierra del Fuego.", color = DashMuted, fontSize = 11.sp, lineHeight = 16.sp)
}
