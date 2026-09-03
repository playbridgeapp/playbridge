package com.playbridge.sender.iptv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playbridge.sender.data.iptv.IptvChannelEntity
import com.playbridge.sender.data.iptv.IptvPlaylistEntity
import com.playbridge.sender.data.iptv.IptvPlaylistSort
import com.playbridge.sender.data.iptv.IptvRepository
import com.playbridge.sender.data.iptv.IptvSortRules
import com.playbridge.sender.data.iptv.IptvSourceType
import com.playbridge.sender.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Live probe progress for the currently-explored playlist. */
data class ProbeProgress(val playlistId: Long, val done: Int, val total: Int) {
    val isRunning: Boolean get() = done < total
}

class IptvViewModel(
    application: Application,
    private val repository: IptvRepository,
    private val settings: SettingsRepository,
) : AndroidViewModel(application) {

    /** Playlists sorted by the user's chosen key + direction. */
    val playlists: StateFlow<List<IptvPlaylistEntity>> =
        combine(
            repository.observePlaylists(),
            settings.iptvSort,
            settings.iptvSortAscending,
        ) { list, sortKey, ascending ->
            val sort = runCatching { IptvPlaylistSort.valueOf(sortKey) }
                .getOrDefault(IptvPlaylistSort.ADDED_DATE)
            IptvSortRules.sortPlaylists(list, sort, ascending)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sortKey: StateFlow<String> =
        settings.iptvSort.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "ADDED_DATE")
    val sortAscending: StateFlow<Boolean> =
        settings.iptvSortAscending.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val activeFirst: StateFlow<Boolean> =
        settings.iptvActiveFirst.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _updatingPlaylistId = MutableStateFlow<Long?>(null)
    val updatingPlaylistId: StateFlow<Long?> = _updatingPlaylistId.asStateFlow()

    private val _probeProgress = MutableStateFlow<ProbeProgress?>(null)
    val probeProgress: StateFlow<ProbeProgress?> = _probeProgress.asStateFlow()

    /** Distinct group names for the group filter chips (cheap SQL, not full rows). */
    fun groupsFor(playlistId: Long): Flow<List<String>> = repository.observeGroups(playlistId)

    /** Raw cached channels for a playlist (screen applies search + active-first ordering). */
    fun channelsFor(playlistId: Long): Flow<List<IptvChannelEntity>> =
        repository.observeChannels(playlistId)

    /**
     * Perf: DB-side filtered channel flow — search/group/active-first handled in SQL
     * so large playlists don't sort fully in memory. Room still re-runs the query
     * on every probe UPDATE; the trailing debounce below only drops Compose
     * emissions so per-channel writes don't rebuild the LazyColumn per channel.
     * First emission per filter set is immediate (no first-paint/search delay);
     * only follow-up re-emissions (probe ticks) are debounced 500ms.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun channelsFiltered(
        playlistId: Long,
        query: Flow<String>,
        group: Flow<String?>,
    ): Flow<List<IptvChannelEntity>> =
        combine(query, group, activeFirst) { q, g, af -> Triple(q, g, af) }
            .distinctUntilChanged()
            .flatMapLatest { (q, g, af) ->
                kotlinx.coroutines.flow.channelFlow {
                    var first = true
                    var pending: kotlinx.coroutines.Job? = null
                    repository.observeChannelsFiltered(playlistId, q, g, af).collect { v ->
                        if (first) {
                            first = false
                            send(v)
                        } else {
                            pending?.cancel()
                            pending = launch {
                                kotlinx.coroutines.delay(500)
                                send(v)
                            }
                        }
                    }
                }
            }

    fun playlistById(id: Long): IptvPlaylistEntity? = playlists.value.find { it.id == id }

    fun addUrlPlaylist(name: String, url: String) = viewModelScope.launch {
        repository.addPlaylist(name, url, IptvSourceType.URL)
    }

    fun addFilePlaylist(name: String, uri: String) = viewModelScope.launch {
        repository.addPlaylist(name, uri, IptvSourceType.FILE)
    }

    fun editPlaylist(id: Long, name: String, source: String, sourceType: String) =
        viewModelScope.launch { repository.editPlaylist(id, name, source, sourceType) }

    fun deletePlaylist(playlist: IptvPlaylistEntity) =
        viewModelScope.launch { repository.deletePlaylist(playlist) }

    fun refresh(id: Long) = viewModelScope.launch {
        _updatingPlaylistId.value = id
        runCatching { repository.refresh(id) }
        _updatingPlaylistId.value = null
    }

    fun probe(playlistId: Long, force: Boolean = false) = viewModelScope.launch {
        _probeProgress.value = ProbeProgress(playlistId, 0, 0)
        // Perf: probe callback fires per channel (up to 10k) — throttle so each
        // write doesn't recompose the detail screen and re-filter the full list.
        var lastEmit = 0L
        var lastDone = -1
        repository.probe(playlistId, force) { done, total ->
            val now = System.currentTimeMillis()
            val pct = if (total > 0) (done * 100) / total else 0
            val lastPct = (lastDone * 100) / total.coerceAtLeast(1)
            if (done == total || now - lastEmit >= 500 || pct - lastPct >= 5) {
                lastEmit = now
                lastDone = done
                _probeProgress.value = ProbeProgress(playlistId, done, total)
            }
        }
        _probeProgress.value = null
    }

    fun setSort(key: String) = viewModelScope.launch { settings.setIptvSort(key) }
    fun setSortAscending(value: Boolean) = viewModelScope.launch { settings.setIptvSortAscending(value) }
    fun setActiveFirst(value: Boolean) = viewModelScope.launch { settings.setIptvActiveFirst(value) }
}
