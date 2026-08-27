package com.sidephone.aviary.transport

import com.sidephone.aviary.data.ConversationEntity
import com.sidephone.aviary.transport.sms.SmsTransport

/**
 * iMessage-style automatic routing (the OpenBubbles behavior): a phone-number
 * thread prefers iMessage whenever the recipient is reachable there and the
 * iMessage transport is up, and silently falls back to SMS otherwise. The choice
 * is made per send, so a thread can mix blue and green messages just like the
 * real thing. Threads owned by other protocols (Signal, Instagram) always stay
 * on their own protocol.
 */
class MessageRouter(private val registry: TransportRegistry) {

    suspend fun resolve(conversation: ConversationEntity): MessageTransport {
        val home = registry.byId(conversation.transportId)
            ?: registry.byId(SmsTransport.ID)!!
        if (conversation.transportId != SmsTransport.ID) return home

        val imessage = registry.byId(IMESSAGE_ID) ?: return home
        val reachable = imessage.status.value is TransportStatus.Ready &&
            (imessage as? ReachabilityAware)?.canReach(conversation.address) == true
        return if (reachable) imessage else home
    }

    companion object {
        const val IMESSAGE_ID = "imessage"
    }
}
