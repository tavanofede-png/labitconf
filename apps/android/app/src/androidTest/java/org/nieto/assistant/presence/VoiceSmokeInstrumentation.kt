package org.nieto.assistant.presence

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/** Exercises the real Android native decoder with synthetic speech, without recording a microphone. */
class VoiceSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val output = Bundle()
        try {
            val directory = File(targetContext.cacheDir, "voice-smoke-model")
            fun copy(path: String, destination: File) {
                val files = targetContext.assets.list(path).orEmpty()
                if (files.isEmpty()) { destination.parentFile?.mkdirs(); targetContext.assets.open(path).use { input -> destination.outputStream().use { input.copyTo(it) } } }
                else files.forEach { copy("$path/$it", File(destination, it)) }
            }
            copy("model-es", directory)
            val model = Model(directory.absolutePath)
            try {
                fun decode(asset: String, wake: Boolean): String {
                    val recognizer = if (wake) Recognizer(model, 16000f, "[\"hola tino\",\"[unk]\"]") else Recognizer(model, 16000f)
                    try {
                        val results = mutableListOf<String>()
                        fun feed(bytes: ByteArray, count: Int) {
                            if (recognizer.acceptWaveForm(bytes, count)) results += JSONObject(recognizer.result).optString("text")
                        }
                        context.assets.open(asset).use { input -> val buffer = ByteArray(6400); while (true) { val count = input.read(buffer); if (count < 0) break; feed(buffer, count) } }
                        repeat(15) { val silence = ByteArray(6400); feed(silence, silence.size) }
                        results += JSONObject(recognizer.finalResult).optString("text")
                        return results.joinToString(" ").trim()
                    } finally { recognizer.close() }
                }
                val positive = decode("wake-positive.pcm", true)
                check(WakePhrase.matches(positive)) { "No reconoció la activación: $positive" }
                val negative = decode("wake-negative.pcm", true)
                check(!WakePhrase.matches(negative)) { "Activación incorrecta: $negative" }
                val command = decode("voice-command.pcm", false)
                check(command.contains("correo")) { "No reconoció la consulta: $command" }
                output.putString("stream", "\nPASS: wake phrase recognized; unrelated speech rejected; command recognized.\n")
                output.putInt("tests", 3)
                finish(Activity.RESULT_OK, output)
            } finally { model.close(); directory.deleteRecursively() }
        } catch (error: Throwable) {
            output.putString("stream", "\nFAIL: ${error.javaClass.simpleName}: ${error.message}\n")
            finish(Activity.RESULT_CANCELED, output)
        }
    }
}
