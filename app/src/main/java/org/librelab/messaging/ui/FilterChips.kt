package org.librelab.messaging.ui

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Archive
import com.composables.icons.materialsymbols.outlined.Box
import com.composables.icons.materialsymbols.outlined.Campaign
import com.composables.icons.materialsymbols.outlined.Chat_bubble
import com.composables.icons.materialsymbols.outlined.Shield

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.librelab.messaging.data.SmsFilter

/**
 * C. Filter chips row — [全部] selected with primary pill, the rest
 * surfaceVariant pills with outline.
 */
@Composable
fun FilterChipRow(
    selected: SmsFilter,
    onSelect: (SmsFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(SmsFilter.entries.toList()) { filter ->
            val isSelected = filter == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(filter) },
                label = { Text(filter.label) },
                leadingIcon = {
                    Icon(
                        imageVector = iconFor(filter),
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize)
                    )
                },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                ),
                border = null
            )
        }
    }
}

private fun iconFor(filter: SmsFilter): ImageVector = when (filter) {
    SmsFilter.ALL -> MaterialSymbols.Outlined.Chat_bubble
    SmsFilter.CODE -> MaterialSymbols.Outlined.Shield
    SmsFilter.PACKAGE -> MaterialSymbols.Outlined.Box
    SmsFilter.AD -> MaterialSymbols.Outlined.Campaign
    SmsFilter.ARCHIVED -> MaterialSymbols.Outlined.Archive
}
