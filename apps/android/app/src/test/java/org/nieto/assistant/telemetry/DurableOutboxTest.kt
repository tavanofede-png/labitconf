package org.nieto.assistant.telemetry

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator

class DurableOutboxTest {
    @Rule @JvmField val folder = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
    private fun storage(file: File) = EncryptedFileOutbox(file, AesGcmEnvelope { key })
    private fun session(server: String = "https://original.example", token: String = "original-private-device-token") =
        TelemetrySession(UUID.randomUUID().toString(), "whatsapp-location", TelemetryOrigin(server, token))
    private fun event(session: TelemetrySession, type: String = "flow_started") = TelemetryEvent(UUID.randomUUID().toString(), session, type)

    @Test fun encryptedQueueSurvivesRestartWithoutWritingCredentialsOrScreenContent() = runBlocking {
        val file = File(folder.root, "queue.bin"); val original = session(); val event = event(original)
        DurableOutbox(storage(file)).enqueue(event)
        val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains(original.origin.installationToken)); assertFalse(bytes.contains(original.origin.serverUrl))
        assertFalse(bytes.contains("flow_started")); assertFalse(bytes.contains(event.eventId))
        val restored = DurableOutbox(storage(file))
        assertEquals(event, restored.due(0)!!.event)
        var sent = 0
        OutboxProcessor(restored, object : TelemetryTransport {
            override suspend fun createSession(session: TelemetrySession): String { assertEquals(original, session); return "server-session" }
            override suspend fun sendEvent(serverSessionId: String, event: TelemetryEvent) { sent++ }
        }).drain()
        assertEquals(1, sent); assertEquals(0, DurableOutbox(storage(file)).summary().pending)
    }
    @Test fun offlineBackoffSurvivesProcessRestartAndKeepsEventIdentity() = runBlocking {
        val file = File(folder.root, "queue.bin"); val item = event(session()); val first = DurableOutbox(storage(file))
        first.enqueue(item)
        val offline = object : TelemetryTransport {
            override suspend fun createSession(session: TelemetrySession): String = throw DeliveryFailure(0)
            override suspend fun sendEvent(serverSessionId: String, event: TelemetryEvent) { fail("must not send while offline") }
        }
        OutboxProcessor(first, offline) { 1000L }.drain()
        val restored = DurableOutbox(storage(file))
        assertNull(restored.due(30_999)); assertEquals(item, restored.due(31_000)!!.event)
        assertEquals(1, restored.due(31_000)!!.attempts)
        assertEquals(1, restored.summary().pending)
    }
    @Test fun acknowledgedServerRequestWithLostResponseReplaysTheSameIdsExactlyOnce() = runBlocking {
        val file = File(folder.root, "queue.bin"); val item = event(session()); val queue = DurableOutbox(storage(file)); queue.enqueue(item)
        val sessions = mutableMapOf<String, String>(); val accepted = mutableSetOf<String>(); var requests = 0
        val transport = object : TelemetryTransport {
            override suspend fun createSession(session: TelemetrySession): String = sessions.getOrPut(session.clientSessionId) { "stable-server-id" }
            override suspend fun sendEvent(serverSessionId: String, event: TelemetryEvent) {
                accepted.add(event.eventId); requests++
                if (requests == 1) throw DeliveryFailure(0) // Server accepted, network lost its response.
            }
        }
        OutboxProcessor(queue, transport) { 0 }.drain()
        val restarted = DurableOutbox(storage(file))
        OutboxProcessor(restarted, transport) { 30_000 }.drain()
        assertEquals(2, requests); assertEquals(1, sessions.size); assertEquals(setOf(item.eventId), accepted)
        assertEquals(0, restarted.summary().pending)
    }
    @Test fun originalIdentityNeverChangesAndOneUnavailableServerDoesNotBlockAnother() = runBlocking {
        val queue = DurableOutbox(storage(File(folder.root, "queue.bin")))
        val old = session(); val current = session("https://new.example", "new-device-token")
        val oldStart = event(old); val oldStep = event(old, "step_shown").copy(stepId = "open_chat")
        queue.enqueue(oldStart); queue.enqueue(oldStep); queue.enqueue(event(current))
        val sent = mutableListOf<TelemetryEvent>()
        OutboxProcessor(queue, object : TelemetryTransport {
            override suspend fun createSession(session: TelemetrySession): String {
                if (session == old) throw DeliveryFailure(401)
                assertEquals(current.origin, session.origin); return "current-server-session"
            }
            override suspend fun sendEvent(serverSessionId: String, event: TelemetryEvent) { sent.add(event) }
        }) { 0 }.drain()
        assertEquals(listOf(current), sent.map { it.session })
        assertEquals(2, queue.summary().pending); assertEquals(2, queue.summary().paused)
        assertFalse(queue.summary().retryable); assertNull(queue.due(Long.MAX_VALUE))
        queue.retryRetained()
        assertEquals(oldStart, queue.due(0)!!.event)
        assertEquals(old.origin, queue.due(0)!!.event.session.origin)
    }
    @Test fun retriesAreBoundedAndNeverSkipAnEarlierEventInTheSameSession() {
        val queue = DurableOutbox(storage(File(folder.root, "queue.bin"))); val session = session(); val first = event(session)
        queue.enqueue(first); queue.enqueue(event(session, "cancelled"))
        repeat(DurableOutbox.MAX_ATTEMPTS) { index ->
            assertEquals(first.eventId, queue.due(Long.MAX_VALUE)!!.event.eventId)
            queue.fail(first.eventId, 0, 503)
            if (index < DurableOutbox.MAX_ATTEMPTS - 1) assertEquals(DurableOutbox.backoff(index + 1), queue.due(Long.MAX_VALUE)!!.nextAttemptAt)
        }
        assertEquals(2, queue.summary().paused); assertNull(queue.due(Long.MAX_VALUE))
    }
    @Test fun duplicateEnqueueDoesNotInflatePendingCountAndCorruptionNeverResetsTheQueue() {
        val file = File(folder.root, "queue.bin"); val queue = DurableOutbox(storage(file)); val item = event(session())
        queue.enqueue(item); queue.enqueue(item); assertEquals(1, queue.summary().pending)
        val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
        assertThrows(Exception::class.java) { DurableOutbox(storage(file)) }
        assertArrayEquals(bytes, file.readBytes())
    }
    @Test fun unauthorizedAndProtocolFailuresPauseInsteadOfDroppingOrHotLooping() {
        listOf(401, 403, 400, 409, 404).forEach { status ->
            val queue = DurableOutbox(storage(File(folder.root, "queue-$status.bin"))); val item = event(session())
            queue.enqueue(item); queue.fail(item.eventId, 0, status)
            assertEquals(1, queue.summary().pending); assertEquals(1, queue.summary().paused)
            assertNull(queue.due(Long.MAX_VALUE))
        }
    }
}
