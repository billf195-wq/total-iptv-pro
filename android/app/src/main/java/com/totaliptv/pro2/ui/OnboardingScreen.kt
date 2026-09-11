package com.totaliptv.pro2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.totaliptv.pro2.data.SavedPrefs

@Composable
fun OnboardingScreen(
    initial: SavedPrefs,
    busy: Boolean,
    error: String?,
    onConnect: (SavedPrefs) -> Unit
) {
    var base by remember { mutableStateOf(initial.xtreamBaseUrl) }
    var user by remember { mutableStateOf(initial.xtreamUsername) }
    var pass by remember { mutableStateOf(initial.xtreamPassword) }

    Column(
        Modifier
            .fillMaxSize()
            .background(TipBg)
            .padding(TipDimens.dp(48)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Total IPTV Pro 2", fontSize = TipDimens.HeadlineLargeSp, fontWeight = FontWeight.Bold, color = TipAmber)
        Text("Xtream Codes login", fontSize = TipDimens.TitleMediumSp, color = TipGoldMuted)
        Spacer(Modifier.height(TipDimens.dp(28)))
        Column(Modifier.widthIn(max = TipDimens.dp(520)).fillMaxWidth()) {
            Field("Server URL (http://host:port)", base, onValue = { base = it })
            Spacer(Modifier.height(TipDimens.dp(12)))
            Field("Username", user, onValue = { user = it })
            Spacer(Modifier.height(TipDimens.dp(12)))
            Field("Password", pass, onValue = { pass = it }, password = true)
            Spacer(Modifier.height(TipDimens.dp(20)))
            if (error != null) {
                Text(error, color = androidx.compose.ui.graphics.Color(0xFFFF8A80), fontSize = TipDimens.BodyMediumSp)
                Spacer(Modifier.height(TipDimens.dp(12)))
            }
            if (busy) {
                CircularProgressIndicator(color = TipAmber, modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                AmberButton("Connect", onClick = {
                    onConnect(
                        initial.copy(
                            xtreamBaseUrl = base.trim(),
                            xtreamUsername = user.trim(),
                            xtreamPassword = pass,
                            onboarded = false,
                            sourceType = "XTREAM"
                        )
                    )
                }, modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    password: Boolean = false
) {
    Text(label, color = TipGoldMuted, fontSize = TipDimens.sp(12))
    Spacer(Modifier.height(TipDimens.dp(4)))
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = TextStyle(color = TipGoldText, fontSize = TipDimens.TitleMediumSp),
        cursorBrush = SolidColor(TipAmber),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri
        ),
        modifier = Modifier
            .fillMaxWidth()
            .background(TipSurface, RoundedCornerShape(TipDimens.PosterCorner))
            .padding(TipDimens.dp(14)),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(label, color = TipGoldMuted.copy(alpha = 0.5f), fontSize = TipDimens.TitleMediumSp)
            }
            inner()
        }
    )
}
