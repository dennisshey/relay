package com.sidephone.aviary.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.sidephone.aviary.data.UnifiedRepository
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
 * It flushes when the network comes back (ConnectivityManager callback) and on a slow periodic
 * fallback. Messages are retried IN PLACE via [MessageTransport.resend] — oldest first, and
 * per-conversation in order: the first failure in a conversation blocks the rest of that
 * conversation's queue for the round, so a later message never overtakes an earlier one.
 *
 * A permanently-undeliverable message (blocked number, etc.) would otherwise flicker
 * PENDING→FAILED every cycle forever, so each message gets a bounded number of AUTOMATIC
 * attempts; regaining network resets the counters, and manual "Try again" always bypasses it.
 */
class Outbox(
    private val context: Context,
    private val repo: UnifiedRepository,
    private val transports: TransportRegistry,
    private val scope: CoroutineScope,
) {
    private val flushMutex = Mutex()
    private val attempts = ConcurrentHashMap<Long, Int>()

    fun start() {
        // Retry as soon as any internet-capable network becomes available.
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        attempts.clear() // fresh connectivity — everything is worth another try
                        flush()
                    }
                },
            )
        }.onFailure { Log.w(TAG, "network callback registration failed", it) }

        // Startup pass + slow periodic fallback (covers failures that happened while the
        // callback wasn't registered, and transports that momentarily weren't ready).
        scope.launch {
            while (isActive) {
                flushOnce()
                delay(PERIODIC_MS)
            }
        }
    }

    fun flush() { scope.launch { flushOnce() } }

    private suspend fun flushOnce() = flushMutex.withLock {
        val failed = runCatching { repo.failedOutgoing() }.getOrElse { return@withLock }
        if (failed.isEmpty()) return@withLock
        val blocked = mutableSetOf<Long>() // conversations whose queue is stalled this round
        for (msg in failed) { // ordered oldest-first by the query
            if (msg.conversationId in blocked) continue
            if ((attempts[msg.id] ?: 0) >= MAX_AUTO_ATTEMPTS) continue
            val convo = repo.getConversation(msg.conversationId) ?: continue
            val transport = transports.byId(msg.transportId) ?: continue
            val result = runCatching { transport.resend(convo, msg) }.getOrElse { Result.failure(it) }
            if (result.isSuccess) {
                attempts.remove(msg.id)
            } else {
                attempts[msg.id] = (attempts[msg.id] ?: 0) + 1
                blocked.add(msg.conversationId) // preserve order: don't send later ones ahead of this
            }
        }
    }

    companion object {
        private const val TAG = "Outbox"
        private const val PERIODIC_MS = 60_000L
        private const val MAX_AUTO_ATTEMPTS = 5
    }
}
