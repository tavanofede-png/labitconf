package org.nieto.assistant.entry

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.nieto.assistant.R

/** Shared transparent completion animation for the final slide of every onboarding role. */
@Composable
fun TinoCompletionAnimation(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val drawable = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(context.resources, R.drawable.tino_listo_animated),
                )
            }.getOrElse { context.getDrawable(R.drawable.tino_listo_static) }
        } else {
            context.getDrawable(R.drawable.tino_listo_static)
        }
    }

    AndroidView(
        factory = {
            ImageView(it).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                contentDescription = "TINO hace el gesto OK y dice ¡Listo!"
            }
        },
        update = { image ->
            if (image.drawable !== drawable) image.setImageDrawable(drawable)
        },
        modifier = modifier,
    )

    DisposableEffect(drawable) {
        (drawable as? AnimatedImageDrawable)?.apply {
            repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            start()
        }
        onDispose { (drawable as? AnimatedImageDrawable)?.stop() }
    }
}
