package com.sidephone.aviary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import androidx.compose.runtime.remember
import com.sidephone.aviary.data.ConversationEntity
import java.io.File
import kotlin.math.absoluteValue

/**
 * Group threads: Signal + group MMS are keyed "group:<id>"; an iMessage group is keyed by its
 * ";"-joined participant list. Drives the group avatar and the per-sender name labels on bubbles.
 */
val ConversationEntity.isGroup: Boolean
    get() = externalId.startsWith("group:") || externalId.contains(";")

private val AvatarPalette = listOf(
    Color(0xFF3B82F6), Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFFF97316),
    Color(0xFF14B8A6), Color(0xFFEF4444), Color(0xFF6366F1), Color(0xFFF59E0B),
)

/** Stable per-conversation color so avatars stay recognizable across sessions. */
fun avatarColor(seed: String): Color =
    AvatarPalette[(seed.hashCode().absoluteValue) % AvatarPalette.size]

@Composable
fun ConversationAvatar(convo: ConversationEntity, size: Dp, avatarPath: String? = null) {
    // One clip, one child, a colored circle behind. For photo rows we DON'T measure the initials
    // Text or draw the base over-then-under the photo — that overdraw + text layout per row was
    // the main scroll cost on this low-end GPU.
    Box(
        Modifier.size(size).clip(CircleShape).background(avatarColor(convo.externalId)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            avatarPath != null -> {
                val file = remember(avatarPath) { File(avatarPath) }
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            convo.isGroup ->
                Icon(Icons.Filled.Groups, null, tint = Color.White, modifier = Modifier.size(size * 0.58f))
            else -> Text(
                convo.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                color = Color.White, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
