package com.totaliptv.pro.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.totaliptv.pro.ui.help.HowToGuide
import com.totaliptv.pro.ui.theme.FocusBorder
import com.totaliptv.pro.ui.theme.LocalTipColors

/**
 * Settings → How to Use (TV). Each section is focusable, so Up / Down on the remote
 * moves through the guide and the list scrolls with it.
 */
@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun HowToUseScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val sections = remember { HowToGuide.sections(tv = true) }
    val backFocus = remember { FocusRequester() }
    // Classic and Desktop use different Material themes; the shared Tip colors read well in both.
    val tip = LocalTipColors.current
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        Button(onClick = onBack, modifier = Modifier.focusRequester(backFocus)) { Text("Back") }
        Text(
            "How to Use",
            style = MaterialTheme.typography.headlineMedium,
            color = tip.onCinema,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
        )
        Text(
            "Use Up and Down on the remote to read. Press Back to return to Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = tip.onCinemaMuted,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(sections, key = { it.title }) { section ->
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(10.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused }
                        // Down on the last section stays put (Desktop has side-menu buttons below).
                        .focusProperties { if (section === sections.last()) down = FocusRequester.Cancel }
                        .focusable()
                        .border(if (focused) 2.dp else 1.dp, if (focused) FocusBorder else tip.onCinemaMuted.copy(alpha = 0.4f), shape)
                        .background(if (focused) tip.onCinema.copy(alpha = 0.10f) else Color.Transparent, shape)
                        .padding(16.dp)
                ) {
                    Text(
                        section.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = tip.onCinema
                    )
                    Text(
                        section.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = tip.onCinema.copy(alpha = 0.9f),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(60)
        runCatching { backFocus.requestFocus() }
    }
}
