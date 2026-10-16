package com.freetv.live.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.freetv.live.data.Channel
import com.freetv.live.data.Programme
import kotlinx.coroutines.delay

/**
 * "Now playing" card that slides in at the top right, like a YouTube info card: on every channel
 * change, and again whenever a new show starts on the channel.
 */
@Composable
fun NowPlayingCard(channel: Channel, number: Int, nowShow: Programme?, now: Long, modifier: Modifier = Modifier) {
    // Changes on a new channel or a new show; each change shows the card for a few seconds.
    val key = channel.url + "|" + (nowShow?.start ?: 0L)
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(key) {
        visible = false
        delay(600)
        visible = true
        delay(7_000)
        visible = false
    }

    AnimatedVisibility(
        visible,
        modifier = modifier,
        enter = slideInHorizontally(initialOffsetX = { it + 80 }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it + 80 }) + fadeOut(),
    ) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Night.copy(alpha = 0.92f))
                .border(2.dp, NeonBrush, RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Pink))
                Spacer(Modifier.width(7.dp))
                Text(
                    "NOW PLAYING · CH $number",
                    style = MaterialTheme.typography.labelSmall,
                    color = Cyan,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                nowShow?.title ?: channel.name,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (nowShow != null) "${formatTime(nowShow.start)} – ${formatTime(nowShow.stop)} · ${channel.name}"
                else "Live programming",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (nowShow != null) {
                Spacer(Modifier.height(10.dp))
                NeonProgress(progressOf(nowShow, now), Modifier.fillMaxWidth(), height = 4.dp)
            }
        }
    }
}
