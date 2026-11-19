package com.totaliptv.pro.ui.billing

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.totaliptv.pro.billing.LicenseController
import com.totaliptv.pro.billing.LicenseStrings
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvBuyScreen(license: LicenseController) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val price by license.price.collectAsState()
    val message by license.message.collectAsState()
    val buyFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { buyFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .padding(48.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            LicenseStrings.BUY_SCREEN_TITLE,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(16.dp))
        Text(
            LicenseStrings.BUY_SCREEN_BODY,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                if (activity != null) {
                    scope.launch { license.buy(activity) }
                }
            },
            modifier = Modifier.focusRequester(buyFocus)
        ) {
            Text(LicenseStrings.buyLabel(price))
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { scope.launch { license.restore() } }) {
            Text(LicenseStrings.RESTORE)
        }
        if (!message.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(
                message!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}
