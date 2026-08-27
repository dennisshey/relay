package com.sidephone.aviary.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which conversations currently have the other party typing, so the thread UI can show a
 * "typing…" bubble. Entries auto-expire (protocols send "started typing" but not always a clean
 * "stopped"), so a stale indicator never gets stuck.
 */
class TypingTracker(private val scope: CoroutineScope) {

    private val _typing = MutableStateFlow<Set<Long>>(emptySet())
    val typing: StateFlow<Set<Long>> = _typing
    private val expiries = ConcurrentHashMap<Long, Job>()

    /** Mark [conversationId] as typing (or not). A true entry clears itself after [ttlMs]. */
    fun set(conversationId: Long, isTyping: Boolean, ttlMs: Long = 12_000) {
        expiries.remove(conversationId)?.cancel()
        if (isTyping) {
            _typing.update { it + conversationId }
            expiries[conversationId] = scope.launch {
                delay(ttlMs)
                _typing.update { it - conversationId }
            }
        } else {
            _typing.update { it - conversationId }
        }
    }
}
