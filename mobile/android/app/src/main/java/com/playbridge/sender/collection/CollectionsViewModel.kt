package com.playbridge.sender.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playbridge.sender.data.collection.CollectionEntity
import com.playbridge.sender.data.collection.CollectionItemDraft
import com.playbridge.sender.data.collection.CollectionItemEntity
import com.playbridge.sender.data.collection.CollectionsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CollectionsViewModel(
    private val repository: CollectionsRepository,
) : ViewModel() {

    val collections: StateFlow<List<CollectionEntity>> =
        repository.observeCollections()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun itemsFor(collectionId: Long): Flow<List<CollectionItemEntity>> =
        repository.observeItems(collectionId)

    fun collectionById(id: Long): CollectionEntity? = collections.value.find { it.id == id }

    fun createCollection(name: String, onCreated: (Long) -> Unit = {}) = viewModelScope.launch {
        onCreated(repository.createCollection(name))
    }

    fun renameCollection(id: Long, name: String) = viewModelScope.launch {
        repository.renameCollection(id, name)
    }

    fun deleteCollection(id: Long) = viewModelScope.launch { repository.deleteCollection(id) }

    fun addItem(
        collectionId: Long,
        draft: CollectionItemDraft,
        onResult: (added: Boolean) -> Unit = {},
    ) = viewModelScope.launch {
        onResult(repository.addItem(collectionId, draft))
    }

    fun removeItem(item: CollectionItemEntity) = viewModelScope.launch {
        repository.removeItem(item)
    }

    fun moveItem(collectionId: Long, itemId: Long, up: Boolean) = viewModelScope.launch {
        repository.moveItem(collectionId, itemId, up)
    }
}
