package com.totaliptv.pro.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.totaliptv.pro.ui.components.PhonePageHeader
import com.totaliptv.pro.ui.help.HowToGuide
import com.totaliptv.pro.ui.theme.OnCinema
import com.totaliptv.pro.ui.theme.OnCinemaMuted
import com.totaliptv.pro.ui.theme.tipScreenBrush

/** Settings → How to Use (phone). Plain scrolling text. */
@Composable
fun PhoneHowToScreen(
    onBack: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    BackHandler { onBack() }
    val sections = remember { HowToGuide.sections(tv = false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tipScreenBrush())
            .padding(contentPadding)
    ) {
        PhonePageHeader()
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            OutlinedButton(onClick = onBack) { Text("Back to Settings") }
            Spacer(Modifier.height(12.dp))
            Text("How to Use", style = MaterialTheme.typography.headlineSmall, color = OnCinema, fontWeight = FontWeight.Bold)
            sections.forEach { section ->
                Spacer(Modifier.height(18.dp))
                Text(section.title, style = MaterialTheme.typography.titleMedium, color = OnCinema, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(section.body, style = MaterialTheme.typography.bodyMedium, color = OnCinemaMuted)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
