package org.nieto.assistant.presence

import android.app.Instrumentation
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import org.nieto.assistant.voice.WakeActivationPolicy
import org.nieto.assistant.voice.WakePcmRingBuffer
import org.nieto.assistant.voice.WakeRecognitionWord
import org.nieto.assistant.voice.WakeTimedWord
import org.nieto.assistant.voice.WakeWordService
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/** Explicit diagnostic of one bundled synthetic fixture. No microphone or network path exists here. */
internal fun inlineFixtureDiagnostics(instrumentation: Instrumentation): Bundle {
    val target = instrumentation.targetContext
    val directory = File(target.cacheDir, "inline-fixture-diagnostic-model")
    check(directory.canonicalFile.parentFile == target.cacheDir.canonicalFile)
    val finals = JSONArray()
    fun copy(source: String, destination: File) {
        val children = target.assets.list(source).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            target.assets.open(source).use { input -> destination.outputStream().use { input.copyTo(it) } }
        } else children.forEach { copy("$source/$it", File(destination, it)) }
    }
    try {
        copy(WakeWordService.MODEL_ASSET, directory)
        Model(directory.absolutePath).use { model ->
            for (constrained in listOf(false, true)) {
                val ring = WakePcmRingBuffer()
                val recognizer = if (constrained) Recognizer(model, 16000f, "[\"hola tino\",\"[unk]\"]") else Recognizer(model, 16000f)
                try {
                    recognizer.setWords(true)
                    fun finalResult(raw: String) {
                        val result = JSONObject(raw)
                        val array = result.optJSONArray("result")
                        val words = array?.let { (0 until it.length()).map { index ->
                            val word = it.optJSONObject(index)
                            WakeTimedWord(word?.optString("word").orEmpty(), (word?.opt("conf") as? Number)?.toDouble(),
                                (word?.opt("start") as? Number)?.toDouble(), (word?.opt("end") as? Number)?.toDouble())
                        } }
                        val text = result.optString("text")
                        val invocation = ring.extractInvocation(true, text, words)
                        finals.put(JSONObject().put("decoder", if (constrained) "constrained" else "free")
                            .put("receivedSamples16k", ring.totalSamples).put("oldestSample16k", ring.oldestSample)
                            .put("final", result).put("strictWakeAccepted", WakeActivationPolicy.accepts(true, text, words?.map { WakeRecognitionWord(it.word, it.confidence) }))
                            .put("invocationAccepted", invocation != null).put("invocationBytes24k", invocation?.pcm24k?.size ?: 0)
                            .put("invocationStart16k", invocation?.startSample16k ?: -1).put("invocationEnd16k", invocation?.endSample16k ?: -1))
                        invocation?.pcm24k?.fill(0)
                    }
                    fun feed(bytes: ByteArray, count: Int) {
                        check(ring.appendPcm16(bytes, count))
                        if (recognizer.acceptWaveForm(bytes, count)) finalResult(recognizer.result)
                    }
                    instrumentation.context.assets.open("wake-inline.pcm").use { input ->
                        val chunk = ByteArray(6400)
                        while (true) { val count = input.read(chunk); if (count < 0) break; feed(chunk, count) }
                        chunk.fill(0)
                    }
                    repeat(15) { val silence = ByteArray(6400); feed(silence, silence.size) }
                    finalResult(recognizer.finalResult)
                } finally { recognizer.close(); ring.clear() }
            }
        }
        return Bundle().apply {
            putBoolean("success", true); putInt("tests", 0)
            putString("stream", "\nSYNTHETIC_INLINE_FIXTURE_ONLY ${finals.toString()}\n")
        }
    } catch (error: Throwable) {
        return Bundle().apply {
            putBoolean("success", false); putInt("tests", 0)
            putString("stream", "\nSYNTHETIC_INLINE_DIAGNOSTIC_FAILED ${error.javaClass.simpleName}\n")
        }
    } finally { directory.deleteRecursively() }
}
