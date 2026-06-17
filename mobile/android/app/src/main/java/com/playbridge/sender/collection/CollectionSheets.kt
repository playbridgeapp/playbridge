package com.playbridge.sender.collection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.playbridge.sender.data.collection.CollectionItemDraft
import com.playbridge.sender.data.collection.CollectionItemKind
import com.playbridge.sender.data.collection.CollectionSource

/** Build a manual (typed URL) web item draft. */
fun manualDraft(name: String, url: String): CollectionItemDraft = CollectionItemDraft(
    title = name.ifBlank { url },
    url = url,
    kind = CollectionItemKind.WEB,
    sourceTag = CollectionSource.MANUAL,
)

/**
 * Reusable "Add to Collection" bottom sheet. Shows existing collections + a "New collection"
 * row; picking one adds [draft] to it (creating it first if new). Used by entry points across
 * the app (IPTV, Cast History, Phone Files, …).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToCollectionSheet(
    viewModel: CollectionsViewModel,
    draft: CollectionItemDraft,
    onDismiss: () -> Unit,
    onAdded: (collectionName: String, added: Boolean) -> Unit = { _, _ -> },
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val collections by viewModel.collections.collectAsState()
    var showCreate by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                "Add to collection",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCreate = true }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(12.dp))
                Text("New collection…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }

            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(collections, key = { it.id }) { collection ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.addItem(collection.id, draft) { added ->
                                    onAdded(collection.name, added)
                                }
                                onDismiss()
                            }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.PlaylistPlay,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                collection.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${collection.itemCount} item${if (collection.itemCount == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showCreate) {
        CollectionNameDialog(
            title = "New collection",
            initial = "",
            confirmLabel = "Create & add",
            onConfirm = { name ->
                viewModel.createCollection(name) { id ->
                    viewModel.addItem(id, draft)
                }
                showCreate = false
                onAdded(name, true) // brand-new collection — always added
                onDismiss()
            },
            onDismiss = { showCreate = false },
        )
    }
}
