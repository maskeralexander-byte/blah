package com.freetv.live.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freetv.live.data.Channel
import com.freetv.live.data.ChannelRepository
import com.freetv.live.data.PlaylistSource
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

data class Facet(val section: Section, val label: String, val count: Int)

class TvViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ChannelRepository(app)

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

    val groups: StateFlow<List<Facet>> = _channels.map { list ->
        list.groupingBy { it.group }.eachCount()
            .map { (g, n) -> Facet(Section.Group(g), g, n) }
            .sortedWith(compareBy<Facet> { it.label == "Other" }.thenBy { it.label.lowercase() })
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val countries: StateFlow<List<Facet>> = _channels.map { list ->
        list.mapNotNull { it.country }.groupingBy { it }.eachCount()
            .map { (c, n) -> Facet(Section.Country(c), countryName(c), n) }
            .sortedBy { it.label }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _filters = combine(_section, _query, _failed, _hideFailed) { section, query, failed, hide ->
        Filters(section, query, if (hide) failed.keys else emptySet())
    }

    private data class Filters(val section: Section, val query: String, val hidden: Set<String>)

    val visible: StateFlow<List<Channel>> = combine(
        _channels, _filters, _favorites, _recents,
    ) { all, (section, query, hidden), favs, recents ->
        val base = when (section) {
            Section.All -> all
            Section.Favorites -> all.filter { it.url in favs }
            Section.Recent -> {
                val byUrl = all.associateBy { it.url }
                recents.mapNotNull { byUrl[it] }
            }
            is Section.Group -> all.filter { it.group == section.name }
            is Section.Country -> all.filter { it.country == section.code }
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
        }
    }

    fun selectSection(s: Section) { _section.value = s }
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
    }

    fun stop() { _playing.value = null }

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
