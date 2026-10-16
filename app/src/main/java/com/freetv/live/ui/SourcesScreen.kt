package com.freetv.live.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun SourcesScreen(vm: TvViewModel, onBack: () -> Unit) {
    val sources by vm.sources.collectAsState()
    val disabled by vm.disabledSources.collectAsState()
    val hideFailed by vm.hideFailed.collectAsState()
    val failed by vm.failed.collectAsState()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    BackHandler(onBack = onBack)

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Channel sources", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Text(
                "Turn lists on or off, or add any M3U playlist link you're allowed to use, like your TV provider's IPTV link.",
                Modifier.padding(start = 12.dp, bottom = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Hide channels that aren't working", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Channels that failed to play are hidden for 24 hours, then tried again. " +
                                    "Favorites always stay visible. ${failed.size} hidden now.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = hideFailed, onCheckedChange = vm::setHideFailed)
                    }
                    if (failed.isNotEmpty()) {
                        TextButton(onClick = vm::clearFailures) { Text("Show them again now") }
                    }
                }
            }
        }
        items(sources, key = { it.id }) { src ->
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(src.name, style = MaterialTheme.typography.titleMedium)
                        Text(src.url, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    if (!src.builtIn) {
                        IconButton(onClick = { vm.removeSource(src.id) }) { Icon(Icons.Default.Delete, "Remove") }
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = src.id !in disabled, onCheckedChange = { vm.setSourceEnabled(src.id, it) })
                }
            }
        }
        item {
            Column(
                Modifier.widthIn(max = 640.dp).padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Add a playlist", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    url, { url = it }, Modifier.fillMaxWidth(),
                    label = { Text("M3U / M3U8 link") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Button(
                    onClick = { vm.addSource(name, url); name = ""; url = "" },
                    enabled = url.startsWith("http://") || url.startsWith("https://"),
                ) { Text("Add") }
            }
        }
    }
}
