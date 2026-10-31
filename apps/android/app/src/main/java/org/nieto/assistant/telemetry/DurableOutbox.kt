package org.nieto.assistant.telemetry

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class TelemetryOrigin(val serverUrl: String, val installationToken: String)
data class TelemetrySession(val clientSessionId: String, val flowId: String, val origin: TelemetryOrigin)
data class TelemetryEvent(val eventId: String, val session: TelemetrySession, val type: String, val stepId: String? = null,
    val durationSeconds: Int? = null, val attemptId: String? = null)
data class PendingEvent(val event: TelemetryEvent, val attempts: Int = 0, val nextAttemptAt: Long = 0, val paused: String? = null)
data class OutboxSummary(val pending: Int = 0, val paused: Int = 0, val retryable: Boolean = false, val error: String = "")

interface OutboxStorage { fun load(): List<PendingEvent>; fun save(entries: List<PendingEvent>) }
interface PayloadCipher { fun encrypt(clear: ByteArray): ByteArray; fun decrypt(sealed: ByteArray): ByteArray }

/** The exact same authenticated envelope is exercised in JVM tests with an ephemeral test key. */
class AesGcmEnvelope(private val key: () -> SecretKey) : PayloadCipher {
    override fun encrypt(clear: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(clear)
    }
    override fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed.size >= 30 && sealed[0] == 1.toByte()) { "Outbox inválida." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(1, 13)))
        return cipher.doFinal(sealed.copyOfRange(13, sealed.size))
    }
}

/** Both the main file and its crash-safe replacement contain ciphertext, never a clear JSON/token. */
class EncryptedFileOutbox(private val file: File, private val cipher: PayloadCipher) : OutboxStorage {
    override fun load(): List<PendingEvent> {
        if (!file.exists()) return emptyList()
        require(file.length() in 1..8_000_000) { "Outbox excedida o dañada." }
        val clear = cipher.decrypt(file.readBytes())
        return DataInputStream(ByteArrayInputStream(clear)).use { input ->
            require(input.readInt() == 1)
            val size = input.readInt(); require(size in 0..DurableOutbox.MAX_ENTRIES)
            val entries = List(size) {
                val origin = TelemetryOrigin(input.readUTF(), input.readUTF())
                val session = TelemetrySession(input.readUTF(), input.readUTF(), origin)
                val event = TelemetryEvent(input.readUTF(), session, input.readUTF(), nullable(input),
                    if (input.readBoolean()) input.readInt() else null, nullable(input))
                PendingEvent(event, input.readInt(), input.readLong(), nullable(input)).also { DurableOutbox.validate(it.event) }
            }
            require(input.available() == 0)
            entries
        }
    }
    override fun save(entries: List<PendingEvent>) {
        val clear = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(1); data.writeInt(entries.size)
            entries.forEach { pending ->
                val event = pending.event; val session = event.session
                data.writeUTF(session.origin.serverUrl); data.writeUTF(session.origin.installationToken)
                data.writeUTF(session.clientSessionId); data.writeUTF(session.flowId)
                data.writeUTF(event.eventId); data.writeUTF(event.type); nullable(data, event.stepId)
                data.writeBoolean(event.durationSeconds != null); event.durationSeconds?.let { data.writeInt(it) }
                nullable(data, event.attemptId); data.writeInt(pending.attempts); data.writeLong(pending.nextAttemptAt); nullable(data, pending.paused)
            }
        } }.toByteArray()
        val encrypted = cipher.encrypt(clear); require(encrypted.size <= 8_000_000)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val temporary = File(file.parentFile, file.name + ".pending")
        try {
            FileOutputStream(temporary).use { it.write(encrypted); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    private fun nullable(input: DataInputStream): String? = if (input.readBoolean()) input.readUTF() else null
    private fun nullable(output: DataOutputStream, value: String?) { output.writeBoolean(value != null); value?.let { output.writeUTF(it) } }
}

/** FIFO inside each original identity/session; a paused old server cannot block another session. */
class DurableOutbox(private val storage: OutboxStorage) {
    private var entries = storage.load()
    @Synchronized fun enqueue(event: TelemetryEvent) {
        validate(event)
        entries.find { it.event.eventId == event.eventId }?.let { require(it.event == event); return }
        check(entries.size < MAX_ENTRIES) { "La cola de progreso está llena. Los eventos existentes se conservan." }
        replace(entries + PendingEvent(event))
    }
    @Synchronized fun summary(): OutboxSummary {
        val heads = entries.distinctBy { it.event.session }
        val pausedSessions = heads.filter { it.paused != null }.map { it.event.session }.toSet()
        return OutboxSummary(entries.size, entries.count { it.event.session in pausedSessions }, heads.any { it.paused == null })
    }
    @Synchronized fun due(now: Long): PendingEvent? = entries.distinctBy { it.event.session }.firstOrNull { it.paused == null && it.nextAttemptAt <= now }
    @Synchronized fun acknowledge(id: String) { replace(entries.filterNot { it.event.eventId == id }) }
    @Synchronized fun fail(id: String, now: Long, status: Int) {
        replace(entries.map { pending -> if (pending.event.eventId != id) pending else {
            val attempts = pending.attempts + 1
            val reason = when {
                status == 401 || status == 403 -> "original_access"
                status in 400..499 && status !in listOf(408, 429) -> "protocol"
                attempts >= MAX_ATTEMPTS -> "retry_limit"
                else -> null
            }
            pending.copy(attempts = attempts, nextAttemptAt = now + backoff(attempts), paused = reason)
        } })
    }
    @Synchronized fun retryRetained() { replace(entries.map { it.copy(attempts = 0, nextAttemptAt = 0, paused = null) }) }
    private fun replace(next: List<PendingEvent>) { storage.save(next); entries = next }
    companion object {
        const val MAX_ENTRIES = 1000
        const val MAX_ATTEMPTS = 5
        fun backoff(attempts: Int): Long = (30_000L shl (attempts - 1).coerceIn(0, 9)).coerceAtMost(6 * 60 * 60_000L)
        fun validate(event: TelemetryEvent) {
            UUID.fromString(event.eventId); UUID.fromString(event.session.clientSessionId)
            event.attemptId?.let { UUID.fromString(it) }
            require(event.session.flowId in setOf("whatsapp-location", "screen-assistance", "whatsapp-group", "email-send"))
            require(event.type in setOf("flow_started", "step_shown", "step_completed", "step_repeated", "user_lost", "flow_reported", "practice_started", "practice_reported", "cancelled"))
            require(event.stepId == null || event.stepId in setOf("open_chat", "attach", "location", "confirm", "share", "verify", "screen-step", "open_whatsapp", "new_group", "select_people", "name_group", "review_group", "check_group", "open_email", "compose_email", "add_recipient", "add_subject", "write_email", "review_email", "send_email", "check_email"))
            require(event.durationSeconds == null || event.durationSeconds in 0..14_400)
            require(event.session.origin.serverUrl.length in 1..2048 && event.session.origin.installationToken.length in 1..512)
        }
    }
}

class DeliveryFailure(val status: Int) : Exception("No se pudo sincronizar el progreso.")
interface TelemetryTransport {
    suspend fun createSession(session: TelemetrySession): String
    suspend fun sendEvent(serverSessionId: String, event: TelemetryEvent)
}

class OutboxProcessor(private val outbox: DurableOutbox, private val transport: TelemetryTransport, private val now: () -> Long = System::currentTimeMillis) {
    suspend fun drain(limit: Int = 20, changed: () -> Unit = {}) {
        val sessions = mutableMapOf<TelemetrySession, String>()
        repeat(limit) {
            val pending = outbox.due(now()) ?: return
            try {
                val session = pending.event.session
                val serverId = sessions[session] ?: transport.createSession(session).also { sessions[session] = it }
                transport.sendEvent(serverId, pending.event)
            } catch (error: DeliveryFailure) { outbox.fail(pending.event.eventId, now(), error.status); changed(); return@repeat }
            outbox.acknowledge(pending.event.eventId); changed()
        }
    }
}
