package com.freetv.live.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TabRowDefaults
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.freetv.live.data.Channel
import kotlinx.coroutines.launch

@Composable
fun BrowseScreen(vm: TvViewModel, onOpenSources: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().nightBackground()) {
        if (maxWidth >= 720.dp) {
            Row(Modifier.fillMaxSize()) {
                SectionPane(vm, Modifier.width(280.dp).fillMaxHeight(), onPicked = {})
                ChannelPane(vm, onMenu = null, onOpenSources = onOpenSources)
            }
        } else {
            val drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = {
                    ModalDrawerSheet(Modifier.widthIn(max = 300.dp)) {
                        SectionPane(vm, Modifier.fillMaxHeight(), onPicked = { scope.launch { drawer.close() } })
                    }
                },
            ) {
                ChannelPane(vm, onMenu = { scope.launch { drawer.open() } }, onOpenSources = onOpenSources)
            }
        }
    }
}

@Composable
private fun SectionPane(vm: TvViewModel, modifier: Modifier, onPicked: () -> Unit) {
    val current by vm.section.collectAsState()
    val channels by vm.tabChannels.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val groups by vm.groups.collectAsState()
    val countries by vm.countries.collectAsState()

    LazyColumn(modifier.background(Night.copy(alpha = 0.72f)).padding(vertical = 12.dp)) {
        item {
            GuideClock(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), timeSize = 34.sp)
        }
        item { SectionItem("All channels", channels.size, current == Section.All) { vm.selectSection(Section.All); onPicked() } }
        item { SectionItem("Favorites", favorites.size, current == Section.Favorites) { vm.selectSection(Section.Favorites); onPicked() } }
        item { SectionItem("Recently watched", null, current == Section.Recent) { vm.selectSection(Section.Recent); onPicked() } }
        item { Header("Categories") }
        items(groups, key = { "g" + it.label }) { f ->
            SectionItem(f.label, f.count, current == f.section) { vm.selectSection(f.section); onPicked() }
        }
        item { Header("Countries") }
        items(countries, key = { "c" + it.label }) { f ->
            SectionItem(f.label, f.count, current == f.section) { vm.selectSection(f.section); onPicked() }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text.uppercase(),
        Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SectionItem(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(10.dp),
        color = when {
            focused -> Violet
            selected -> Violet.copy(alpha = 0.2f)
            else -> Color.Transparent
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (count != null) {
                Text("$count", style = MaterialTheme.typography.labelMedium,
                    color = if (focused) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChannelPane(vm: TvViewModel, onMenu: (() -> Unit)?, onOpenSources: () -> Unit) {
    val list by vm.visible.collectAsState()
    val loading by vm.loading.collectAsState()
    val errors by vm.errors.collectAsState()
    val query by vm.query.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val all by vm.channels.collectAsState()
    val guide by vm.guide.collectAsState()
    val now by rememberNow()

    Column(Modifier.fillMaxSize()) {
        val castTarget by vm.castTarget.collectAsState()
        var showCast by remember { mutableStateOf(false) }
        if (showCast) CastDialog(vm, onDismiss = { showCast = false })
        val actions: @Composable () -> Unit = {
            IconButton(onClick = { showCast = true }) {
                Icon(
                    if (castTarget != null) Icons.Default.CastConnected else Icons.Default.Cast,
                    "Cast to Roku",
                    tint = if (castTarget != null) Cyan else LocalContentColor.current,
                )
            }
            IconButton(onClick = { vm.refresh(force = true) }) { Icon(Icons.Default.Refresh, "Refresh") }
            IconButton(onClick = onOpenSources) { Icon(Icons.Default.Settings, "Sources") }
        }
        val search: @Composable (Modifier) -> Unit = { m ->
            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = m,
                singleLine = true,
                placeholder = { Text("Search channels") },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Violet) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Clear, "Clear") }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Violet,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = Night.copy(alpha = 0.5f),
                    unfocusedContainerColor = Night.copy(alpha = 0.5f),
                ),
            )
        }
        if (onMenu != null) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, "Sections") }
                Text("Live TV", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                actions()
            }
            search(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                search(Modifier.weight(1f))
                actions()
            }
        }
        val tab by vm.tab.collectAsState()
        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = Color.Transparent,
            indicator = { positions ->
                Box(
                    with(TabRowDefaults) { Modifier.tabIndicatorOffset(positions[tab.ordinal]) }
                        .padding(horizontal = 24.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(NeonBrush)
                )
            },
            divider = {},
        ) {
            LanguageTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { vm.selectTab(t) },
                    text = { Text(t.label, fontWeight = if (tab == t) FontWeight.SemiBold else FontWeight.Normal) },
                    selectedContentColor = Color.White,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val officialOnly by vm.officialOnly.collectAsState()
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val chipColors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Violet.copy(alpha = 0.22f),
                selectedLabelColor = Color.White,
            )
            FilterChip(
                selected = officialOnly,
                onClick = { vm.setOfficialOnly(true) },
                label = { Text("Official channels") },
                colors = chipColors,
                shape = RoundedCornerShape(50),
            )
            FilterChip(
                selected = !officialOnly,
                onClick = { vm.setOfficialOnly(false) },
                label = { Text("All streams") },
                colors = chipColors,
                shape = RoundedCornerShape(50),
            )
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (errors.isNotEmpty()) {
            Text(
                "Couldn't update: " + errors.joinToString("; "),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "${list.size} channels",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (list.isEmpty() && !loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (all.isEmpty()) "No channels loaded. Check your connection and tap refresh."
                    else "Nothing here yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(list, key = { _, c -> c.url }) { i, c ->
                    ChannelRow(
                        number = i + 1,
                        channel = c,
                        nowShow = guide.nowAndNext(c, now).first,
                        now = now,
                        favorite = c.url in favorites,
                        onPlay = { vm.play(list, i) },
                        onFavorite = { vm.toggleFavorite(c) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    number: Int,
    channel: Channel,
    nowShow: com.freetv.live.data.Programme?,
    now: Long,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            onClick = onPlay,
            interactionSource = interaction,
            shape = RoundedCornerShape(16.dp),
            color = if (focused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
            modifier = Modifier
                .weight(1f)
                .then(if (focused) Modifier.border(2.dp, NeonBrush, RoundedCornerShape(16.dp)) else Modifier),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$number",
                    Modifier.width(56.dp),
                    style = numberStyle(18.sp),
                    color = if (focused) Cyan else Violet,
                )
                ChannelLogo(channel, 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium)
                    if (nowShow != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(Pink))
                            Spacer(Modifier.width(6.dp))
                            Text(nowShow.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(5.dp))
                        NeonProgress(progressOf(nowShow, now), Modifier.fillMaxWidth(0.85f))
                    } else {
                        val sub = listOfNotNull(channel.group, channel.country?.let(TvViewModel::countryName)).joinToString(" · ")
                        Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        IconButton(onClick = onFavorite) {
            Icon(
                if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                if (favorite) "Remove from favorites" else "Add to favorites",
                tint = if (favorite) Color(0xFFFFC94D) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ChannelLogo(channel: Channel, size: androidx.compose.ui.unit.Dp) {
    var failed by remember(channel.logo) { mutableStateOf(channel.logo == null) }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E1B3A)),
        contentAlignment = Alignment.Center,
    ) {
        if (failed) {
            Text(channel.name.take(2).uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge)
        } else {
            AsyncImage(
                model = channel.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        }
    }
}
