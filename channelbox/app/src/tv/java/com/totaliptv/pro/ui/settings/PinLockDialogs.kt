package com.totaliptv.pro.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.totaliptv.pro.data.lock.ParentalGate
import com.totaliptv.pro.data.lock.PinFlow
import com.totaliptv.pro.data.lock.PinHash
import com.totaliptv.pro.data.lock.PinMode
import com.totaliptv.pro.data.lock.PinPadView
import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.ui.home.LongPressReleaseGate
import com.totaliptv.pro.ui.home.longPressReleaseGate
import com.totaliptv.pro.ui.theme.CinemaBg
import com.totaliptv.pro.ui.theme.OnCinema
import com.totaliptv.pro.ui.theme.OnCinemaMuted

/** Shows [dialog] (if any). [onClose] runs with the next dialog (or null) when one finishes. */
@Composable
fun PinLockDialogHost(
    dialog: PinLockDialog?,
    repository: CatalogRepository,
    onClose: (PinLockDialog?) -> Unit
) {
    val context = LocalContext.current
    when (dialog) {
        null -> Unit
        is PinLockDialog.Pad -> PinPadDialog(
            mode = dialog.mode,
            onDone = {
                PinLockEntry.doneMessage(dialog.mode)?.let {
                    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                }
                onClose(if (dialog.mode == PinMode.PICK_CATEGORIES) PinLockDialog.Categories else null)
            },
            onDismiss = { onClose(null) }
        )
        PinLockDialog.Forgot -> ForgotPinDialog(
            onConfirm = { onClose(PinLockDialog.Pad(PinMode.RESET)) },
            onDismiss = { onClose(null) }
        )
        PinLockDialog.Categories -> LockedCategoriesDialog(repository = repository, onDismiss = { onClose(null) })
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

/** D-pad number pad. Remote number keys work too. The 4th digit submits. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PinPadDialog(mode: PinMode, onDone: () -> Unit, onDismiss: () -> Unit) {
    val flow = remember(mode) { PinFlow(mode) }
    var view by remember(mode) { mutableStateOf(PinPadView.of(flow)) }
    val gate = remember(mode) { LongPressReleaseGate() }
    val firstFocus = remember(mode) { FocusRequester() }
    fun after(changed: Boolean) {
        view = PinPadView.of(flow)
        if (changed && flow.step == PinFlow.Step.DONE) onDone()
    }
    fun press(d: Char) {
        flow.digit(d)
        after(flow.digits.length == PinHash.PIN_LENGTH && flow.submit())
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .longPressReleaseGate(gate)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val d = ev.key.digitOrNull()
                    when {
                        d != null -> { press(d); true }
                        ev.key == Key.Backspace || ev.key == Key.Delete -> { flow.backspace(); after(false); true }
                        else -> false
                    }
                }
                .width(420.dp)
                .background(CinemaBg, RoundedCornerShape(14.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                view.title,
                color = OnCinema,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(view.prompt, color = OnCinemaMuted, style = MaterialTheme.typography.bodySmall)
            Text(
                view.dots,
                color = OnCinema,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            view.error?.let { Text(it, color = OnCinema, style = MaterialTheme.typography.bodySmall) }
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { label ->
                        Button(
                            onClick = {
                                if (!gate.allowsClick()) return@Button
                                when (label) {
                                    "⌫" -> { flow.backspace(); after(false) }
                                    "OK" -> after(flow.submit())
                                    else -> press(label[0])
                                }
                            },
                            modifier = Modifier
                                .width(96.dp)
                                .then(if (label == "1") Modifier.focusRequester(firstFocus) else Modifier)
                        ) {
                            Text(label, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }
            Button(onClick = { if (gate.allowsClick()) onDismiss() }) { Text("Cancel") }
        }
        LaunchedEffect(mode) {
            kotlinx.coroutines.delay(60)
            runCatching { firstFocus.requestFocus() }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ForgotPinDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    val gate = remember { LongPressReleaseGate() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .longPressReleaseGate(gate)
                .width(460.dp)
                .background(CinemaBg, RoundedCornerShape(14.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                PinLockEntry.FORGOT_TITLE,
                color = OnCinema,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(PinLockEntry.FORGOT_BODY, color = OnCinemaMuted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Button(onClick = { if (gate.allowsClick()) onConfirm() }) { Text(PinLockEntry.FORGOT_CONFIRM) }
            Button(
                onClick = { if (gate.allowsClick()) onDismiss() },
                modifier = Modifier.focusRequester(cancelFocus)
            ) { Text("Cancel") }
        }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(60)
            runCatching { cancelFocus.requestFocus() }
        }
    }
}

/** Live / Movies / Series tabs; OK on a category locks or shows it. Opened only while unlocked. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LockedCategoriesDialog(repository: CatalogRepository, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(ContentKind.LIVE) }
    val revision by ParentalGate.revision.collectAsState()
    val categories = remember(kind) { repository.allCategoriesForLock(kind) }
    val tabFocus = remember { FocusRequester() }
    val gate = remember { LongPressReleaseGate() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .longPressReleaseGate(gate)
                .width(560.dp)
                .background(CinemaBg, RoundedCornerShape(14.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                PinLockEntry.PICKER_TITLE,
                color = OnCinema,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(PinLockEntry.PICKER_HINT, color = OnCinemaMuted, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ContentKind.LIVE to "Live", ContentKind.VOD to "Movies", ContentKind.SERIES to "Series").forEach { (k, label) ->
                    Button(
                        onClick = { if (gate.allowsClick()) kind = k },
                        modifier = if (k == ContentKind.LIVE) Modifier.focusRequester(tabFocus) else Modifier
                    ) { Text(if (k == kind) "• $label" else label) }
                }
            }
            if (categories.isEmpty()) {
                Text("No categories loaded yet.", color = OnCinemaMuted, style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(
                modifier = Modifier.heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(categories, key = { it.id }) { cat ->
                    val locked = remember(revision, cat.id) { ParentalGate.isCategoryLocked(cat) }
                    CategoryLockRow(cat, locked, onToggle = {
                        if (gate.allowsClick()) ParentalGate.toggleCategory(cat)
                    })
                }
            }
            Button(onClick = { if (gate.allowsClick()) onDismiss() }) { Text("Done") }
        }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(60)
            runCatching { tabFocus.requestFocus() }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CategoryLockRow(cat: Category, locked: Boolean, onToggle: () -> Unit) {
    Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(cat.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(if (locked) "Locked" else "Shown")
        }
    }
}
