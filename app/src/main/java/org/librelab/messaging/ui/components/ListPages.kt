package org.librelab.messaging.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.librelab.messaging.data.SmsMessage
import org.librelab.messaging.data.SmsThreadItem
import org.librelab.messaging.ui.CodeCardRow
import org.librelab.messaging.ui.MessageItem

/** Shared centered empty-state text for the filter pages. */
@Composable
fun EmptyBox(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Conversation-list page skeleton: one row per thread (avatar + sender +
 * preview + time + unread badge), empty state, multi-select support.
 * Optional [header] item renders above the list (the smart banner on 全部).
 */
@Composable
fun ThreadListPage(
    threads: List<SmsThreadItem>,
    emptyText: String,
    onOpenThread: (SmsThreadItem) -> Unit,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    onToggleSelect: (SmsThreadItem) -> Unit,
    onLongPress: (SmsThreadItem) -> Unit,
    header: (@Composable () -> Unit)? = null
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        header?.let { item { it() } }
        if (threads.isEmpty()) {
            item { EmptyBox(emptyText) }
        } else {
            // Key on threadId + address: groupThreads falls back to
            // address.hashCode() for threadId=0 rows, so the raw threadId
            // alone can collide across senders (LazyColumn would crash on
            // duplicate keys).
            items(threads, key = { "${it.message.threadId}:${it.message.address}" }) { thread ->
                MessageItem(
                    thread = thread,
                    onClick = {
                        if (selectionMode) onToggleSelect(thread) else onOpenThread(thread)
                    },
                    onLongClick = { onLongPress(thread) },
                    selected = thread.message.threadId in selectedIds
                )
            }
        }
    }
}

/**
 * Code/express-entry list page skeleton: one card per code (multi-parcel
 * messages are split by [org.librelab.messaging.data.codeEntries]),
 * empty state.
 */
@Composable
fun CodeListPage(
    entries: List<SmsMessage>,
    emptyText: String,
    onCopy: (String) -> Unit,
    onOpenOriginal: (SmsMessage) -> Unit
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (entries.isEmpty()) {
            item { EmptyBox(emptyText) }
        } else {
            items(entries, key = { "${it.key}_${it.code}" }) { msg ->
                CodeCardRow(
                    codeMsg = msg,
                    onCopy = onCopy,
                    onOpenOriginal = onOpenOriginal
                )
            }
        }
    }
}
