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
    private var suite: String? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); suite=arguments?.getString("suite"); start() }
    override fun onStart() {
        val output = Bundle()
        if (suite=="jury-demo") {
            try { val checks=juryDemoSmoke(targetContext); output.putString("stream","\nPASS: $checks offline jury checks; login, expiry, replay, privacy, messages, 225 public-context filters, both interactive practice engines and logout.\n");output.putInt("tests",checks);finish(Activity.RESULT_OK,output) }
            catch(error:Throwable) { output.putString("stream","\nFAIL: ${error.javaClass.simpleName}: ${error.message}\n${error.stackTraceToString().take(3000)}\n");finish(Activity.RESULT_CANCELED,output) }
            return
        }
        if (suite=="family-audio") {
            try {
                val file=familyAudioSmoke(targetContext)
                output.putString("stream","\nPASS: synthetic AAC recorded by codec, valid M4A, native playback completed (${file.length()} bytes); microphone not used.\n")
                output.putInt("tests",3);finish(Activity.RESULT_OK,output)
            } catch(error: Throwable) {output.putString("stream","\nFAIL: ${error.javaClass.simpleName}: ${error.message}\n");finish(Activity.RESULT_CANCELED,output)}
            return
        }
        if (suite=="reports") { reportSmoke(output); return }
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

    private fun reportSmoke(output: Bundle) {
        try {
            val start=java.time.LocalDate.of(2026,5,2).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            val end=java.time.LocalDate.of(2026,10,29).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
            val pdf=org.nieto.assistant.ui.InstitutionReport.pdf(targetContext,start,end)
            val file=File(targetContext.filesDir,"tino-report-demo.pdf").apply { writeBytes(pdf) }
            android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                android.graphics.pdf.PdfRenderer(descriptor).use { renderer -> check(renderer.pageCount==2) }
            }
            val csv=org.nieto.assistant.ui.InstitutionReport.csv(targetContext,start,end).toString(Charsets.UTF_8)
            check(csv.contains("02/05/2026") && csv.contains("29/10/2026"))
            val second=org.nieto.assistant.ui.InstitutionReport.csv(targetContext,start+86_400_000,end-86_400_000).toString(Charsets.UTF_8)
            fun figures(text: String)=text.lines().filter { it.isNotBlank() }.map { row -> row.split(';').filterIndexed { i,_ -> i!=1 && i!=2 } }
            check(figures(csv)==figures(second)) { "El fixture cambió al cambiar las fechas." }
            File(targetContext.filesDir,"tino-report-demo.csv").writeText(csv)
            output.putString("stream","\nPASS: PDF has 2 pages; dates match selection; CSV figures are invariant across date ranges.\n")
            output.putInt("tests",3);finish(Activity.RESULT_OK,output)
        } catch(error: Throwable) { output.putString("stream","\nFAIL: ${error.javaClass.simpleName}: ${error.message}\n");finish(Activity.RESULT_CANCELED,output) }
    }
}
