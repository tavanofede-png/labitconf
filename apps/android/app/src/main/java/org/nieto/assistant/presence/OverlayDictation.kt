package org.nieto.assistant.presence

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

/** Local speech-to-text. Only the editable transcript enters chat after the person presses Send. */
internal class OverlayDictation(private val context: Context, private val scope: CoroutineScope,
    private val partial: (String) -> Unit, private val finished: (String?, String?) -> Unit) {
    private var model: Model? = null
    private var decoder: Recognizer? = null
    private var speech: SpeechService? = null
    private var loading: Job? = null
    private var generation = 0
    private var lastText = ""
    fun start() {
        stop(false)
        val id = generation
        loading = scope.launch {
            try {
                val path = withContext(Dispatchers.IO) { unpack() }
                if (id != generation) return@launch
                model = Model(path)
                decoder = Recognizer(model, 16000f)
                speech = SpeechService(decoder, 16000f).also { input ->
                    input.startListening(object : RecognitionListener {
                        override fun onPartialResult(hypothesis: String?) { if (id == generation) { lastText = JSONObject(hypothesis.orEmpty()).optString("partial"); partial(lastText) } }
                        override fun onResult(hypothesis: String?) { if (id == generation) { val text = JSONObject(hypothesis.orEmpty()).optString("text"); if (text.isNotBlank()) { lastText = text; stop(true) } } }
                        override fun onFinalResult(hypothesis: String?) { if (id == generation) { lastText = JSONObject(hypothesis.orEmpty()).optString("text").ifBlank { lastText }; stop(true) } }
                        override fun onTimeout() { if (id == generation) stop(true) }
                        override fun onError(exception: Exception?) { if (id == generation) { stop(false); finished(null, "No pude escuchar. Revisá que el micrófono esté disponible.") } }
                    }, 30_000)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (id == generation) { stop(false); finished(null, "No pude iniciar el dictado. Podés escribir tu mensaje.") } }
        }
    }
    fun stop(deliver: Boolean) {
        generation++
        loading?.cancel(); loading = null
        val text = lastText.trim(); lastText = ""
        speech?.cancel(); speech?.shutdown(); speech = null
        decoder?.close(); decoder = null
        model?.close(); model = null
        if (deliver) finished(text.takeIf { it.isNotBlank() }, if (text.isBlank()) "No escuché palabras. Probá otra vez." else null)
    }
    private fun unpack(): String {
        val target = File(context.filesDir, "overlay-voice-es-0.42")
        if (File(target, ".ready").exists()) return target.absolutePath
        fun copy(path: String, out: File) {
            val children = context.assets.list(path).orEmpty()
            if (children.isEmpty()) { out.parentFile?.mkdirs(); context.assets.open(path).use { input -> out.outputStream().use { input.copyTo(it) } } }
            else { out.mkdirs(); children.forEach { copy("$path/$it", File(out, it)) } }
        }
        copy("model-es", target)
        check(File(target, "am/final.mdl").isFile)
        File(target, ".ready").writeText("0.42")
        return target.absolutePath
    }
}
