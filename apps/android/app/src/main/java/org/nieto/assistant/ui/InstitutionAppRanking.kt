package org.nieto.assistant.ui

import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.nieto.assistant.R

// App attribution for the presentation's existing workflow fixture.
internal fun institutionAppRanking(flows: List<JSONObject>): List<JSONObject> {
    val apps = mapOf(
        "whatsapp-group" to ("WhatsApp" to "com.whatsapp"),
        "whatsapp-location" to ("WhatsApp" to "com.whatsapp"),
        "video-call" to ("WhatsApp" to "com.whatsapp"),
        "share-photo" to ("WhatsApp" to "com.whatsapp"),
        "email-send" to ("Gmail" to "com.google.android.gm"),
        "health-appointment" to ("Chrome" to "com.android.chrome"),
        "check-link" to ("Chrome" to "com.android.chrome"),
        "online-form" to ("Chrome" to "com.android.chrome"),
        "phone-settings" to ("Ajustes" to "com.android.settings"),
        "maps-route" to ("Google Maps" to "com.google.android.apps.maps")
    )
    return flows.mapNotNull { flow ->
        apps[flow.optString("id")]?.let { app -> Triple(app.first, app.second, flow.optDouble("count", 0.0)) }
    }.groupBy { it.first to it.second }.map { (app, rows) ->
        JSONObject().put("name", app.first).put("package", app.second).put("count", rows.sumOf { it.third })
    }.sortedByDescending { it.optDouble("count") }
}

@Composable internal fun OriginalAppIcon(packageName: String, name: String) {
    AndroidView(
        factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        modifier = Modifier.size(32.dp),
        update = { view ->
            view.contentDescription = name
            val original = runCatching { view.context.packageManager.getApplicationIcon(packageName) }.getOrNull()
            if (original != null) view.setImageDrawable(original)
            else if (packageName == "com.whatsapp") view.setImageResource(R.drawable.ic_brand_whatsapp)
            else view.setImageDrawable(view.context.packageManager.defaultActivityIcon)
        }
    )
}
