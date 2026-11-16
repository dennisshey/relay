package com.sidephone.aviary.transport

import android.content.Intent
import com.sidephone.aviary.data.ConversationEntity
import com.sidephone.aviary.data.MessageEntity
import com.sidephone.aviary.data.MessageStatus
import com.sidephone.aviary.data.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automatic retry, with fakes. No socket, no carrier, no WorkManager.
 *
 * A process start — opening the app, [Intent.ACTION_BOOT_COMPLETED], or
 * [Intent.ACTION_MY_PACKAGE_REPLACED] — all run [OutboxSweeper.sweepAfterProcessStart].
 */
class OutboxRetryTest {

    @Test fun staleFailuresAreNotResentOnStartupOrAfterUpdateOrBoot() {
        val now = 1_700_000_000_000L
        val triggers = listOf(
            "process-start",
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
        for (trigger in triggers) {
            val harness = harness(
                failed(id = 1, firstAttemptAt = now - OutboxPolicy.AUTO_RETRY_WINDOW_MS - 1, timestamp = 10),
                failed(id = 2, firstAttemptAt = null, timestamp = 20),
                failed(id = 3, firstAttemptAt = 0L, timestamp = 30),
            )
            assertTrue(trigger == "process-start" || isBootOrPackageReplaced(trigger))
            runBlocking { harness.sweeper.sweepAfterProcessStart(now) }
            assertEquals(trigger, emptyList<Long>(), harness.transport.sent)
            assertTrue(harness.store.messages.all { it.status == MessageStatus.FAILED })
        }
        assertTrue(!isBootOrPackageReplaced(Intent.ACTION_PACKAGE_REPLACED))
    }

    @Test fun aParkedFailureDoesNotBlockANewerMessageInTheSameChat() {
        val now = 6_000_000L
        val harness = harness(
            failed(id = 31, firstAttemptAt = null, timestamp = 10),
            failed(id = 32, firstAttemptAt = now - 2_000, timestamp = 20),
        )
        runBlocking { harness.sweeper.sweepAfterProcessStart(now) }
        assertEquals(listOf(32L), harness.transport.sent)
    }

    @Test fun freshFailureRetriesWithinTheWindowAndStopsAtTheCap() {
        val now = 5_000_000L
        val edge = harness(
            failed(id = 8, firstAttemptAt = now - OutboxPolicy.AUTO_RETRY_WINDOW_MS, timestamp = 1),
        )
        runBlocking { edge.sweeper.flush(now) }
        assertEquals(listOf(8L), edge.transport.sent)

        val harness = harness(
            failed(id = 4, firstAttemptAt = now - 1_000, timestamp = 1),
        )
        runBlocking {
            repeat(OutboxPolicy.MAX_AUTO_ATTEMPTS + 3) {
                harness.sweeper.flush(now)
            }
        }
        assertEquals(OutboxPolicy.MAX_AUTO_ATTEMPTS, harness.transport.sent.size)
        assertTrue(harness.transport.sent.all { it == 4L })
        assertEquals(MessageStatus.FAILED, harness.store.messages.single().status)

        val cutOff = harness(
            failed(id = 5, firstAttemptAt = now - 1_000, timestamp = 1),
        )
        runBlocking {
            cutOff.sweeper.flush(now)
            cutOff.sweeper.flush(now)
            cutOff.sweeper.flush(now + OutboxPolicy.AUTO_RETRY_WINDOW_MS + 1)
        }
        assertEquals(2, cutOff.transport.sent.size)
    }

    @Test fun manualRetryOfAnOldFailureSendsExactlyOnce() {
        val now = 9_000_000L
        val old = failed(id = 7, firstAttemptAt = now - OutboxPolicy.AUTO_RETRY_WINDOW_MS - 60_000, timestamp = 50)
        val harness = harness(old)
        val convo = harness.store.conversations.single()
        runBlocking {
            harness.sweeper.sweepAfterProcessStart(now)
            val result = harness.sweeper.retryManually(convo, old)
            assertTrue(result.isFailure)
            // A later update/boot/restart must not pick the row up again.
            val restarted = OutboxSweeper(harness.store, TransportRegistry(listOf(harness.transport)))
            restarted.sweepAfterProcessStart(now)
            restarted.flush(now)
        }
        assertEquals(listOf(7L), harness.transport.sent)
        assertEquals(MessageStatus.FAILED, harness.store.messages.single().status)
        assertEquals(old.firstAttemptAt, harness.store.messages.single().firstAttemptAt)
    }

    @Test fun twoManualRetriesSendOnce() {
        val now = 9_000_000L
        val row = failed(id = 44, firstAttemptAt = now - OutboxPolicy.AUTO_RETRY_WINDOW_MS - 60_000, timestamp = 50)
        val harness = harness(row)
        harness.transport.fail = false
        val convo = harness.store.conversations.single()
        runBlocking {
            harness.sweeper.retryManually(convo, row)
            harness.sweeper.retryManually(convo, row)
        }
        assertEquals(listOf(44L), harness.transport.sent)
        assertEquals(MessageStatus.SENT, harness.store.messages.single().status)
    }

    @Test fun manualRetryAfterASuccessfulAutoRetrySendsNothing() {
        val now = 5_000_000L
        val row = failed(id = 45, firstAttemptAt = now - 1_000, timestamp = 1)
        val harness = harness(row)
        harness.transport.fail = false
        runBlocking {
            harness.sweeper.flush(now)
            assertEquals(listOf(45L), harness.transport.sent)
            val result = harness.sweeper.retryManually(harness.store.conversations.single(), row)
            assertTrue(result.isSuccess)
        }
        assertEquals(listOf(45L), harness.transport.sent)
    }

    @Test fun aClockStepBackwardDoesNotRetry() {
        val started = 1_700_000_000_000L
        val now = started - 3_600_000L
        assertTrue(!OutboxPolicy.eligibleForAutoRetry(started, now))
        val harness = harness(failed(id = 46, firstAttemptAt = started, timestamp = 1))
        runBlocking { harness.sweeper.flush(now) }
        assertEquals(emptyList<Long>(), harness.transport.sent)
        assertEquals(MessageStatus.FAILED, harness.store.messages.single().status)
    }

    @Test fun startupRetryLeavesASignalSendThatBeganAfterLaunch() {
        val startedAt = 1_000_000L
        val harness = harness(
            pending(id = 47, transportId = "signal", firstAttemptAt = startedAt + 30_000),
            pending(id = 48, transportId = "imessage", firstAttemptAt = startedAt),
        )
        runBlocking { harness.sweeper.sweepAfterProcessStart(now = startedAt + 60_000, startedAt = startedAt) }
        assertEquals(emptyList<Long>(), harness.transport.sent)
        assertTrue(harness.store.messages.all { it.status == MessageStatus.PENDING })
    }

    @Test fun inFlightRowPastTheWindowIsFailedAndNotSent() {
        val now = 4_000_000L
        val harness = harness(
            pending(id = 11, transportId = "signal", firstAttemptAt = now - OutboxPolicy.AUTO_RETRY_WINDOW_MS - 1),
            pending(id = 12, transportId = "imessage", firstAttemptAt = null),
            pending(id = 13, transportId = "sms", firstAttemptAt = 0L),
            pending(id = 14, transportId = "instagram", firstAttemptAt = now - 86_400_000L),
        )
        runBlocking { harness.sweeper.sweepAfterProcessStart(now) }
        assertEquals(emptyList<Long>(), harness.transport.sent)
        assertTrue(harness.store.messages.all { it.status == MessageStatus.FAILED })
    }

    @Test fun freshInterruptedSignalSendRetriesInsideTheWindow() {
        val now = 8_000L
        val harness = harness(
            pending(id = 21, transportId = "signal", firstAttemptAt = now - 5_000),
        )
        harness.transport.fail = false
        runBlocking { harness.sweeper.sweepAfterProcessStart(now) }
        assertEquals(listOf(21L), harness.transport.sent)
        assertEquals(MessageStatus.SENT, harness.store.messages.single().status)
    }

    @Test fun freshSmsPendingIsLeftAloneSoItIsNotDoubleSent() {
        val now = 8_000L
        val harness = harness(
            pending(id = 22, transportId = "sms", firstAttemptAt = now - 5_000),
        )
        runBlocking { harness.sweeper.sweepAfterProcessStart(now) }
        assertEquals(emptyList<Long>(), harness.transport.sent)
        assertEquals(MessageStatus.PENDING, harness.store.messages.single().status)
    }
}

private class Harness(vararg initial: MessageEntity) {
    val store = FakeOutboxStore(initial.toMutableList())
    val transport = FakeTransport(store)
    val sweeper = OutboxSweeper(
        store,
        TransportRegistry(listOf(transport, transport.sms, transport.imessage, transport.instagram)),
    )

    init {
        store.conversations += ConversationEntity(
            id = 1L,
            transportId = "signal",
            externalId = "ext",
            address = "+15555550100",
            title = "Ada",
        )
    }
}

private fun harness(vararg initial: MessageEntity) = Harness(*initial)

private fun failed(id: Long, firstAttemptAt: Long?, timestamp: Long) = message(
    id = id,
    status = MessageStatus.FAILED,
    firstAttemptAt = firstAttemptAt,
    timestamp = timestamp,
)

private fun pending(id: Long, transportId: String, firstAttemptAt: Long?) = message(
    id = id,
    status = MessageStatus.PENDING,
    transportId = transportId,
    firstAttemptAt = firstAttemptAt,
    timestamp = id,
)

private fun message(
    id: Long,
    status: MessageStatus,
    firstAttemptAt: Long?,
    timestamp: Long,
    transportId: String = "signal",
) = MessageEntity(
    id = id,
    conversationId = 1L,
    transportId = transportId,
    externalId = "ext-$id",
    sender = "me",
    body = "hello $id",
    timestamp = timestamp,
    outgoing = true,
    status = status,
    firstAttemptAt = firstAttemptAt,
)

private class FakeOutboxStore(val messages: MutableList<MessageEntity>) : OutboxStore {
    val conversations = mutableListOf<ConversationEntity>()

    override suspend fun failedOutgoing(): List<MessageEntity> =
        messages.filter { it.outgoing && it.status == MessageStatus.FAILED }
            .sortedWith(compareBy({ it.timestamp }, { it.id }))

    override suspend fun pendingOutgoing(): List<MessageEntity> =
        messages.filter { it.outgoing && it.status == MessageStatus.PENDING }
            .sortedWith(compareBy({ it.timestamp }, { it.id }))

    override suspend fun message(id: Long): MessageEntity? = messages.find { it.id == id }

    override suspend fun getConversation(id: Long): ConversationEntity? =
        conversations.find { it.id == id }

    override suspend fun setMessageStatus(id: Long, status: MessageStatus) {
        val index = messages.indexOfFirst { it.id == id }
        check(index >= 0) { "missing message $id" }
        messages[index] = messages[index].copy(status = status)
    }
}

private class FakeTransport(private val store: FakeOutboxStore) : MessageTransport {
    val sent = mutableListOf<Long>()
    var fail = true
    override val id = "signal"
    override val protocol = Protocol.SIGNAL
    override val status = MutableStateFlow<TransportStatus>(TransportStatus.Ready)
    val sms = sibling("sms")
    val imessage = sibling("imessage")
    val instagram = sibling("instagram")

    override suspend fun start() = Unit

    override suspend fun sendText(
        conversation: ConversationEntity,
        body: String,
        replyTo: MessageEntity?,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun resend(conversation: ConversationEntity, message: MessageEntity): Result<Unit> {
        sent += message.id
        return if (fail) {
            store.setMessageStatus(message.id, MessageStatus.FAILED)
            Result.failure(IllegalStateException("offline"))
        } else {
            store.setMessageStatus(message.id, MessageStatus.SENT)
            Result.success(Unit)
        }
    }

    private fun sibling(transportId: String): MessageTransport = object : MessageTransport {
        override val id = transportId
        override val protocol = Protocol.SIGNAL
        override val status = MutableStateFlow<TransportStatus>(TransportStatus.Ready)
        override suspend fun start() = Unit
        override suspend fun sendText(
            conversation: ConversationEntity,
            body: String,
            replyTo: MessageEntity?,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun resend(conversation: ConversationEntity, message: MessageEntity): Result<Unit> =
            this@FakeTransport.resend(conversation, message)
    }
}
