package org.librelab.messaging.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Archive
import com.composables.icons.materialsymbols.outlined.Box
import com.composables.icons.materialsymbols.outlined.Campaign
import com.composables.icons.materialsymbols.outlined.Chat_bubble
import com.composables.icons.materialsymbols.outlined.Shield
import org.librelab.messaging.data.SmsFilter

/**
 * Standard Material 3 [TabRow] + [Tab] for the SMS category filter.
 *
 * [selectedTabIndex] is the current pager page — driven by [pagerState.currentPage],
 * which is kept in sync with [state.filter][org.librelab.messaging.data.UiState.filter]
 * by [SmsListContent]. Tapping a tab applies the real filter via [onTabSelected].
 */
@Composable
fun FilterChipRow(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val filters = SmsFilter.entries.toList()

    TabRow(
        selectedTabIndex = selectedTabIndex,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        indicator = { tabPositions ->
            TabRowDefaults.SecondaryIndicator(
                modifier = Modifier
                    .tabIndicatorOffset(tabPositions[selectedTabIndex])
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }
    ) {
        filters.forEachIndexed { index, filter ->
            val selected = selectedTabIndex == index
            Tab(
                selected = selected,
                onClick = { onTabSelected(index) },
                modifier = Modifier.height(48.dp),
                content = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = iconFor(filter),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (selected) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(filter.labelRes),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
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
