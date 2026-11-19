package com.totaliptv.pro.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.totaliptv.pro.data.model.WatchProgress
import com.totaliptv.pro.ui.theme.CinemaBg
import com.totaliptv.pro.ui.theme.OnCinema

/**
 * TV long-press (hold OK) menu for a Continue watching title.
 * Each choice is a big focusable button so the D-pad can reach every one.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ContinueWatchingMenuDialog(
    entry: WatchProgress,
    actions: List<ContinueWatchingAction>,
    isFavorite: Boolean,
    onAction: (ContinueWatchingAction) -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocus = remember(entry.id) { FocusRequester() }
    // The hold that opened this menu is still in progress: ignore its release.
    val gate = remember(entry.id) { LongPressReleaseGate() }
    val safeFocus = ContinueWatchingAction.initialFocus(actions)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .longPressReleaseGate(gate)
                .width(420.dp)
                .background(CinemaBg, RoundedCornerShape(14.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                entry.title,
                color = OnCinema,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            actions.forEach { action ->
                val label = if (action == ContinueWatchingAction.TOGGLE_FAVORITE) {
                    if (isFavorite) "Remove from favorites" else "Add to favorites"
                } else {
                    action.label
                }
                Button(
                    onClick = { if (gate.allowsClick()) onAction(action) },
                    modifier = if (action == safeFocus) Modifier.focusRequester(firstFocus) else Modifier
                ) { Text(label) }
            }
            Button(
                onClick = { if (gate.allowsClick()) onDismiss() },
                modifier = if (safeFocus == null) Modifier.focusRequester(firstFocus) else Modifier
            ) { Text("Cancel") }
        }
        LaunchedEffect(entry.id) {
            kotlinx.coroutines.delay(60)
            runCatching { firstFocus.requestFocus() }
        }
    }
}
