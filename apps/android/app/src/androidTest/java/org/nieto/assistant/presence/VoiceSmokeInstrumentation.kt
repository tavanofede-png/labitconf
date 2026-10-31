package org.nieto.assistant.presence

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import org.nieto.assistant.voice.WakeActivationPolicy
import org.nieto.assistant.voice.WakeRecognitionWord
import org.nieto.assistant.voice.LocalEndPolicy
import org.nieto.assistant.voice.WakeTimedWord
import org.nieto.assistant.voice.WakeInvocationPCM
import org.nieto.assistant.voice.WakeWordService

/** Exercises the real Android native decoder with synthetic speech, without recording a microphone. */
class VoiceSmokeInstrumentation : Instrumentation() {
    private var suite: String? = null
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); suite=arguments?.getString("suite"); start() }
    override fun onStart() {
        val output = Bundle()
        if(suite=="dictation-model") {
            try {
                val recognized=kotlinx.coroutines.runBlocking {
                    val model=org.nieto.assistant.voice.OfflineVoiceModel.load(targetContext)
                    try {
                        val decoder=Recognizer(model,16000f)
                        try {
                            val phrases=mutableListOf<String>()
                            context.assets.open("voice-command.pcm").use { input ->
                                val bytes=ByteArray(4096)
                                while(true){val count=input.read(bytes);if(count<0)break;if(decoder.acceptWaveForm(bytes,count))phrases+=JSONObject(decoder.result).optString("text")}
                            }
                            phrases+=JSONObject(decoder.finalResult).optString("text")
                            phrases.joinToString(" ").trim()
                        } finally { decoder.close() }
                    } finally { model.close() }
                }
                check(recognized.contains("correo")) { "Unexpected transcript: $recognized" }
                output.putString("stream","\nPASS: bundled shared dictation model loaded and transcribed real PCM: $recognized\n")
                finish(Activity.RESULT_OK,output)
            } catch(error:Throwable){output.putString("stream","\nFAIL: ${error.javaClass.simpleName}: ${error.message}\n");finish(Activity.RESULT_CANCELED,output)}
            return
        }
        if (suite == "inline-fixture") {
            val result = inlineFixtureDiagnostics(this)
            finish(if (result.getBoolean("success")) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
            return
        }
        if (suite == "realtime-production" || suite == "realtime-dialogue") {
            val result = realtimeProductionSmoke(this, continuous = suite == "realtime-dialogue")
            finish(if (result.getBoolean("success")) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
            return
        }
        if (suite == "realtime-transport" || suite == "realtime-transport-dialogue") {
            val result = realtimeTransportSmoke(this, continuous = suite == "realtime-transport-dialogue")
            finish(if (result.getBoolean("success")) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
            return
        }
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
            copy(WakeWordService.MODEL_ASSET, directory)
            val model = Model(directory.absolutePath)
            try {
                data class DecodedUtterance(val text: String, val words: List<WakeRecognitionWord>?,
                    val timedWords: List<WakeTimedWord>?, val invocation: WakeInvocationPCM?) {
                    fun acceptedWake() = WakeActivationPolicy.accepts(isFinal = true, text = text, words = words)
                    fun acceptedEnd() = LocalEndPolicy.accepts(isFinal = true, text = text, words = words, activeConversation = true)
                }
                fun decode(asset: String, wake: Boolean, captureInvocation: Boolean = false): List<DecodedUtterance> {
                    if (captureInvocation) return context.assets.open(asset).use { decodeSyntheticWake(model, it) }.map { utterance ->
                        DecodedUtterance(utterance.text, utterance.words?.map { WakeRecognitionWord(it.word, it.confidence) }, utterance.words, utterance.invocation)
                    }
                    val recognizer = if (wake) Recognizer(model, 16000f, "[\"hola tino\",\"[unk]\"]") else Recognizer(model, 16000f)
                    try {
                        recognizer.setWords(true)
                        val results = mutableListOf<DecodedUtterance>()
                        fun finalResult(result: String) {
                            val json = JSONObject(result)
                            val timed = json.optJSONArray("result")?.let { array -> (0 until array.length()).map { index ->
                                val word = array.optJSONObject(index)
                                WakeTimedWord(word?.optString("word").orEmpty(), (word?.opt("conf") as? Number)?.toDouble(),
                                    (word?.opt("start") as? Number)?.toDouble(), (word?.opt("end") as? Number)?.toDouble())
                            } }
                            val text = json.optString("text")
                            results += DecodedUtterance(text, timed?.map { WakeRecognitionWord(it.word, it.confidence) }, timed, null)
                        }
                        fun feed(bytes: ByteArray, count: Int) {
                            if (recognizer.acceptWaveForm(bytes, count)) finalResult(recognizer.result)
                        }
                        context.assets.open(asset).use { input -> val buffer = ByteArray(6400); while (true) { val count = input.read(buffer); if (count < 0) break; feed(buffer, count) } }
                        repeat(15) { val silence = ByteArray(6400); feed(silence, silence.size) }
                        finalResult(recognizer.finalResult)
                        return results
                    } finally { recognizer.close() }
                }
                val positive = decode("wake-positive.pcm", true)
                check(positive.any { it.acceptedWake() }) { "La muestra sintética positiva no superó la política final de activación." }
                check(positive.none { it.acceptedEnd() }) { "La muestra sintética de activación se confundió con un cierre." }
                val negative = decode("wake-negative.pcm", true)
                check(negative.none { it.acceptedWake() }) { "La muestra sintética ajena activó incorrectamente la política final." }
                check(negative.none { it.acceptedEnd() }) { "La muestra sintética ajena se confundió con un cierre." }
                val command = decode("voice-command.pcm", false)
                check(command.joinToString(" ") { it.text }.contains("correo")) { "No reconoció correo en la consulta sintética." }
                check(command.none { it.acceptedEnd() }) { "La consulta sintética de correo se confundió con un cierre." }
                // END uses unrestricted local dictation: negative and additional words must survive decoding.
                val endPositive = decode("end-positive.pcm", false)
                check(endPositive.any { it.acceptedEnd() }) { "La muestra sintética de cierre no superó la política de fin local con dictado libre." }
                val endNegative = decode("end-negative.pcm", false)
                check(endNegative.none { it.acceptedEnd() }) { "La negación sintética fue interpretada como un cierre local." }
                val inline = decode("wake-inline.pcm", false, captureInvocation = true)
                val complete = inline.firstOrNull { it.invocation != null && (it.timedWords?.size ?: 0) > 2 }
                    ?: error("La muestra sin pausa no conservó la consulta posterior a Hola Tino.")
                val invocation = complete.invocation!!
                val timed = complete.timedWords!!
                check(invocation.startSample16k >= kotlin.math.ceil(timed.first().startSeconds!! * 16000).toLong()) { "El recorte sintético incluyó audio anterior a la activación." }
                check(invocation.endSample16k >= kotlin.math.ceil(timed.last().endSeconds!! * 16000).toLong()) { "El recorte sintético perdió parte de la consulta." }
                check(invocation.pcm24k.size == (((invocation.endSample16k - invocation.startSample16k) * 3 + 1) / 2 * 2).toInt()) { "La consulta sintética no quedó en PCM24k alineado." }
                invocation.pcm24k.fill(0)
                val followup = context.assets.open("voice-command.pcm").use { decodeSyntheticConversation(model, it) }
                check(followup != null && followup.pcm24k.size >= 4800) { "No conservó el audio real del seguimiento sin Hola Tino." }
                followup.pcm24k.fill(0)
                val silence = java.io.ByteArrayInputStream(ByteArray(16000 * 2 * 3)).use { decodeSyntheticConversation(model, it) }
                check(silence == null) { "El silencio creó una consulta de seguimiento." }
                output.putString("stream", "\nPASS: strict wake; unrelated speech rejected; correo recognized; local ending; negation preserved; full inline wake PCM; follow-up without keyword; silence produces no turn.\n")
                output.putInt("tests", 8)
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
