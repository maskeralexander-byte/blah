package com.freetv.live.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Picks a Roku to cast to, or stops the current cast. */
@Composable
fun CastDialog(vm: TvViewModel, onDismiss: () -> Unit) {
    val rokus by vm.rokus.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val target by vm.castTarget.collectAsState()
    val message by vm.castMessage.collectAsState()
    var ip by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { if (target == null) vm.scanRokus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Cast, null) },
        title = { Text("Cast to Roku") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rokus.forEach { d ->
                    val active = target?.host == d.host
                    Surface(
                        onClick = { vm.castTo(d); onDismiss() },
                        shape = RoundedCornerShape(10.dp),
                        color = if (active) Accent else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (active) Icons.Default.CastConnected else Icons.Default.Cast, null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(d.name, style = MaterialTheme.typography.titleSmall)
                                Text(d.host, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (scanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Looking for Rokus on your Wi-Fi…")
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        ip, { ip = it }, Modifier.weight(1f),
                        label = { Text("Roku IP address") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { vm.addRokuByIp(ip) }, enabled = ip.isNotBlank() && !scanning) { Text("Connect") }
                }
            }
        },
        confirmButton = {
            if (target != null) {
                TextButton(onClick = { vm.stopCasting(); onDismiss() }) { Text("Stop casting") }
            } else {
                TextButton(onClick = vm::scanRokus, enabled = !scanning) { Text("Search again") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
