package com.freetv.live.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.freetv.live.data.Programme
import kotlinx.coroutines.delay

/** Current time, refreshed every 30 seconds so now/next labels roll over on their own. */
@Composable
fun rememberNow(): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now.longValue = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun formatTime(ms: Long): String {
    val context = LocalContext.current
    return remember(ms) { DateFormat.getTimeFormat(context).format(ms) }
}

fun progressOf(p: Programme, now: Long): Float =
    ((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)

/** Side panel with every channel in the current list and what's on now. */
@Composable
fun ChannelListOverlay(vm: TvViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val queue by vm.queue.collectAsState()
    val playing by vm.playing.collectAsState()
    val guide by vm.guide.collectAsState()
    val now by rememberNow()
    val current = playing ?: 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    val focusCurrent = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusCurrent.requestFocus() } }

    Surface(modifier.fillMaxHeight().widthIn(max = 460.dp), color = Color(0xF20E0E14)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Channels", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
            }
            LazyColumn(state = listState) {
                itemsIndexed(queue, key = { _, c -> c.url }) { i, c ->
                    val interaction = remember { MutableInteractionSource() }
                    val focused by interaction.collectIsFocusedAsState()
                    val (nowShow, _) = guide.nowAndNext(c, now)
                    Surface(
                        onClick = { vm.playAt(i); onClose() },
                        interactionSource = interaction,
                        shape = RoundedCornerShape(10.dp),
                        color = when {
                            focused -> MaterialTheme.colorScheme.surfaceVariant
                            i == current -> Accent.copy(alpha = 0.25f)
                            else -> Color.Transparent
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 1.dp)
                            .then(if (focused) Modifier.border(2.dp, Accent, RoundedCornerShape(10.dp)) else Modifier)
                            .then(if (i == current) Modifier.focusRequester(focusCurrent) else Modifier),
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", Modifier.width(44.dp), color = if (i == current) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
                            ChannelLogo(c, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                if (nowShow != null) {
                                    Text(nowShow.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(3.dp))
                                    LinearProgressIndicator(
                                        progress = { progressOf(nowShow, now) },
                                        modifier = Modifier.fillMaxWidth().height(3.dp),
                                        color = Accent,
                                        trackColor = Color(0x33FFFFFF),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Now: Title · until 9:30" / "Next: Title at 9:30" lines for the info bar. */
@Composable
fun NowNextLines(nowShow: Programme?, next: Programme?, compact: Boolean) {
    if (nowShow == null && next == null) return
    Box(Modifier.padding(top = 4.dp)) {
        Column {
            nowShow?.let {
                Text(
                    "Now: ${it.title} · until ${formatTime(it.stop)}",
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                )
            }
            next?.let {
                Text(
                    "Next: ${it.title} at ${formatTime(it.start)}",
                    color = Color(0xFFBBBBCC), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
