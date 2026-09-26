package com.totaliptv.pro.ui.components

import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text

/**
 * True on Android TV / Leanback. Phone builds remove the leanback feature and are not television.
 */
@Composable
fun isTelevisionUi(): Boolean {
    val context = LocalContext.current
    val television = (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    val leanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    return television || leanback
}

/**
 * TV search box that does not open the keyboard when the D-pad lands on it.
 *
 * Idle: a normal focusable row. Up/Down/Left/Right move to the next control.
 * OK/Select enters edit mode and shows the keyboard.
 * While editing, D-pad Down, Back, and IME Done hide the keyboard and move to [downFocus]
 * (or whatever [onExitEdit] focuses). Returns true from [onExitEdit] if that callback
 * already moved focus.
 */
@Composable
fun DpadSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search…",
    textStyle: TextStyle = TextStyle.Default,
    placeholderColor: Color = Color(0xFF9E9E9E),
    cursorColor: Color = Color(0xFFFFB300),
    backgroundColor: Color = Color(0xFF141414),
    focusedBorderColor: Color = Color(0xFFFFB300),
    idleBorderColor: Color = Color.Transparent,
    shape: Shape = RoundedCornerShape(8.dp),
    contentPadding: PaddingValues = PaddingValues(12.dp),
    focusRequester: FocusRequester? = null,
    downFocus: FocusRequester? = null,
    onEditingChange: (Boolean) -> Unit = {},
    onExitEdit: (toNext: Boolean) -> Boolean = { false }
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val idleFocus = focusRequester ?: remember { FocusRequester() }
    val editFocus = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var idleFocused by remember { mutableStateOf(false) }
    // null = not leaving edit. true = move to the next row.
    var exitToNext by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(editing) {
        onEditingChange(editing)
    }

    fun beginEdit() {
        exitToNext = null
        editing = true
    }

    fun endEdit(toNext: Boolean) {
        if (!editing) return
        keyboard?.hide()
        exitToNext = toNext
        editing = false
    }

    LaunchedEffect(editing, exitToNext) {
        if (editing) {
            runCatching { editFocus.requestFocus() }
            keyboard?.show()
            return@LaunchedEffect
        }
        val toNext = exitToNext ?: return@LaunchedEffect
        exitToNext = null
        val moved = runCatching { onExitEdit(toNext) }.getOrDefault(false)
        if (moved) return@LaunchedEffect
        if (toNext && downFocus != null) {
            runCatching { downFocus.requestFocus() }
        } else {
            runCatching { idleFocus.requestFocus() }
        }
    }

    val borderColor = when {
        editing || idleFocused -> focusedBorderColor
        else -> idleBorderColor
    }

    if (!editing) {
        Box(
            modifier
                .fillMaxWidth()
                .focusRequester(idleFocus)
                .onFocusChanged { idleFocused = it.isFocused }
                .focusProperties {
                    if (downFocus != null) down = downFocus
                }
                .background(backgroundColor, shape)
                .border(2.dp, borderColor, shape)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            beginEdit()
                            true
                        }
                        else -> false
                    }
                }
                .focusable()
                .clickable { beginEdit() }
                .padding(contentPadding),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = value.ifEmpty { placeholder },
                style = textStyle,
                color = if (value.isEmpty()) placeholderColor else textStyle.color,
                maxLines = 1
            )
        }
    } else {
        BackHandler { endEdit(toNext = true) }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(cursorColor),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { endEdit(toNext = true) },
                onSearch = { endEdit(toNext = true) },
                onGo = { endEdit(toNext = true) }
            ),
            modifier = modifier
                .fillMaxWidth()
                .focusRequester(editFocus)
                .focusProperties {
                    if (downFocus != null) down = downFocus
                }
                .background(backgroundColor, shape)
                .border(2.dp, focusedBorderColor, shape)
                .padding(contentPadding)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown, Key.Back, Key.Escape -> {
                            endEdit(toNext = true)
                            true
                        }
                        else -> false
                    }
                },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = textStyle, color = placeholderColor, maxLines = 1)
                    }
                    inner()
                }
            }
        )
    }
}
