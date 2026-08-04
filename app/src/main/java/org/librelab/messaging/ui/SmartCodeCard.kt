package org.librelab.messaging.ui

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Package_2
import com.composables.icons.materialsymbols.outlined.Safety_check

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.librelab.messaging.data.SmsMessage
import org.librelab.messaging.data.SmsParser
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Absolute send time: today → HH:mm, older → MM-dd HH:mm. */
private fun formatSendTime(date: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = date }
    val now = Calendar.getInstance()
    val sameDay = cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val fmt = if (sameDay) SimpleDateFormat("HH:mm", Locale.getDefault())
    else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return fmt.format(Date(date))
}

/**
 * B. Smart Verification Card — pinned at the top of the list, shows the
 * newest real verification code extracted from the inbox.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartCodeCard(
    message: SmsMessage?,
    allCodes: List<SmsMessage> = emptyList(),
    onCopy: (String) -> Unit,
    onOpenOriginal: (SmsMessage) -> Unit = {},
    fallbackLabel: String = "验证码",
    emptyText: String = "暂无验证码短信",
    modifier: Modifier = Modifier
) {
    var showSheet by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            // Original soft background, no border.
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header row — icon adapts: verified_user for 验证码,
            // package (box) for 取件码; no highlight container.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (message?.codeLabel == "取件码") {
                        MaterialSymbols.Outlined.Package_2
                    } else {
                        MaterialSymbols.Outlined.Safety_check
                    },
                    contentDescription = message?.codeLabel ?: fallbackLabel,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = message?.merchantName ?: fallbackLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = message?.codeLabel ?: fallbackLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { showSheet = true },
                    enabled = allCodes.isNotEmpty()
                ) {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Chevron_right,
                        contentDescription = "查看全部验证码",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Core code row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = message?.code?.let(SmsParser::formatCode) ?: "— — —",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { message?.code?.let(onCopy) },
                    enabled = message?.hasCode == true
                ) {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Content_copy,
                        contentDescription = "复制验证码",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Body row
            Text(
                text = message?.body ?: emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            // Send time + open-original, parked at the card's bottom edge.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { message?.let(onOpenOriginal) },
                    enabled = message != null,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("原始短信", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = message?.let { formatSendTime(it.date) } ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "所有验证码与取件码 (${allCodes.size})",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(maxHeight = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(allCodes, key = { "${it.key}_${it.code}" }) { codeMsg ->
                        CodeCardRow(
                            codeMsg = codeMsg,
                            onCopy = onCopy,
                            onOpenOriginal = onOpenOriginal
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CodeCardRow(
    codeMsg: SmsMessage,
    onCopy: (String) -> Unit,
    onOpenOriginal: (SmsMessage) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 170.dp)
        ) {
            // Main content, bottom padding leaves room for the footer bar.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 52.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (codeMsg.codeLabel == "取件码") {
                        MaterialSymbols.Outlined.Package_2
                    } else {
                        MaterialSymbols.Outlined.Safety_check
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = codeMsg.merchantName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = codeMsg.code?.let(SmsParser::formatCode) ?: "",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = codeMsg.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Footer bar pinned to the card's bottom-right corner:
            // send time + 原始短信 + copy, always at the very bottom.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatSendTime(codeMsg.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.width(6.dp))
                TextButton(
                    onClick = { onOpenOriginal(codeMsg) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text("原始短信", style = MaterialTheme.typography.labelSmall)
                }
                IconButton(
                    onClick = { codeMsg.code?.let(onCopy) },
                    enabled = codeMsg.hasCode,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Content_copy,
                        contentDescription = "复制",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
