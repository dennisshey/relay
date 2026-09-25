package com.sidephone.aviary.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationEntityTest {
    @Test
    fun instagramParticipantListMarksGroup() {
        val conversation = ConversationEntity(
            transportId = "instagram",
            externalId = "34028236684171030123456789012345678",
            address = "alice;bob",
            title = "Weekend plans",
        )

        assertTrue(conversation.isGroup)
    }

    @Test
    fun instagramSingleRecipientRemainsDirectMessage() {
        val conversation = ConversationEntity(
            transportId = "instagram",
            externalId = "34028236684171030123456789012345678",
            address = "alice",
            title = "Alice",
        )

        assertFalse(conversation.isGroup)
    }
}
