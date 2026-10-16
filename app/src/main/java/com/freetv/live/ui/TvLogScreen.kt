package com.freetv.live.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shows the Roku's debug console output: the TV app's log, compile errors and crash details. */
@Composable
fun TvLogScreen(vm: TvViewModel, onBack: () -> Unit) {
    val log by vm.tvLog.collectAsState()
    val context = LocalContext.current
    val scroll = rememberScrollState()
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { vm.captureTvLog(restart = false) }
    LaunchedEffect(log.length) { scroll.scrollTo(scroll.maxValue) }

    NightSurface {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column {
                    Text("TV log", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "What the Live TV app on the Roku reports. If it's stuck, tap Restart & capture, " +
                            "wait for it to stop, then Copy and send it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.captureTvLog(restart = true) }) { Text("Restart & capture") }
                OutlinedButton(onClick = vm::tvDebugInfo) { Text("Crash details") }
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("TV log", log))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
                OutlinedButton(onClick = vm::clearTvLog) { Text("Clear") }
            }
            Surface(
                Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = Night.copy(alpha = 0.8f),
            ) {
                Text(
                    log.ifEmpty { "Connecting…" },
                    Modifier.verticalScroll(scroll).padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Cyan,
                )
            }
        }
    }
}
