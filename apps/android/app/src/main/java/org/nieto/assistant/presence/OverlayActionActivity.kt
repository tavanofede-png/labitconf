package org.nieto.assistant.presence

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Transparent permission/document bridge. Never launches MainActivity or replaces the underlying app. */
class OverlayActionActivity : ComponentActivity() {
    private val files = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val uris = data?.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(data?.data)
            FloatingBubbleService.active?.receiveFiles(uris.distinct())
        } else FloatingBubbleService.active?.resumeHelper()
        finish()
    }
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginVoice() else { FloatingBubbleService.active?.resumeHelper(); FloatingBubbleService.active?.notice("El micrófono está desactivado. Podés escribir."); finish() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        if (FloatingBubbleService.active == null) { finish(); return }
        if (intent.action == "microphone") {
            // Wait until this transparent activity is visible before promoting the microphone FGS.
            window.decorView.post {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginVoice()
                else microphone.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else {
            val types = if (intent.action == "images") arrayOf("image/png", "image/jpeg", "image/webp") else arrayOf("image/png", "image/jpeg", "image/webp", "application/pdf")
            runCatching { files.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, types).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                .onFailure { FloatingBubbleService.active?.resumeHelper(); FloatingBubbleService.active?.notice("No pude abrir el selector de archivos."); finish() }
        }
    }
    private fun beginVoice() { FloatingBubbleService.active?.resumeHelper(); FloatingBubbleService.active?.startDictation(); finish() }
}
