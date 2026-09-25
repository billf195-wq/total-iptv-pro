package com.totaliptv.pro.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.totaliptv.pro.desktop.data.SavedPrefs
import com.totaliptv.pro.desktop.data.SourceType

@Composable
fun OnboardingScreen(
    initial: SavedPrefs,
    busy: Boolean,
    error: String?,
    onConnect: (SavedPrefs) -> Unit
) {
    var tab by remember { mutableStateOf(if (initial.sourceType == SourceType.M3U.name) 1 else 0) }
    var baseUrl by remember { mutableStateOf(initial.xtreamBaseUrl) }
    var user by remember { mutableStateOf(initial.xtreamUsername) }
    var pass by remember { mutableStateOf(initial.xtreamPassword) }
    var m3u by remember { mutableStateOf(initial.m3uUrl) }
    var showPass by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(TipBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("TOTAL IPTV PRO", style = MaterialTheme.typography.headlineLarge, color = TipBlue)
            Text("Desktop for Ubuntu", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Connect your Xtream Codes panel or an M3U playlist.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(24.dp))

            TabRow(
                selectedTabIndex = tab,
                containerColor = TipSurface,
                contentColor = TipBlue
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Xtream") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("M3U URL") })
            }

            Spacer(Modifier.height(20.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = TipSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (tab == 0) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Server URL") },
                            placeholder = { Text("http://host:port") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().trackTextInputFocus(),
                            colors = tipFieldColors()
                        )
                        OutlinedTextField(
                            value = user,
                            onValueChange = { user = it },
                            label = { Text("Username") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().trackTextInputFocus(),
                            colors = tipFieldColors()
                        )
                        OutlinedTextField(
                            value = pass,
                            onValueChange = { pass = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { showPass = !showPass }) {
                                    Text(if (showPass) "Hide" else "Show", color = TipMuted)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().trackTextInputFocus(),
                            colors = tipFieldColors()
                        )
                    } else {
                        OutlinedTextField(
                            value = m3u,
                            onValueChange = { m3u = it },
                            label = { Text("M3U playlist URL") },
                            placeholder = { Text("https://…/playlist.m3u") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().trackTextInputFocus(),
                            colors = tipFieldColors()
                        )
                    }

                    if (error != null) {
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }

                    Button(
                        onClick = {
                            val prefs = if (tab == 0) {
                                initial.copy(
                                    sourceType = SourceType.XTREAM.name,
                                    xtreamBaseUrl = baseUrl.trim(),
                                    xtreamUsername = user.trim(),
                                    xtreamPassword = pass,
                                    m3uUrl = m3u.trim(),
                                    onboarded = true
                                )
                            } else {
                                initial.copy(
                                    sourceType = SourceType.M3U.name,
                                    m3uUrl = m3u.trim(),
                                    xtreamBaseUrl = baseUrl.trim(),
                                    xtreamUsername = user.trim(),
                                    xtreamPassword = pass,
                                    onboarded = true
                                )
                            }
                            onConnect(prefs)
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TipBlue)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                Modifier.size(22.dp),
                                color = ColorWhite,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Connect", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private val ColorWhite = androidx.compose.ui.graphics.Color.White

@Composable
private fun tipFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = TipBlue,
    unfocusedBorderColor = TipSurfaceAlt,
    focusedContainerColor = TipSurfaceAlt,
    unfocusedContainerColor = TipSurfaceAlt,
    focusedLabelColor = TipBlue,
    unfocusedLabelColor = TipMuted,
    cursorColor = TipBlue,
    focusedTextColor = TipOnBg,
    unfocusedTextColor = TipOnBg,
    focusedPlaceholderColor = TipMuted,
    unfocusedPlaceholderColor = TipMuted
)
