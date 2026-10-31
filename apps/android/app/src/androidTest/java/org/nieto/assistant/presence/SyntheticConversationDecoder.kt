package org.nieto.assistant.presence

import org.json.JSONObject
import org.nieto.assistant.voice.ConversationInputPolicy
import org.nieto.assistant.voice.WakeInvocationPCM
import org.nieto.assistant.voice.WakePcmRingBuffer
import org.vosk.Model
import org.vosk.Recognizer
import java.io.InputStream

/** Real JNI endpointing of a follow-up with no wake phrase; shares the production buffer and partial policy. */
internal fun decodeSyntheticConversation(model: Model, input: InputStream): WakeInvocationPCM? {
    val ring = WakePcmRingBuffer(WakePcmRingBuffer.MAX_SAMPLES)
    val policy = ConversationInputPolicy()
    fun first(json: JSONObject, key: String): Long? = json.optJSONArray(key)?.optJSONObject(0)
        ?.optDouble("start", Double.NaN)?.takeIf { it.isFinite() && it >= 0 }
        ?.let { (it * WakePcmRingBuffer.SAMPLE_RATE).toLong() }
    try {
        Recognizer(model, 16000f).use { recognizer ->
            recognizer.setWords(true); recognizer.setPartialWords(true)
            fun feed(bytes: ByteArray, count: Int): WakeInvocationPCM? {
                check(ring.appendPcm16(bytes, count))
                if (recognizer.acceptWaveForm(bytes, count)) {
                    val json = JSONObject(recognizer.result)
                    val start = policy.finalStart(json.optString("text"), ring.totalSamples, first(json, "result"))
                    return start?.let { ring.extractConversationInvocation(it, ring.totalSamples) }
                }
                val json = JSONObject(recognizer.partialResult)
                policy.partial(json.optString("partial"), ring.totalSamples, first(json, "partial_result"))
                return null
            }
            val buffer = ByteArray(640)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                feed(buffer, count)?.let { return it }
            }
            repeat(150) { feed(ByteArray(640), 640)?.let { return it } }
            val json = JSONObject(recognizer.finalResult)
            val start = policy.finalStart(json.optString("text"), ring.totalSamples, first(json, "result"))
            return start?.let { ring.extractConversationInvocation(it, ring.totalSamples) }
        }
    } finally { ring.clear() }
}
