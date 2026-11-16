package com.sidephone.aviary.transport

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.sidephone.aviary.data.ConversationEntity
import com.sidephone.aviary.data.MessageEntity
import com.sidephone.aviary.data.MessageStatus
import com.sidephone.aviary.data.UnifiedRepository
import com.sidephone.aviary.transport.signal.SignalVerificationRequired
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline outbox: automatically retries outgoing messages that previously failed to send.
 *
 * It flushes when the network comes back and on a slow periodic fallback. Messages are retried
 * IN PLACE via [MessageTransport.resend] — oldest first, and per-conversation in order while a
 * retry is still in progress: the first *eligible* failure in a conversation blocks the rest of
 * that conversation for the round.
 *
 * Automatic retries run only inside [OutboxPolicy.AUTO_RETRY_WINDOW_MS] of the row's first
 * attempt, and only up to [OutboxPolicy.MAX_AUTO_ATTEMPTS]. A parked failure (too old, or written
 * before [com.sidephone.aviary.data.MessageEntity.firstAttemptAt] existed) stays FAILED until the
 * user taps Try again. Regaining network does not reset the cap or the window.
 */
class Outbox(
    private val context: Context,
    private val repo: UnifiedRepository,
    private val transports: TransportRegistry,
    private val scope: CoroutineScope,
) {
    private val flushMutex = Mutex()
    private val sweeper = OutboxSweeper(RoomOutboxStore(repo), transports)

    fun start() {
        scope.launch {
            // Retry as soon as any internet-capable network becomes available. This does not
            // forget how many attempts a message already used, and it does not revive a stale one.
            runCatching {
                context.getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            flush()
                        }
                    },
                )
            }.onFailure { Log.w(TAG, "network callback registration failed", it) }

            // One startup sweep (process start, which is also what BOOT_COMPLETED and
            // MY_PACKAGE_REPLACED cause), then a slow periodic pass over whatever is still
            // inside the window. startedAt stays the original process start: a sweep that
            // retries after the 60s delay must not treat a send begun in this process as
            // interrupted.
            val startedAt = System.currentTimeMillis()
            var swept = false
            while (isActive) {
                val now = System.currentTimeMillis()
                if (!swept) {
                    val ok = runCatching { guardedSweep(now, startedAt) }
                        .onFailure { Log.w(TAG, "outbox recovery failed", it) }
                        .isSuccess
                    if (ok) swept = true
                } else {
                    guardedFlush(now)
                }
                delay(PERIODIC_MS)
            }
        }
    }

    fun flush() { scope.launch { guardedFlush(System.currentTimeMillis()) } }

    fun resumeAfterVerification(transportId: String) {
        scope.launch {
            flushMutex.withLock {
                // Verification is a user action that unblocks a send which failed without
                // spending the cap. The window still applies, so this cannot drain the backlog.
                sweeper.clearAutoAttempts(transportId)
                sweeper.flush(System.currentTimeMillis())
            }
        }
    }

    /** Manual "Try again": send this row once, even when it is outside the automatic window. */
    suspend fun retryManually(conversation: ConversationEntity, message: MessageEntity): Result<Unit> =
        flushMutex.withLock { sweeper.retryManually(conversation, message) }

    private suspend fun guardedSweep(now: Long, startedAt: Long) = flushMutex.withLock {
        sweeper.sweepAfterProcessStart(now, startedAt)
    }

    private suspend fun guardedFlush(now: Long) = flushMutex.withLock {
        sweeper.flush(now)
    }

    companion object {
        private const val TAG = "Outbox"
        private const val PERIODIC_MS = 60_000L
    }
}

/**
 * True for the broadcasts that start a new process after reboot or an in-place app update.
 * Both end up in [Outbox.start], which is the sweep that used to resend every failed row.
 */
internal fun isBootOrPackageReplaced(action: String?): Boolean =
    action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED

internal object OutboxPolicy {
    /**
     * How long after the first attempt an automatic retry is still allowed.
     *
     * Three minutes is long enough for a radio blip, the 60s periodic pass, and a process
     * restart during an active send. It is short enough that an update, reboot, or later
     * process start cannot deliver a message the user already watched fail. Five automatic
     * attempts is the burst cap inside that window; the periodic pass is not required to
     * exhaust it.
     */
    const val AUTO_RETRY_WINDOW_MS = 3 * 60 * 1000L

    const val MAX_AUTO_ATTEMPTS = 5

    /** Null and non-positive timestamps are stale (pre-migration rows, or a missing stamp).
     *  A clock step backward (now < started) is not a fresh attempt. */
    fun eligibleForAutoRetry(firstAttemptAt: Long?, now: Long): Boolean {
        val started = firstAttemptAt ?: return false
        if (started <= 0L) return false
        if (now < started) return false
        return now - started <= AUTO_RETRY_WINDOW_MS
    }
}

internal interface OutboxStore {
    suspend fun failedOutgoing(): List<MessageEntity>
    suspend fun pendingOutgoing(): List<MessageEntity>
    suspend fun message(id: Long): MessageEntity?
    suspend fun getConversation(id: Long): ConversationEntity?
    suspend fun setMessageStatus(id: Long, status: MessageStatus)
}

private class RoomOutboxStore(private val repo: UnifiedRepository) : OutboxStore {
    override suspend fun failedOutgoing() = repo.failedOutgoing()
    override suspend fun pendingOutgoing() = repo.pendingOutgoing()
    override suspend fun message(id: Long) = repo.message(id)
    override suspend fun getConversation(id: Long) = repo.getConversation(id)
    override suspend fun setMessageStatus(id: Long, status: MessageStatus) = repo.setMessageStatus(id, status)
}

/**
 * The startup sweep and the retry loop, with no Android context so tests can drive them.
 * [sweepAfterProcessStart] is what an app update, reboot, and ordinary process start all run.
 */
internal class OutboxSweeper(
    private val store: OutboxStore,
    private val transports: TransportRegistry,
    private val attempts: MutableMap<Long, Int> = ConcurrentHashMap(),
) {
    suspend fun sweepAfterProcessStart(now: Long, startedAt: Long = now) {
        expireStaleInFlight(now, startedAt)
        flush(now)
    }

    suspend fun flush(now: Long) {
        val failed = runCatching { store.failedOutgoing() }.getOrElse { return }
        if (failed.isEmpty()) return
        val blocked = mutableSetOf<Long>()
        for (msg in failed) {
            if (msg.conversationId in blocked) continue
            // Parked failures must not wedge the rest of the conversation behind them.
            if (!OutboxPolicy.eligibleForAutoRetry(msg.firstAttemptAt, now)) continue
            if ((attempts[msg.id] ?: 0) >= OutboxPolicy.MAX_AUTO_ATTEMPTS) continue
            val convo = store.getConversation(msg.conversationId) ?: continue
            val transport = transports.byId(msg.transportId) ?: continue
            if (transport.sendingPaused) continue
            val result = runCatching { transport.resend(convo, msg) }.getOrElse { Result.failure(it) }
            if (result.isSuccess) {
                attempts.remove(msg.id)
            } else if (result.exceptionOrNull() is SendDeferredException ||
                result.exceptionOrNull() is SignalVerificationRequired
            ) {
                blocked.add(msg.conversationId)
            } else {
                attempts[msg.id] = (attempts[msg.id] ?: 0) + 1
                blocked.add(msg.conversationId)
            }
        }
    }

    /**
     * Send [message] once. Does not refresh [MessageEntity.firstAttemptAt] and does not spend or
     * reset the automatic cap, so a stale row cannot fall back into the startup sweep.
     *
     * Reloads the row first. A second tap, or a tap queued behind an automatic retry that already
     * succeeded, sees SENT (or anything other than FAILED) and does not send again.
     */
    suspend fun retryManually(conversation: ConversationEntity, message: MessageEntity): Result<Unit> {
        val current = store.message(message.id) ?: return Result.success(Unit)
        if (current.status != MessageStatus.FAILED) return Result.success(Unit)
        val transport = transports.byId(current.transportId)
            ?: return Result.failure(IllegalStateException("No transport for ${current.transportId}"))
        return runCatching { transport.resend(conversation, current) }.getOrElse { Result.failure(it) }
    }

    suspend fun clearAutoAttempts(transportId: String) {
        store.failedOutgoing().filter { it.transportId == transportId }.forEach { attempts.remove(it.id) }
    }

    private suspend fun expireStaleInFlight(now: Long, startedAt: Long) {
        val pending = store.pendingOutgoing()
        for (msg in pending) {
            // Began in this process. The original send is still the one in flight; failing it
            // here and resending would deliver a second copy. Matches the old
            // `timestamp < startedAt` guard, including when this sweep retries a minute later.
            val started = msg.firstAttemptAt
            if (started != null && started >= startedAt) continue
            val fresh = OutboxPolicy.eligibleForAutoRetry(started, now)
            if (!fresh || msg.transportId in INTERRUPT_RESUME_TRANSPORTS) {
                // Past the window (or never stamped): mark FAILED and leave it for the flush,
                // which will not send it. A fresh signal/iMessage PENDING is moved to FAILED so
                // the same flush can resume it inside the window. SMS/MMS stays PENDING while
                // it is fresh — the platform reports the result later, and resending would duplicate.
                store.setMessageStatus(msg.id, MessageStatus.FAILED)
            }
        }
    }

    private companion object {
        val INTERRUPT_RESUME_TRANSPORTS = setOf("signal", "imessage")
    }
}
