package org.librelab.messaging.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Deselect
import com.composables.icons.materialsymbols.outlined.Select_all
import org.librelab.messaging.R

/**
 * Multi-select top-bar action group shared by the conversation list and the
 * conversation detail: the select-all toggle, page-specific [actions], and
 * an optional close button ([onClose] — the detail screen keeps its close
 * in the navigation icon instead, so it passes null).
 */
@Composable
fun MultiSelectActions(
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {}
) {
    IconButton(onClick = onToggleAll, modifier = modifier) {
        Icon(
            imageVector = if (allSelected) {
                MaterialSymbols.Outlined.Deselect
            } else {
                MaterialSymbols.Outlined.Select_all
            },
            contentDescription = stringResource(R.string.action_select_all),
            tint = tint
        )
    }
    actions()
    if (onClose != null) {
        IconButton(onClick = onClose, modifier = modifier) {
            Icon(
                imageVector = MaterialSymbols.Outlined.Close,
                contentDescription = stringResource(R.string.action_cancel_selection),
                tint = tint
            )
        }
    }
}
