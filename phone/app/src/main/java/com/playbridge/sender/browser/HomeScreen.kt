package com.playbridge.sender.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.playbridge.sender.data.history.BookmarkDao
import com.playbridge.sender.data.history.HistoryDao
import com.playbridge.sender.data.history.HistoryEntity

// Simple data class for top sites (could be dynamic later)
data class TopSite(val title: String, val url: String, val iconUrl: String = "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    historyDao: HistoryDao,
    bookmarkDao: BookmarkDao
) {
    var searchText by remember { mutableStateOf("") }
    val recentHistory by historyDao.getAll().collectAsState(initial = emptyList())
    // Take top 5 recent items
    val recentItems = remember(recentHistory) { recentHistory.take(5) }
    
    val topSites = remember {
        listOf(
            TopSite("Google", "https://www.google.com"),
            TopSite("YouTube", "https://www.youtube.com"),
            TopSite("Wikipedia", "https://www.wikipedia.org"),
            TopSite("Reddit", "https://www.reddit.com"),
            TopSite("GitHub", "https://github.com")
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))
            
            // Logo or App Name
            Text(
                text = "PlayBridge",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Search Bar
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text("Search or type URL") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (searchText.isNotBlank()) {
                        onNavigate(normalizeUrl(searchText))
                    }
                }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Top Sites Grid (Simple Row for now)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                topSites.take(4).forEach { site ->
                    TopSiteItem(site = site, onClick = { onNavigate(site.url) })
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Recent History
            if (recentItems.isNotEmpty()) {
                Text(
                    text = "Recent History",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    recentItems.forEach { item ->
                        ListItem(
                            headlineContent = { 
                                Text(item.title ?: item.url, maxLines = 1, overflow = TextOverflow.Ellipsis) 
                            },
                            leadingContent = { Icon(Icons.Default.History, null) },
                            modifier = Modifier.clickable { onNavigate(item.url) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TopSiteItem(site: TopSite, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = site.title.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = site.title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    return when {
        trimmed.isEmpty() -> "about:blank"
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("about:") -> trimmed
        trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
        else -> "https://www.google.com/search?q=${trimmed.replace(" ", "+")}"
    }
}
