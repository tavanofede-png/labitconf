package org.nieto.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun LogoutButton(enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 14.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFB42318))) {
        val color = LocalContentColor.current
        Canvas(Modifier.size(24.dp)) {
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color,
                Offset(size.width * x1, size.height * y1), Offset(size.width * x2, size.height * y2),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            line(.44f, .16f, .16f, .16f); line(.16f, .16f, .16f, .84f); line(.16f, .84f, .44f, .84f)
            line(.4f, .5f, .88f, .5f); line(.68f, .3f, .88f, .5f); line(.68f, .7f, .88f, .5f)
        }
        Spacer(Modifier.width(12.dp))
        Text("Cerrar sesión", Modifier.weight(1f), fontSize = 18.sp)
    }
}
