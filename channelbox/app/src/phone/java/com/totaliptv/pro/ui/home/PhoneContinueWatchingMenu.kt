package com.totaliptv.pro.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.totaliptv.pro.data.model.WatchProgress

/** Phone long-tap menu for a Continue watching title. */
@Composable
fun PhoneContinueWatchingMenu(
    entry: WatchProgress,
    actions: List<ContinueWatchingAction>,
    isFavorite: Boolean,
    onAction: (ContinueWatchingAction) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                actions.forEach { action ->
                    val label = if (action == ContinueWatchingAction.TOGGLE_FAVORITE) {
                        if (isFavorite) "Remove from favorites" else "Add to favorites"
                    } else {
                        action.label
                    }
                    TextButton(
                        onClick = { onAction(action) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(label, modifier = Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
