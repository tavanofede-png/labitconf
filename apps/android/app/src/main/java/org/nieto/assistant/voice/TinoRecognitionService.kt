package org.nieto.assistant.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.nieto.assistant.NietoApp
import org.vosk.Model
import org.vosk.Recognizer
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Spanish, on-device dictation for Android's recognition contract; it never opens a chat. */
class TinoRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null
    private var activeCallback: Callback? = null
    private val stopRequested = AtomicBoolean(false)

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        val appState = (application as NietoApp).state.value
        if (worker != null || appState.wakeEnabled || appState.voice.active || appState.busy ||
            !LocalMicrophoneLease.acquire(LEASE_OWNER)) {
            notify(listener) { error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            LocalMicrophoneLease.release(LEASE_OWNER)
            notify(listener) { error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
            return
        }
        val language = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) ?: "es-AR"
        if (Locale.forLanguageTag(language.replace('_', '-')).language != "es") {
            LocalMicrophoneLease.release(LEASE_OWNER)
            notify(listener) { error(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) }
            return
        }
        val attributionContext = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) createContext(
                ContextParams.Builder().setNextAttributionSource(listener.callingAttributionSource).build()
            ) else this
        } catch (_: RuntimeException) {
            LocalMicrophoneLease.release(LEASE_OWNER)
            notify(listener) { error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
            return
        }
        stopRequested.set(false)
        activeCallback = listener
        val allowPartial = recognizerIntent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        worker = scope.launch(start = CoroutineStart.LAZY) {
            var model: Model? = null
            try {
                val loaded = OfflineVoiceModel.load(this@TinoRecognitionService)
                model = loaded
                val text = withContext(Dispatchers.IO) { transcribe(loaded, attributionContext, listener, allowPartial) }
                // AudioRecord is closed in transcribe's finally before either final callback.
                notify(listener) { endOfSpeech() }
                if (text.isBlank()) notify(listener) { error(SpeechRecognizer.ERROR_NO_MATCH) }
                else notify(listener) { results(resultBundle(text)) }
            } catch (_: CancellationException) { }
            catch (_: SecurityException) { notify(listener) { error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) } }
            catch (_: LinkageError) { notify(listener) { error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) } }
            catch (_: Exception) { notify(listener) { error(SpeechRecognizer.ERROR_AUDIO) } }
            finally {
                withContext(NonCancellable + Dispatchers.IO) { model?.close() }
                LocalMicrophoneLease.release(LEASE_OWNER)
                activeCallback = null
                worker = null
            }
        }
        worker?.start()
    }

    override fun onStopListening(listener: Callback) {
        if (activeCallback === listener) stopRequested.set(true)
    }

    override fun onCancel(listener: Callback) {
        if (activeCallback === listener) worker?.cancel()
    }

    @SuppressLint("MissingPermission")
    private suspend fun transcribe(model: Model, attributionContext: Context, listener: Callback, allowPartial: Boolean): String {
        currentCoroutineContext().ensureActive()
        if (stopRequested.get()) return ""
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE.toInt(), AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw IOException("Unsupported microphone format")
        val recognizer = Recognizer(model, SAMPLE_RATE)
        var recorder: AudioRecord? = null
        try {
            val builder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE.toInt())
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum, 6_400))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setContext(attributionContext)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setPrivacySensitive(true)
            recorder = builder.build()
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IOException("Microphone unavailable")
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("Microphone did not start")
            if (!notify(listener) { readyForSpeech(Bundle()) }) throw CancellationException("Recognition client disconnected")
            val buffer = ShortArray(320)
            val startedAt = SystemClock.elapsedRealtime()
            var began = false
            var lastPartial = ""
            while (currentCoroutineContext().isActive && !stopRequested.get()) {
                currentCoroutineContext().ensureActive()
                val now = SystemClock.elapsedRealtime()
                if (now - startedAt >= MAX_CAPTURE_MS || (!began && now - startedAt >= NO_SPEECH_TIMEOUT_MS)) break
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                if (count < 0) throw IOException("Microphone read failed")
                if (count == 0) { delay(20); continue }
                if (recognizer.acceptWaveForm(buffer, count)) {
                    val text = JSONObject(recognizer.result).optString("text", "")
                    if (text.isNotBlank()) {
                        if (!began) notify(listener) { beginningOfSpeech() }
                        return text
                    }
                } else {
                    val partial = JSONObject(recognizer.partialResult).optString("partial", "")
                    if (partial.isNotBlank() && !began) {
                        began = true
                        if (!notify(listener) { beginningOfSpeech() }) throw CancellationException("Recognition client disconnected")
                    }
                    if (allowPartial && partial.isNotBlank() && partial != lastPartial) {
                        lastPartial = partial
                        if (!notify(listener) { partialResults(resultBundle(partial)) }) throw CancellationException("Recognition client disconnected")
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            return JSONObject(recognizer.finalResult).optString("text", "")
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            runCatching { recognizer.close() }
        }
    }

    private fun resultBundle(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    private fun notify(listener: Callback, action: Callback.() -> Unit): Boolean =
        runCatching { listener.action(); true }.getOrDefault(false)

    override fun onDestroy() {
        worker?.cancel()
        scope.launch(NonCancellable) { worker?.join(); scope.cancel() }
        super.onDestroy()
    }

    companion object {
        private const val LEASE_OWNER = "system-dictation"
        private const val SAMPLE_RATE = 16_000f
        private const val MAX_CAPTURE_MS = 30_000L
        private const val NO_SPEECH_TIMEOUT_MS = 8_000L
    }
}
