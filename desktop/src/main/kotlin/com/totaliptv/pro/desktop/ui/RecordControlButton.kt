package com.totaliptv.pro.desktop.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.totaliptv.pro.desktop.dvr.DvrRecordUi
import com.totaliptv.pro.desktop.dvr.RecordingEntry

@Composable
fun RecordControlButton(
    active: RecordingEntry?,
    itemId: String?,
    streamUrl: String?,
    onClick: () -> Unit,
    idleLabel: String = DvrRecordUi.IDLE_LABEL,
    outlined: Boolean = true,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val look = DvrRecordUi.appearance(active, itemId, streamUrl, idleLabel)
    val enabled = !streamUrl.isNullOrBlank()
    if (look.selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = TipRecordActive,
                contentColor = TipOnRecordActive,
                disabledContainerColor = TipRecordActive.copy(alpha = 0.7f),
                disabledContentColor = TipOnRecordActive
            ),
            modifier = modifier.then(if (compact) Modifier else Modifier.height(40.dp))
        ) {
            Icon(
                Icons.Default.FiberManualRecord,
                contentDescription = null,
                tint = TipOnRecordActive,
                modifier = Modifier.size(if (compact) 16.dp else 18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(look.label, color = TipOnRecordActive, fontWeight = FontWeight.Bold)
        }
    } else if (compact) {
        TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            Icon(
                Icons.Default.FiberManualRecord,
                contentDescription = null,
                tint = TipAccent,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(look.label, color = TipOnBg, fontWeight = FontWeight.SemiBold)
        }
    } else if (outlined) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TipOnBg),
            border = androidx.compose.foundation.BorderStroke(1.dp, TipBlue),
            modifier = modifier
        ) {
            Icon(
                Icons.Default.FiberManualRecord,
                contentDescription = null,
                tint = TipAccent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(look.label, color = TipOnBg, fontWeight = FontWeight.SemiBold)
        }
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = TipSurfaceAlt,
                contentColor = TipOnBg
            ),
            modifier = modifier
        ) {
            Icon(
                Icons.Default.FiberManualRecord,
                contentDescription = null,
                tint = TipAccent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(look.label, color = TipOnBg, fontWeight = FontWeight.SemiBold)
        }
    }
}
