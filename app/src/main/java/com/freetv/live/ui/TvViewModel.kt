package com.freetv.live.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freetv.live.data.Channel
import com.freetv.live.data.ChannelRepository
import com.freetv.live.data.Guide
import com.freetv.live.data.GuideRepository
import com.freetv.live.data.PlaylistSource
import com.freetv.live.data.RokuCaster
import com.freetv.live.data.RokuDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

sealed interface Section {
    data object All : Section
    data object Favorites : Section
    data object Recent : Section
    data class Group(val name: String) : Section
    data class Country(val code: String) : Section
}

enum class LanguageTab(val label: String) { English("English"), Other("Other languages") }

data class Facet(val section: Section, val label: String, val count: Int)

class TvViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ChannelRepository(app)
    private val caster = RokuCaster(app)
    private val guideRepo = GuideRepository(app)

    private val _guide = MutableStateFlow(Guide.Empty)
    val guide: StateFlow<Guide> = _guide.asStateFlow()

    /** Show only channels from official free services and broadcasters. */
    private val _officialOnly = MutableStateFlow(repo.officialOnly)
    val officialOnly: StateFlow<Boolean> = _officialOnly.asStateFlow()

    fun setOfficialOnly(on: Boolean) {
        repo.officialOnly = on
        _officialOnly.value = on
    }
    private val castPrefs = app.getSharedPreferences("cast", android.content.Context.MODE_PRIVATE)

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _errors = MutableStateFlow<List<String>>(emptyList())
    val errors: StateFlow<List<String>> = _errors.asStateFlow()

    private val _favorites = MutableStateFlow(repo.favorites())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _recents = MutableStateFlow(repo.recents())

    /** Channels that failed to play in the last 24 hours. */
    private val _failed = MutableStateFlow(repo.failures())
    val failed: StateFlow<Map<String, Long>> = _failed.asStateFlow()

    private val _hideFailed = MutableStateFlow(repo.hideFailed)
    val hideFailed: StateFlow<Boolean> = _hideFailed.asStateFlow()

    private val _tab = MutableStateFlow(LanguageTab.English)
    val tab: StateFlow<LanguageTab> = _tab.asStateFlow()

    /** Channels in the selected language tab. */
    val tabChannels: StateFlow<List<Channel>> = combine(_channels, _tab, _officialOnly) { all, tab, officialOnly ->
        all.filter { it.english == (tab == LanguageTab.English) && (!officialOnly || it.official) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _section = MutableStateFlow<Section>(Section.All)
    val section: StateFlow<Section> = _section.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _sources = MutableStateFlow(repo.allSources())
    val sources: StateFlow<List<PlaylistSource>> = _sources.asStateFlow()

    private val _disabled = MutableStateFlow(repo.disabledSources())
    val disabledSources: StateFlow<Set<String>> = _disabled.asStateFlow()

    /** The list being played through with channel up/down, and the position in it. */
    private val _queue = MutableStateFlow<List<Channel>>(emptyList())
    val queue: StateFlow<List<Channel>> = _queue.asStateFlow()
    private val _playing = MutableStateFlow<Int?>(null)
    val playing: StateFlow<Int?> = _playing.asStateFlow()

    val groups: StateFlow<List<Facet>> = tabChannels.map { list ->
        list.groupingBy { it.group }.eachCount()
            .map { (g, n) -> Facet(Section.Group(g), g, n) }
            .sortedWith(compareBy<Facet> { it.label == "Other" }.thenBy { it.label.lowercase() })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val countries: StateFlow<List<Facet>> = tabChannels.map { list ->
        list.mapNotNull { it.country }.groupingBy { it }.eachCount()
            .map { (c, n) -> Facet(Section.Country(c), countryName(c), n) }
            .sortedBy { it.label }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _filters = combine(_section, _query, _failed, _hideFailed) { section, query, failed, hide ->
        Filters(section, query, if (hide) failed.keys else emptySet())
    }

    private data class Filters(val section: Section, val query: String, val hidden: Set<String>)

    val visible: StateFlow<List<Channel>> = combine(
        _channels, tabChannels, _filters, _favorites, _recents,
    ) { all, inTab, (section, query, hidden), favs, recents ->
        // Favorites and recents span both language tabs; everything else is per tab.
        val base = when (section) {
            Section.All -> inTab
            Section.Favorites -> all.filter { it.url in favs }
            Section.Recent -> {
                val byUrl = all.associateBy { it.url }
                recents.mapNotNull { byUrl[it] }
            }
            is Section.Group -> inTab.filter { it.group == section.name }
            is Section.Country -> inTab.filter { it.country == section.code }
        }
        // Favorites always stay visible, even when they failed recently.
        val healthy = if (section == Section.Favorites || hidden.isEmpty()) base else base.filter { it.url !in hidden }
        val q = query.trim()
        if (q.isEmpty()) healthy else healthy.filter {
            it.name.contains(q, ignoreCase = true) || it.group.contains(q, ignoreCase = true)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        refresh(force = false)
    }

    fun refresh(force: Boolean) {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            val result = repo.load(force)
            _channels.value = result.channels
            _errors.value = result.errors
            _loading.value = false
            _guide.value = guideRepo.load(force)
        }
    }

    fun selectSection(s: Section) { _section.value = s }

    fun selectTab(t: LanguageTab) {
        _tab.value = t
        // Category/country picks belong to one tab, so reset them when switching.
        if (_section.value is Section.Group || _section.value is Section.Country) _section.value = Section.All
    }
    fun setQuery(q: String) { _query.value = q }

    fun toggleFavorite(c: Channel) {
        val fav = c.url !in _favorites.value
        repo.setFavorite(c.url, fav)
        _favorites.value = repo.favorites()
    }

    fun markFailed(c: Channel) {
        repo.setFailed(c.url, true)
        _failed.value = repo.failures()
    }

    fun markWorking(c: Channel) {
        if (c.url !in _failed.value) return
        repo.setFailed(c.url, false)
        _failed.value = repo.failures()
    }

    fun setHideFailed(hide: Boolean) {
        repo.hideFailed = hide
        _hideFailed.value = hide
    }

    fun clearFailures() {
        _failed.value.keys.forEach { repo.setFailed(it, false) }
        _failed.value = repo.failures()
    }

    fun play(list: List<Channel>, index: Int) {
        _queue.value = list
        playAt(index)
    }

    /** Moves to the channel at [index] in the current queue, wrapping around. */
    fun playAt(index: Int) {
        val q = _queue.value
        if (q.isEmpty()) return
        val i = ((index % q.size) + q.size) % q.size
        _playing.value = i
        repo.addRecent(q[i].url)
        _recents.value = repo.recents()
        sendToRoku()
    }

    fun stop() { _playing.value = null }

    // ---- Casting to Roku ----

    private val _rokus = MutableStateFlow<List<RokuDevice>>(emptyList())
    val rokus: StateFlow<List<RokuDevice>> = _rokus.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** The Roku that channel changes go to, or null when playing on this device. */
    private val _castTarget = MutableStateFlow<RokuDevice?>(null)
    val castTarget: StateFlow<RokuDevice?> = _castTarget.asStateFlow()

    private val _castMessage = MutableStateFlow<String?>(null)
    val castMessage: StateFlow<String?> = _castMessage.asStateFlow()

    /** True once a Roku turned out not to have the receiver channel; the UI shows setup steps. */
    private val _receiverMissing = MutableStateFlow(false)
    val receiverMissing: StateFlow<Boolean> = _receiverMissing.asStateFlow()

    fun dismissReceiverHelp() { _receiverMissing.value = false }

    private val _installing = MutableStateFlow(false)
    val installing: StateFlow<Boolean> = _installing.asStateFlow()

    fun installReceiver(password: String) {
        val device = _castTarget.value ?: return
        viewModelScope.launch {
            _installing.value = true
            _castMessage.value = "Installing the receiver on ${device.name}…"
            try {
                caster.installReceiver(device, password)
                _receiverMissing.value = false
                _castMessage.value = "Installed. Starting the channel…"
                kotlinx.coroutines.delay(1_500)
                sendToRoku()
            } catch (e: Exception) {
                _castMessage.value = e.message ?: "Install failed."
            }
            _installing.value = false
        }
    }

    fun retryCast() = sendToRoku()

    fun scanRokus() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            _castMessage.value = null
            val found = caster.discover().toMutableList()
            // Discovery can miss devices on some routers; also try the last Roku used.
            castPrefs.getString("last_host", null)?.let { last ->
                if (found.none { it.host == last }) caster.probe(last)?.let(found::add)
            }
            _rokus.value = found
            if (found.isEmpty()) _castMessage.value = "No Roku found. Make sure your phone is on the same Wi-Fi, or enter the Roku's IP address."
            _scanning.value = false
        }
    }

    fun addRokuByIp(ip: String) {
        viewModelScope.launch {
            _scanning.value = true
            val device = caster.probe(ip)
            if (device == null) {
                _castMessage.value = "No Roku answered at $ip. Find its IP on the Roku under Settings → Network → About."
            } else {
                _rokus.value = (_rokus.value.filterNot { it.host == device.host } + device)
                castTo(device)
            }
            _scanning.value = false
        }
    }

    fun castTo(device: RokuDevice) {
        _castTarget.value = device
        castPrefs.edit().putString("last_host", device.host).apply()
        sendToRoku()
    }

    fun stopCasting() {
        val device = _castTarget.value ?: return
        _castTarget.value = null
        _castMessage.value = null
        viewModelScope.launch { runCatching { caster.stop(device) } }
    }

    private fun sendToRoku() {
        val device = _castTarget.value ?: return
        val channel = _playing.value?.let { _queue.value.getOrNull(it) } ?: return
        viewModelScope.launch {
            _receiverMissing.value = false
            _castMessage.value = "Sending ${channel.name} to ${device.name}…"
            _castMessage.value = try {
                caster.play(device, channel)
                if (channel.userAgent != null || channel.referrer != null) {
                    "Playing on ${device.name}. This channel needs special headers, so it may not load on Roku."
                } else {
                    "Playing on ${device.name}"
                }
            } catch (e: RokuCaster.ReceiverMissingException) {
                _receiverMissing.value = true
                e.message
            } catch (e: Exception) {
                "Couldn't reach ${device.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        repo.setSourceEnabled(id, enabled)
        _disabled.value = repo.disabledSources()
        refresh(force = false)
    }

    fun addSource(name: String, url: String) {
        repo.addCustomSource(name.ifBlank { "My playlist" }, url.trim())
        _sources.value = repo.allSources()
        refresh(force = true)
    }

    fun removeSource(id: String) {
        repo.removeCustomSource(id)
        _sources.value = repo.allSources()
        refresh(force = false)
    }

    companion object {
        fun countryName(code: String): String {
            val iso = if (code == "UK") "GB" else code
            return Locale("", iso).getDisplayCountry(Locale.getDefault()).ifBlank { code }
        }
    }
}
