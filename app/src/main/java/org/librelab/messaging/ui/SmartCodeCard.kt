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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.librelab.messaging.data.SmsMessage
import org.librelab.messaging.data.SmsParser
import org.librelab.messaging.util.formatRelativeTime

/**
 * B. Smart Verification Card — pinned at the top of the list, shows the
 * newest real verification code extracted from the inbox.
 */
@Composable
fun SmartCodeCard(
    message: SmsMessage?,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier
) {
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
                    contentDescription = message?.codeLabel ?: "验证码",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = message?.merchantName ?: "验证码",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = message?.codeLabel ?: "验证码",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = MaterialSymbols.Outlined.Chevron_right,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

            // Footer row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message?.body ?: "暂无验证码短信",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = message?.let { formatRelativeTime(it.date) } ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
