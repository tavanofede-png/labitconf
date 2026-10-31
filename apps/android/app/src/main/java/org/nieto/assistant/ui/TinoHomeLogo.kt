package org.nieto.assistant.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.nieto.assistant.R

internal val LocalTinoHome = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Every authenticated header uses the same accessible back action. */
@Composable fun TinoHomeLogo(modifier: Modifier = Modifier) {
    val home = LocalTinoHome.current
    Image(painterResource(R.drawable.tino_logo), "TINO, volver atrás",
        modifier.heightIn(min = 48.dp).then(if (home != null) Modifier.clickable(
            role = Role.Button, onClickLabel = "Volver atrás", onClick = home) else Modifier))
}
