package com.freetv.live.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The phone as a remote for the Live TV app on the Roku. */
@Composable
fun RemoteScreen(vm: TvViewModel, onBack: () -> Unit, onOpenLog: () -> Unit) {
    val target by vm.castTarget.collectAsState()
    val message by vm.castMessage.collectAsState()
    val receiverMissing by vm.receiverMissing.collectAsState()
    var showCast by remember { mutableStateOf(false) }
    var number by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val haptics = LocalHapticFeedback.current
    fun tap(action: () -> Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        action()
    }

    BackHandler(onBack = onBack)
    if (showCast) CastDialog(vm, onDismiss = { showCast = false })

    NightSurface {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to channels") }
                Column(Modifier.weight(1f)) {
                    Text(target?.name ?: "Remote", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (target != null) "Connected" else "Not connected",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (target != null) Cyan else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (target != null) {
                    TextButton(onClick = onOpenLog) { Text("TV log", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(onClick = { tap(vm::openOnTv) }) { Text("Open on TV", color = Cyan) }
                } else {
                    TextButton(onClick = { showCast = true }) {
                        Icon(Icons.Default.Cast, null, tint = Cyan)
                        Spacer(Modifier.width(6.dp))
                        Text("Connect", color = Cyan)
                    }
                }
            }

            if (target == null) {
                Text(
                    "Connect to your Roku to use the phone as its remote.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (receiverMissing) ReceiverSetup(vm, Modifier.widthIn(max = 520.dp))

            // Back / Home / Guide / Favorite
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RoundKey(Icons.AutoMirrored.Filled.ArrowBack, "Back") { tap { vm.sendKey("Back") } }
                RoundKey(Icons.Default.Home, "Home") { tap { vm.sendKey("Home") } }
                RoundKey(Icons.Default.GridView, "Guide", highlight = true) { tap { vm.sendCommand("command" to "guide") } }
                RoundKey(Icons.Default.Star, "Favorite") { tap { vm.sendKey("Info") } }
            }

            DPad(
                onUp = { tap { vm.sendKey("Up") } },
                onDown = { tap { vm.sendKey("Down") } },
                onLeft = { tap { vm.sendKey("Left") } },
                onRight = { tap { vm.sendKey("Right") } },
                onOk = { tap { vm.sendKey("Select") } },
            )

            // Channel and volume rockers, play/pause and mute.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Rocker("CH", onUp = { tap { vm.sendCommand("command" to "channel_up") } }, onDown = { tap { vm.sendCommand("command" to "channel_down") } })
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    RoundKey(Icons.Default.PlayArrow, "Play / pause") { tap { vm.sendKey("Play") } }
                    RoundKey(Icons.AutoMirrored.Filled.VolumeOff, "Mute") { tap { vm.sendKey("VolumeMute") } }
                }
                Rocker("VOL", onUp = { tap { vm.sendKey("VolumeUp") } }, onDown = { tap { vm.sendKey("VolumeDown") } }, plusMinus = true)
            }

            // Number pad: tunes the TV's channel numbers.
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        number.ifEmpty { "CH —" },
                        style = TextStyle(fontFamily = Bungee, fontSize = 30.sp, brush = NeonBrush),
                    )
                    Spacer(Modifier.height(10.dp))
                    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "GO"))
                    rows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                            row.forEach { k ->
                                NumberKey(k, accent = k == "GO") {
                                    tap {
                                        when (k) {
                                            "⌫" -> number = number.dropLast(1)
                                            "GO" -> if (number.isNotEmpty()) {
                                                vm.sendCommand("command" to "number", "n" to number)
                                                number = ""
                                            }
                                            else -> number = (number + k).take(4)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Search on the TV's guide.
            Row(Modifier.fillMaxWidth().widthIn(max = 520.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    query, { query = it }, Modifier.weight(1f),
                    placeholder = { Text("Search on TV") },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = Violet) },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.sendCommand("command" to "search", "q" to query) }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Violet),
                )
                Spacer(Modifier.width(10.dp))
                Button(onClick = { tap { vm.sendCommand("command" to "search", "q" to query) } }, enabled = query.isNotBlank()) {
                    Text("Search")
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RoundKey(icon: ImageVector, label: String, size: Dp = 60.dp, highlight: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (highlight) Violet.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(size).then(if (highlight) Modifier.border(2.dp, NeonBrush, CircleShape) else Modifier),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = Color.White) }
    }
}

@Composable
private fun DPad(onUp: () -> Unit, onDown: () -> Unit, onLeft: () -> Unit, onRight: () -> Unit, onOk: () -> Unit) {
    Box(
        Modifier
            .size(250.dp)
            .background(
                Brush.radialGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface)),
                CircleShape,
            )
            .border(2.dp, NeonBrush, CircleShape),
    ) {
        PadArrow(Icons.Default.KeyboardArrowUp, "Up", Modifier.align(Alignment.TopCenter), onUp)
        PadArrow(Icons.Default.KeyboardArrowDown, "Down", Modifier.align(Alignment.BottomCenter), onDown)
        PadArrow(Icons.Default.KeyboardArrowLeft, "Left", Modifier.align(Alignment.CenterStart), onLeft)
        PadArrow(Icons.Default.KeyboardArrowRight, "Right", Modifier.align(Alignment.CenterEnd), onRight)
        Surface(
            onClick = onOk,
            shape = CircleShape,
            color = Violet,
            modifier = Modifier.align(Alignment.Center).size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("OK", style = TextStyle(fontFamily = Bungee, fontSize = 26.sp, color = Night))
            }
        }
    }
}

@Composable
private fun PadArrow(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier.padding(6.dp).size(70.dp)) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(40.dp))
    }
}

@Composable
private fun Rocker(label: String, onUp: () -> Unit, onDown: () -> Unit, plusMinus: Boolean = false) {
    Surface(shape = RoundedCornerShape(40.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onUp, modifier = Modifier.size(64.dp)) {
                if (plusMinus) Text("+", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                else Icon(Icons.Default.KeyboardArrowUp, "$label up", tint = Color.White)
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = Cyan)
            IconButton(onClick = onDown, modifier = Modifier.size(64.dp)) {
                if (plusMinus) Text("–", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                else Icon(Icons.Default.KeyboardArrowDown, "$label down", tint = Color.White)
            }
        }
    }
}

@Composable
private fun NumberKey(label: String, accent: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (accent) Violet else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(64.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (label == "⌫") {
                Icon(Icons.Default.Backspace, "Delete", tint = Color.White)
            } else {
                Text(
                    label,
                    style = if (accent) TextStyle(fontFamily = Bungee, fontSize = 18.sp, color = Night)
                    else MaterialTheme.typography.titleLarge,
                    color = if (accent) Night else Color.White,
                )
            }
        }
    }
}
