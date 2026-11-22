package com.totaliptv.pro.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.totaliptv.pro.data.lock.ParentalGate
import com.totaliptv.pro.data.lock.PinFlow
import com.totaliptv.pro.data.lock.PinHash
import com.totaliptv.pro.data.lock.PinMode
import com.totaliptv.pro.data.lock.PinPadView
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.ui.home.LongPressReleaseGate
import com.totaliptv.pro.ui.home.longPressReleaseGate

/** Phone PIN lock dialogs (same flows as TV; Material dialogs like the rest of the phone app). */
@Composable
fun PhonePinLockDialogHost(
    dialog: PinLockDialog?,
    repository: CatalogRepository,
    onClose: (PinLockDialog?) -> Unit,
    onPinVerified: () -> Unit = {}
) {
    val context = LocalContext.current
    when (dialog) {
        null -> Unit
        is PinLockDialog.Pad -> PhonePinPadDialog(
            mode = dialog.mode,
            onDone = {
                PinLockEntry.doneMessage(dialog.mode)?.let {
                    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                }
                if (dialog.mode == PinMode.VERIFY) onPinVerified()
                onClose(if (dialog.mode == PinMode.PICK_CATEGORIES) PinLockDialog.Categories else null)
            },
            onDismiss = { onClose(null) }
        )
        PinLockDialog.Forgot -> AlertDialog(
            onDismissRequest = { onClose(null) },
            title = { Text(PinLockEntry.FORGOT_TITLE) },
            text = { Text(PinLockEntry.FORGOT_BODY) },
            confirmButton = {
                TextButton(onClick = { onClose(PinLockDialog.Pad(PinMode.RESET)) }) { Text(PinLockEntry.FORGOT_CONFIRM) }
            },
            dismissButton = { TextButton(onClick = { onClose(null) }) { Text("Cancel") } }
        )
        PinLockDialog.Categories -> PhoneLockedCategoriesDialog(repository, onDismiss = { onClose(null) })
    }
}

private fun Key.digitOrNull(): Char? = when (this) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}

@Composable
fun PhonePinPadDialog(mode: PinMode, onDone: () -> Unit, onDismiss: () -> Unit) {
    val flow = remember(mode) { PinFlow(mode) }
    var view by remember(mode) { mutableStateOf(PinPadView.of(flow)) }
    val gate = remember(mode) { LongPressReleaseGate() }
    fun after(changed: Boolean) {
        view = PinPadView.of(flow)
        if (changed && flow.step == PinFlow.Step.DONE) onDone()
    }
    fun press(d: Char) {
        flow.digit(d)
        after(flow.digits.length == PinHash.PIN_LENGTH && flow.submit())
    }
    AlertDialog(
        modifier = Modifier
            .longPressReleaseGate(gate)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val d = ev.key.digitOrNull()
                when {
                    d != null -> { press(d); true }
                    ev.key == Key.Backspace || ev.key == Key.Delete -> { flow.backspace(); after(false); true }
                    else -> false
                }
            },
        onDismissRequest = onDismiss,
        title = { Text(view.title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(view.prompt, style = MaterialTheme.typography.bodyMedium)
                Text(view.dots, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
                view.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK")).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        row.forEach { label ->
                            OutlinedButton(
                                onClick = {
                                    if (!gate.allowsClick()) return@OutlinedButton
                                    when (label) {
                                        "⌫" -> { flow.backspace(); after(false) }
                                        "OK" -> after(flow.submit())
                                        else -> press(label[0])
                                    }
                                },
                                modifier = Modifier.width(76.dp)
                            ) { Text(label, textAlign = TextAlign.Center) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { if (gate.allowsClick()) onDismiss() }) { Text("Cancel") } }
    )
}

@Composable
fun PhoneLockedCategoriesDialog(repository: CatalogRepository, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(ContentKind.LIVE) }
    val revision by ParentalGate.revision.collectAsState()
    val categories = remember(kind) { repository.allCategoriesForLock(kind) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(PinLockEntry.PICKER_TITLE) },
        text = {
            Column {
                Text(PinLockEntry.PICKER_HINT_TOUCH, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    listOf(ContentKind.LIVE to "Live", ContentKind.VOD to "Movies", ContentKind.SERIES to "Series").forEach { (k, label) ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(label) })
                    }
                }
                if (categories.isEmpty()) Text("No categories loaded yet.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(categories, key = { it.id }) { cat ->
                        val locked = remember(revision, cat.id) { ParentalGate.isCategoryLocked(cat) }
                        TextButton(onClick = { ParentalGate.toggleCategory(cat) }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(cat.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(if (locked) "Locked" else "Shown")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
