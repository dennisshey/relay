package com.sidephone.aviary.transport.signal

import org.junit.Assert.*
import org.junit.Test

class GroupDeliveryTest {
    @Test fun captchaFailureKeepsAcceptedRecipientsForResume() {
        val original = GroupDelivery(byteArrayOf(1), setOf("accepted", "challenged"))
        var disk = original.encode()
        val error = runCatching {
            original.deliver(original.recipients, { disk = it.encode() }) { member, _ ->
                if (member == "accepted") Result.success(Unit) else Result.failure(SignalVerificationRequired())
            }
        }.exceptionOrNull()
        assertTrue(error is SignalVerificationRequired)
        assertEquals(setOf("challenged"), GroupDelivery.decode(disk).pending)
    }

    @Test fun rateLimitedMemberKeepsProgressAndDefersWithoutLosingRetryDeadline() {
        val original = GroupDelivery(byteArrayOf(1), setOf("a", "b", "c"))
        var disk = original.encode()
        val failure = runCatching {
            original.deliver(original.recipients, { disk = it.encode() }) { member, _ ->
                if (member == "a") Result.success(Unit)
                else Result.failure(com.sidephone.aviary.transport.SendDeferredException(if (member == "b") 100L else 200L))
            }
        }.exceptionOrNull()
        assertEquals(200L, (failure as com.sidephone.aviary.transport.SendDeferredException).retryAt)
        val restored = GroupDelivery.decode(disk)
        assertEquals(setOf("a"), restored.accepted)
        val retried = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        restored.deliver(restored.recipients, {}) { member, _ ->
            retried.add(member)
            Result.success(Unit)
        }
        assertEquals(setOf("b", "c"), retried)
    }

    @Test fun partialFailureSurvivesRestartAndOnlyMissingMembersAreRetried() {
        val original = GroupDelivery(byteArrayOf(1, 2, 3), setOf("a", "b", "c"))
        var disk = original.encode()
        val failed = runCatching {
            original.deliver(original.recipients, { disk = it.encode() }) { member, _ ->
                if (member == "b") Result.failure(Exception("offline")) else Result.success(Unit)
            }
        }
        assertTrue(failed.isFailure)
        val restored = GroupDelivery.decode(disk)
        assertEquals(setOf("b"), restored.pending)
        val attempted = mutableListOf<String>()
        val complete = restored.deliver(original.recipients, { disk = it.encode() }) { member, content ->
            attempted.add(member)
            assertArrayEquals(original.content, content)
            Result.success(Unit)
        }
        assertEquals(listOf("b"), attempted)
        assertTrue(complete.pending.isEmpty())
        GroupDelivery.decode(disk).deliver(original.recipients, {}) { _, _ ->
            fail("Completed recipient must not receive a second copy")
            Result.success(Unit)
        }
    }

    @Test fun retriesDoNotSendOldContentToNewMembersOrRemovedMembers() {
        val original = GroupDelivery(byteArrayOf(1), setOf("existing", "removed"))
        val attempted = mutableListOf<String>()
        val result = original.deliver(setOf("existing", "new"), {}) { member, _ ->
            attempted.add(member); Result.success(Unit)
        }
        assertEquals(listOf("existing"), attempted)
        assertTrue(result.pending.isEmpty())
    }

    @Test fun persistenceFailureDoesNotReportSuccess() {
        val original = GroupDelivery(byteArrayOf(1), setOf("a"))
        val result = runCatching {
            original.deliver(original.recipients, {
                if (it.accepted.isNotEmpty()) error("Disk full")
            }) { _, _ -> Result.success(Unit) }
        }
        assertTrue(result.isFailure)
    }
}
