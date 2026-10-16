package com.freetv.live.ui

import android.net.Uri
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.freetv.live.data.Channel
import kotlinx.coroutines.delay

private const val DEFAULT_UA =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: TvViewModel, onExit: () -> Unit) {
    val queue by vm.queue.collectAsState()
    val index by vm.playing.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val castTarget by vm.castTarget.collectAsState()
    val castMessage by vm.castMessage.collectAsState()
    var showCast by remember { mutableStateOf(false) }
    val i = index ?: return
    val channel = queue.getOrNull(i) ?: return

    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context)
            // Keep a deeper buffer than the default so short network stalls don't stop playback.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(20_000, 60_000, 2_500, 5_000)
                    .build()
            )
            .build()
            .apply { playWhenReady = true }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var overlayTick by remember { mutableIntStateOf(0) }
    var overlayVisible by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf("") }
    // Streams without a recognisable extension are tried as HLS first, then as a plain stream.
    var forceProgressive by remember(channel.url) { mutableStateOf(false) }
    // Reconnect state: a drop restarts the stream after a short backoff instead of giving up.
    var played by remember(channel.url) { mutableStateOf(false) }
    var retries by remember(channel.url) { mutableIntStateOf(0) }
    var reconnectAt by remember(channel.url) { mutableStateOf<Long?>(null) }
    var restartKey by remember(channel.url) { mutableIntStateOf(0) }

    fun giveUp(reason: String) {
        player.stop()
        reconnectAt = null
        error = "This channel isn't working right now ($reason)."
        vm.markFailed(channel)
    }

    fun scheduleReconnect(reason: String) {
        val max = if (played) MAX_RETRIES_AFTER_PLAYING else MAX_RETRIES_BEFORE_PLAYING
        if (retries < max) {
            player.stop()
            reconnectAt = System.currentTimeMillis() + backoffMs(retries)
            retries++
        } else {
            giveUp(reason)
        }
    }

    DisposableEffect(player, channel.url) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                when (state) {
                    Player.STATE_READY -> {
                        error = null
                        retries = 0
                        reconnectAt = null
                        if (!played) {
                            played = true
                            vm.markWorking(channel)
                        }
                    }
                    // A live stream should never end; treat it as a dropped connection.
                    Player.STATE_ENDED -> scheduleReconnect("stream ended")
                    else -> Unit
                }
            }
            override fun onPlayerError(e: PlaybackException) {
                when {
                    e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        player.seekToDefaultPosition()
                        player.prepare()
                    }
                    !played && !forceProgressive && guessedHls(channel.url) -> forceProgressive = true
                    else -> scheduleReconnect(e.errorCodeName)
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Watchdog: some dead servers accept the connection and then send nothing, which never
    // raises a player error. Treat a stream that stays stuck loading as failed.
    LaunchedEffect(channel.url, restartKey, forceProgressive, buffering, played, error, reconnectAt, castTarget) {
        if (castTarget != null || !buffering || error != null || reconnectAt != null) return@LaunchedEffect
        delay(if (played) STALL_TIMEOUT_MS else STARTUP_TIMEOUT_MS)
        when {
            played -> scheduleReconnect("stalled")
            !forceProgressive && guessedHls(channel.url) -> forceProgressive = true
            else -> scheduleReconnect("no response")
        }
    }

    LaunchedEffect(reconnectAt) {
        val at = reconnectAt ?: return@LaunchedEffect
        delay((at - System.currentTimeMillis()).coerceAtLeast(0))
        reconnectAt = null
        restartKey++
    }

    LaunchedEffect(channel.url, forceProgressive, restartKey, castTarget) {
        error = null
        if (castTarget != null) {
            // The Roku plays the stream; this device just acts as the remote.
            player.stop()
            buffering = false
            reconnectAt = null
            return@LaunchedEffect
        }
        buffering = true
        startChannel(player, channel, forceProgressive)
        if (restartKey == 0) {
            overlayVisible = true
            overlayTick++
        }
    }

    LaunchedEffect(overlayTick) {
        delay(4_000)
        overlayVisible = false
    }

    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1_500)
        digits.toIntOrNull()?.let { n -> if (n in 1..queue.size) vm.playAt(n - 1) }
        digits = ""
    }

    BackHandler { vm.stop(); onExit() }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (val code = ev.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> { vm.playAt(i + 1); true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> { vm.playAt(i - 1); true }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_INFO, AndroidKeyEvent.KEYCODE_DPAD_LEFT,
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { overlayVisible = true; overlayTick++; true }
                    AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_BOOKMARK,
                    AndroidKeyEvent.KEYCODE_PROG_YELLOW -> { vm.toggleFavorite(channel); overlayVisible = true; overlayTick++; true }
                    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
                        digits = (digits + (code - AndroidKeyEvent.KEYCODE_0)).takeLast(5); true
                    }
                    else -> false
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                overlayVisible = !overlayVisible
                if (overlayVisible) overlayTick++
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    isFocusable = false
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        castTarget?.let { device ->
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.CastConnected, null, tint = Accent, modifier = Modifier.padding(8.dp))
                Text(channel.name, color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text(castMessage ?: "Playing on ${device.name}", color = Color(0xFFBBBBCC),
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.padding(6.dp))
                OutlinedButton(onClick = vm::stopCasting) { Text("Stop casting", color = Color.White) }
            }
        }

        if (castTarget == null && (buffering || reconnectAt != null) && error == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Accent)
                if (reconnectAt != null || retries > 0) {
                    Spacer(Modifier.padding(6.dp))
                    Text("Reconnecting…", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        error?.let {
            Column(
                Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(it, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.padding(4.dp))
                Text("It's been hidden from the list for now.",
                    color = Color(0xFFBBBBCC), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.padding(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { retries = 0; error = null; restartKey++ }) {
                        Text("Try again", color = Color.White)
                    }
                    Button(onClick = { vm.playAt(i + 1) }) { Text("Next channel") }
                }
            }
        }

        if (showCast) CastDialog(vm, onDismiss = { showCast = false })

        if (digits.isNotEmpty()) {
            Text(
                digits,
                Modifier.align(Alignment.TopEnd).padding(32.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(horizontal = 20.dp, vertical = 10.dp),
                color = Color.White,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
        }

        AnimatedVisibility(overlayVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                IconButton(
                    onClick = { vm.stop(); onExit() },
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp).background(Color(0x88000000), RoundedCornerShape(50)),
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }

                IconButton(
                    onClick = { showCast = true },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color(0x88000000), RoundedCornerShape(50)),
                ) {
                    Icon(
                        if (castTarget != null) Icons.Default.CastConnected else Icons.Default.Cast,
                        "Cast to Roku",
                        tint = if (castTarget != null) Accent else Color.White,
                    )
                }

                Column(
                    Modifier.align(Alignment.CenterEnd).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconButton(onClick = { vm.playAt(i + 1) }, Modifier.background(Color(0x88000000), RoundedCornerShape(50))) {
                        Icon(Icons.Default.KeyboardArrowUp, "Next channel", tint = Color.White)
                    }
                    IconButton(onClick = { vm.playAt(i - 1) }, Modifier.background(Color(0x88000000), RoundedCornerShape(50))) {
                        Icon(Icons.Default.KeyboardArrowDown, "Previous channel", tint = Color.White)
                    }
                }

                InfoBar(
                    channel = channel,
                    number = i + 1,
                    total = queue.size,
                    favorite = channel.url in favorites,
                    onFavorite = { vm.toggleFavorite(channel) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun InfoBar(channel: Channel, number: Int, total: Int, favorite: Boolean, onFavorite: () -> Unit, modifier: Modifier) {
    // Phones in portrait get a compact bar; the remote-key hint only shows on wide (TV) screens.
    val compact = LocalConfiguration.current.screenWidthDp < 600
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE000000))))
            .padding(horizontal = if (compact) 16.dp else 32.dp, vertical = if (compact) 16.dp else 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$number", color = Accent, fontWeight = FontWeight.Bold,
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.width(if (compact) 12.dp else 20.dp))
        ChannelLogo(channel, if (compact) 44.dp else 64.dp)
        Spacer(Modifier.width(if (compact) 12.dp else 20.dp))
        Column(Modifier.weight(1f)) {
            Text(channel.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall)
            val sub = listOfNotNull(channel.group, channel.country?.let(TvViewModel::countryName), "$number of $total")
            Text(sub.joinToString(" · "), color = Color(0xFFBBBBCC), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium)
            if (!compact) {
                Text("▲▼ change channel · 0–9 jump to number · MENU favorite",
                    color = Color(0xFF888899), style = MaterialTheme.typography.bodySmall)
            }
        }
        IconButton(onClick = onFavorite) {
            Icon(
                if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                "Favorite",
                tint = if (favorite) Color(0xFFFFC94D) else Color.White,
            )
        }
    }
}

private const val MAX_RETRIES_BEFORE_PLAYING = 1
private const val STARTUP_TIMEOUT_MS = 15_000L
private const val STALL_TIMEOUT_MS = 20_000L
private const val MAX_RETRIES_AFTER_PLAYING = 6

private fun backoffMs(attempt: Int): Long = minOf(1_000L shl attempt, 15_000L)

private fun guessedHls(url: String): Boolean =
    Util.inferContentType(Uri.parse(url)) == C.CONTENT_TYPE_OTHER && !hasMediaExtension(url)

private fun hasMediaExtension(url: String): Boolean {
    val path = Uri.parse(url).path.orEmpty().lowercase()
    return listOf(".ts", ".mp4", ".mkv", ".mp3", ".aac", ".flv", ".webm").any { path.endsWith(it) }
}

@OptIn(UnstableApi::class)
private fun startChannel(player: ExoPlayer, channel: Channel, forceProgressive: Boolean) {
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(channel.userAgent ?: DEFAULT_UA)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(20_000)
    channel.referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }

    val item = MediaItem.Builder().setUri(channel.url).apply {
        if (!forceProgressive && guessedHls(channel.url)) setMimeType(MimeTypes.APPLICATION_M3U8)
    }.build()

    val factory = DefaultMediaSourceFactory(http)
        // Retry individual segment/playlist downloads before surfacing an error.
        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))
    player.setMediaSource(factory.createMediaSource(item))
    player.prepare()
    player.play()
}
