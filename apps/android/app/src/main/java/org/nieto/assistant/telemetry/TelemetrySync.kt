package org.nieto.assistant.telemetry

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.nieto.assistant.data.ApiFailure
import org.nieto.assistant.data.LocalSettings
import org.nieto.assistant.data.NietoApi
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

object TelemetrySync {
    private const val IMMEDIATE = "nieto_telemetry_send"
    private const val RECOVERY = "nieto_telemetry_recovery"
    private val mutable = MutableStateFlow(OutboxSummary())
    val status = mutable.asStateFlow()
    private var initialized = false
    private var outbox: DurableOutbox? = null
    private val deliveryLock = Mutex()

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        try {
            outbox = DurableOutbox(EncryptedFileOutbox(File(context.noBackupFilesDir, "telemetry/outbox.bin"), AesGcmEnvelope(::key)))
            publish()
            schedule(context)
        } catch (_: Exception) {
            // Never replace an unreadable encrypted queue with an empty one.
            mutable.value = OutboxSummary(pending = -1, error = "No pude recuperar la cola cifrada de progreso. No se borró ni se declaró sincronizada.")
        }
    }
    fun session(serverUrl: String, token: String, flowId: String) =
        TelemetrySession(UUID.randomUUID().toString(), flowId, TelemetryOrigin(serverUrl, token))

    fun enqueue(context: Context, session: TelemetrySession, type: String, stepId: String? = null,
        durationSeconds: Int? = null, attemptId: String? = null): Boolean {
        initialize(context)
        return try {
            val queue = outbox ?: return false
            queue.enqueue(TelemetryEvent(UUID.randomUUID().toString(), session, type, stepId, durationSeconds, attemptId))
            publish(); schedule(context); true
        } catch (_: Exception) {
            publish("No pude guardar un evento de progreso. La cola existente se conserva; este evento no se declaró pendiente ni sincronizado.")
            false
        }
    }
    fun retryRetained(context: Context) {
        if (outbox == null) synchronized(this) { initialized = false }
        initialize(context)
        try { outbox?.retryRetained(); publish(); schedule(context) }
        catch (_: Exception) { publish("No pude actualizar la cola cifrada. Los eventos existentes se conservan.") }
    }
    suspend fun synchronize(context: Context): OutboxSummary = deliveryLock.withLock {
        initialize(context)
        val queue = outbox ?: return@withLock mutable.value
        val api = NietoApi(LocalSettings(context))
        val transport = object : TelemetryTransport {
            override suspend fun createSession(session: TelemetrySession): String = deliver {
                val result = api.request("/assistant/sessions", "POST", JSONObject().put("flowId", session.flowId)
                    .put("clientSessionId", session.clientSessionId), serverUrl = session.origin.serverUrl,
                    installationToken = session.origin.installationToken)
                result.getString("id").also { UUID.fromString(it) }
            }
            override suspend fun sendEvent(serverSessionId: String, event: TelemetryEvent) = deliver {
                val body = JSONObject().put("eventId", event.eventId).put("type", event.type)
                event.stepId?.let { body.put("stepId", it) }; event.attemptId?.let { body.put("attemptId", it) }
                event.durationSeconds?.let { body.put("durationSeconds", it) }
                val acknowledgement = api.request("/assistant/sessions/$serverSessionId/events", "POST", body,
                    serverUrl = event.session.origin.serverUrl, installationToken = event.session.origin.installationToken)
                if (!acknowledgement.optBoolean("ok", false)) throw DeliveryFailure(502)
                Unit
            }
        }
        try { withTimeout(90_000) { OutboxProcessor(queue, transport).drain(changed = { publish() }) } }
        // Keep the lightweight recovery job: cancelling it on an empty snapshot can
        // race an enqueue while the immediate worker is finishing. Empty/paused queues do no HTTP.
        finally { publish() }
        mutable.value
    }
    private suspend fun <T> deliver(action: suspend () -> T): T = try { action() }
        catch (error: CancellationException) { throw error }
        catch (error: ApiFailure) { throw DeliveryFailure(error.status) }
        catch (_: Exception) { throw DeliveryFailure(0) }

    private fun publish(error: String = "") { outbox?.let { mutable.value = it.summary().copy(error = error) } }
    private fun schedule(context: Context) {
        if (!mutable.value.retryable) return
        val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniquePeriodicWork(RECOVERY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TelemetryWorker>(15, TimeUnit.MINUTES).setInitialDelay(15, TimeUnit.MINUTES)
                .setConstraints(network).build())
        manager.enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<TelemetryWorker>().setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    private fun key(): SecretKey {
        val alias = "nieto_telemetry_outbox_v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}

class TelemetryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val state = TelemetrySync.synchronize(applicationContext)
        if (state.retryable && runAttemptCount < 2) Result.retry() else Result.success()
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.success() }
}
