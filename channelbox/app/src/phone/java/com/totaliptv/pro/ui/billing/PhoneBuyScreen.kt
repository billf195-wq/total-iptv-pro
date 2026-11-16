package com.totaliptv.pro.ui.billing

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.totaliptv.pro.billing.LicenseController
import com.totaliptv.pro.billing.LicenseStrings
import com.totaliptv.pro.ui.theme.OnCinema
import com.totaliptv.pro.ui.theme.OnCinemaMuted
import com.totaliptv.pro.ui.theme.tipScreenBrush
import kotlinx.coroutines.launch

@Composable
fun PhoneBuyScreen(license: LicenseController) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val price by license.price.collectAsState()
    val message by license.message.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tipScreenBrush())
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            LicenseStrings.BUY_SCREEN_TITLE,
            color = OnCinema,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(12.dp))
        Text(
            LicenseStrings.BUY_SCREEN_BODY,
            color = OnCinemaMuted,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (activity != null) {
                    scope.launch { license.buy(activity) }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(LicenseStrings.buyLabel(price))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { scope.launch { license.restore() } },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(LicenseStrings.RESTORE)
        }
        if (!message.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(message!!, color = OnCinemaMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
