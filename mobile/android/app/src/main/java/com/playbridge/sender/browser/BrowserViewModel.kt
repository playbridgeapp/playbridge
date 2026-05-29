package com.playbridge.sender.browser

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playbridge.sender.data.history.BookmarkDao
import com.playbridge.sender.data.history.BookmarkEntity
import com.playbridge.sender.data.history.HistoryDao
import com.playbridge.sender.data.history.HistoryEntity
import com.playbridge.sender.data.history.TabDao
import com.playbridge.sender.data.history.TabEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.state.ContentState
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore

class BrowserViewModel(
    application: Application,
    private val historyDao: HistoryDao,
    private val bookmarkDao: BookmarkDao,
    private val tabDao: TabDao
) : AndroidViewModel(application) {

    private val TAG = "BrowserViewModel"

    // Search query flow for suggestions
    private val _editUrl = MutableStateFlow("")
    val editUrl: StateFlow<String> = _editUrl.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val suggestions: StateFlow<List<HistoryEntity>> = _editUrl
        .flatMapLatest { query ->
            historyDao.search(query)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun setEditUrl(url: String) {
        _editUrl.value = url
    }

    fun logHistory(url: String, title: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                historyDao.insert(HistoryEntity(url = url, title = title ?: "Cast Video"))
                Log.d(TAG, "Successfully logged history for url: $url")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log history for url: $url", e)
            }
        }
    }

    fun addBookmark(url: String, title: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                bookmarkDao.insert(
                    BookmarkEntity(
                        url = url,
                        title = title?.ifEmpty { null },
                        timestamp = System.currentTimeMillis()
                    )
                )
                Log.d(TAG, "Successfully added bookmark: $title")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add bookmark: $title", e)
            }
        }
    }

    fun saveTabs(
        tabs: List<TabSessionState>,
        selectedId: String?,
        allStates: Map<String, ByteArray>,
        parentIds: Map<String, String?>
    ) {
        val now = System.currentTimeMillis()
        Log.d(TAG, "saveTabs: starting save for ${tabs.size} tabs")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entities = tabs.mapIndexed { index, tab ->
                    TabEntity(
                        id = tab.id,
                        url = tab.content.url,
                        title = tab.content.title,
                        parentId = parentIds[tab.id] ?: tab.parentId,
                        isSelected = (tab.id == selectedId),
                        lastAccessTime = now,
                        sessionState = allStates[tab.id],
                        position = index
                    )
                }
                tabDao.updateTabs(entities)
                Log.d(TAG, "saveTabs: successfully updated DB with ${entities.size} tabs")
            } catch (e: Exception) {
                Log.e(TAG, "saveTabs: failed to update DB", e)
            }
        }
    }

    fun restoreTabs(
        tabManager: TabManager,
        store: BrowserStore,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (store.state.tabs.isEmpty()) {
                    Log.d("PB_STARTUP", "Store is empty — querying DB")
                    val savedTabs = tabDao.getAll()
                    Log.d("PB_STARTUP", "DB returned ${savedTabs.size} tabs")
                    if (savedTabs.isNotEmpty()) {
                        val sessionTabs = savedTabs.map { entity ->
                            TabSessionState(
                                id = entity.id,
                                content = ContentState(url = entity.url, title = entity.title ?: ""),
                                parentId = entity.parentId
                            )
                        }
                        val selectedId = savedTabs.find { it.isSelected }?.id
                        
                        withContext(Dispatchers.Main) {
                            Log.d("PB_STARTUP", "Populating savedStates and calling restoreTabs on Main thread")
                            savedTabs.forEach { entity ->
                                entity.sessionState?.let { bytes ->
                                    tabManager.savedStates[entity.id] = bytes
                                }
                            }
                            tabManager.restoreTabs(sessionTabs, selectedId, store)
                            Log.d("PB_STARTUP", "restoreTabs done — store now has ${store.state.tabs.size} tabs")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore tabs", e)
            } finally {
                withContext(Dispatchers.Main) {
                    onComplete()
                }
            }
        }
    }
}
