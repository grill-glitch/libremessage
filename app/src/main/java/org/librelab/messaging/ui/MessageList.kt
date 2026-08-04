package org.librelab.messaging.ui

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.composables.icons.materialsymbols.outlined.Package_2
import com.composables.icons.materialsymbols.outlined.Safety_check

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.outlined.Check_circle
import com.composables.icons.materialsymbols.outlined.Package_2
import com.composables.icons.materialsymbols.outlined.Safety_check
import org.librelab.messaging.R
import org.librelab.messaging.data.MessageCategory
import org.librelab.messaging.data.SmsMessage
import org.librelab.messaging.data.SmsThreadItem
import org.librelab.messaging.ui.theme.avatarColorFor
import org.librelab.messaging.util.formatRelativeTime

/**
 * D. Conversation list item — 48dp circular avatar, sender + latest body,
 * time + unread count badge. Height ~72dp. One row per sender (thread).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    thread: SmsThreadItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    selected: Boolean = false
) {
    val message = thread.message
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Icon(
                imageVector = MaterialSymbols.Outlined.Check_circle,
                contentDescription = stringResource(R.string.action_selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
        }
        Avatar(message)
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = message.sender,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (message.imageUris.isNotEmpty()) {
                    stringResource(R.string.image_message, message.body.trim()).trimEnd()
                } else {
                    message.body
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = formatRelativeTime(context, message.date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(6.dp))
            if (thread.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (thread.unreadCount > 99) "99+" else thread.unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

/** 48dp circular avatar: deterministic palette color derived from the
 * contact name (or the address when unnamed), so every sender keeps a
 * stable, distinct color (see theme/Color.kt). */
@Composable
fun Avatar(message: SmsMessage, modifier: Modifier = Modifier) {
    val avatarColor = avatarColorFor(message.contactName ?: message.address)
    // Code messages adapt like the smart card: verified_user for 验证码,
    // package (box) for 取件码; other categories keep their own icon.
    val icon: ImageVector = if (message.category == MessageCategory.CODE) {
        if (message.isPickupCode) MaterialSymbols.Outlined.Package_2 else MaterialSymbols.Outlined.Safety_check
    } else {
        message.category.icon
    }
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(avatarColor.container),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = avatarColor.content,
            modifier = Modifier.size(22.dp)
        )
    }
}
