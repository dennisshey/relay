package com.sidephone.aviary.transport.signal

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Persist the exact content and recipient snapshot so retries only send missing copies. */
internal data class GroupDelivery(
    val content: ByteArray,
    val recipients: Set<String>,
    val accepted: Set<String> = emptySet(),
) {
    val pending: Set<String> get() = recipients - accepted
    fun deliver(
        currentMembers: Set<String>, save: (GroupDelivery) -> Unit,
        send: (String, ByteArray) -> Result<Unit>,
    ): GroupDelivery {
        var state = copy(recipients = recipients.intersect(currentMembers))
        save(state)
        val progressLock = Any()
        val failures = kotlinx.coroutines.runBlocking {
            val limit = kotlinx.coroutines.sync.Semaphore(4)
            state.pending.map { member ->
                async(kotlinx.coroutines.Dispatchers.IO) {
                    limit.withPermit {
                        val result = send(member, content)
                        if (result.isSuccess) synchronized(progressLock) {
                            val next = state.copy(accepted = state.accepted + member)
                            save(next)
                            state = next
                        }
                        result.exceptionOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
        failures.filterIsInstance<SignalVerificationRequired>().firstOrNull()?.let { throw it }
        failures.filterIsInstance<com.sidephone.aviary.transport.SendDeferredException>()
            .maxByOrNull { it.retryAt }?.let { throw it }
        check(failures.isEmpty()) {
            "Not sent to ${failures.size} group member(s). ${failures.first().message}"
        }
        return state
    }

    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(content.size); out.write(content)
            out.writeInt(recipients.size); recipients.forEach { out.writeUTF(it) }
            out.writeInt(accepted.size); accepted.forEach { out.writeUTF(it) }
        }
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): GroupDelivery = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val length = input.readInt()
            require(length in 0..bytes.size)
            val content = ByteArray(length).also { input.readFully(it) }
            fun members(): Set<String> {
                val count = input.readInt()
                require(count in 0..100_000)
                return (0 until count).map { input.readUTF() }.toSet()
            }
            GroupDelivery(content, members(), members())
        }
    }
}
