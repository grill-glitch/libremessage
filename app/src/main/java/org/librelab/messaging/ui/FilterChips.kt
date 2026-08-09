package org.librelab.messaging.ui

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Archive
import com.composables.icons.materialsymbols.outlined.Box
import com.composables.icons.materialsymbols.outlined.Campaign
import com.composables.icons.materialsymbols.outlined.Chat_bubble
import com.composables.icons.materialsymbols.outlined.Shield

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.librelab.messaging.data.SmsFilter

/**
 * C. Filter chips row — [全部] selected with primary pill, the rest
 * surfaceVariant pills with outline.
 *
 * A sliding indicator bar sits under the chips and follows the pager:
 * [page] is the settled page and [pageOffset] the drag fraction in [-1,1].
 * While the user swipes, the bar translates from the current chip to the
 * neighbour (and its width interpolates), so the highlight moves with the
 * finger instead of jumping.
 */
@Composable
fun FilterChipRow(
    onSelect: (SmsFilter) -> Unit,
    modifier: Modifier = Modifier,
    page: Int = 0,
    pageOffset: Float = 0f
) {
    val filters = remember { SmsFilter.entries.toList() }
    val chipX = remember { mutableStateMapOf<SmsFilter, Int>() } // start x in row coords
    val chipW = remember { mutableStateMapOf<SmsFilter, Int>() }
    var rowRootX by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    Box(modifier = modifier) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.onGloballyPositioned { coords ->
                rowRootX = coords.positionInRoot().x.toInt()
            }
        ) {
            items(filters) { filter ->
                FilterChip(
                    selected = false,
                    onClick = { onSelect(filter) },
                    label = { Text(stringResource(filter.labelRes)) },
                    leadingIcon = {
                        Icon(
                            imageVector = iconFor(filter),
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        selectedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    border = null,
                    modifier = Modifier.onGloballyPositioned { coords ->
                        chipX[filter] = (coords.positionInRoot().x - rowRootX).toInt()
                        chipW[filter] = coords.size.width
                    }
                )
            }
        }

        // Sliding indicator: translates between the settled chip and its
        // neighbour according to the drag offset, width interpolated too.
        val clampPage = { p: Int -> p.coerceIn(0, filters.size - 1) }
        val from = filters[clampPage(page)]
        val toFilter = when {
            pageOffset > 0f && page + 1 < filters.size -> filters[page + 1]
            pageOffset < 0f && page - 1 >= 0 -> filters[page - 1]
            else -> from
        }
        val x0 = chipX[from] ?: return@Box
        val w0 = chipW[from] ?: return@Box
        val t = kotlin.math.abs(pageOffset).coerceIn(0f, 1f)
        val x1 = chipX[toFilter] ?: x0
        val w1 = chipW[toFilter] ?: w0
        val barX = (x0 + (x1 - x0) * t).toInt()
        val barW = with(density) { (w0 + (w1 - w0) * t).toInt().toDp() }
        val barHeight = 3.dp

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset { IntOffset(barX, 0) }
                .size(width = barW, height = barHeight)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        ) { }
    }
}

private fun iconFor(filter: SmsFilter): ImageVector = when (filter) {
    SmsFilter.ALL -> MaterialSymbols.Outlined.Chat_bubble
    SmsFilter.CODE -> MaterialSymbols.Outlined.Shield
    SmsFilter.PACKAGE -> MaterialSymbols.Outlined.Box
    SmsFilter.AD -> MaterialSymbols.Outlined.Campaign
    SmsFilter.ARCHIVED -> MaterialSymbols.Outlined.Archive
}
