package app.refocus.ui.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.refocus.AppGraph
import app.refocus.camera.BatchProgress
import app.refocus.camera.ConnectionState
import app.refocus.data.PictureItem
import app.refocus.ui.formatDay
import app.refocus.ui.formatDuration
import app.refocus.ui.rememberPictureImage
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(graph: AppGraph, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val itemsMap by graph.library.items.collectAsState()
    val conn by graph.session.state.collectAsState()
    val cameraWifi by graph.network.cameraNetwork.collectAsState()
    val batch by graph.session.batch.collectAsState()
    val items = remember(itemsMap) { itemsMap.values.sortedByDescending { it.taken } }
    val zone = ZoneId.systemDefault()
    val groups = remember(items) { items.groupBy { it.taken.atZone(zone).toLocalDate() } }

    var selection by rememberSaveable { mutableStateOf(setOf<String>()) }
    val selecting = selection.isNotEmpty()
    var confirmRemove by remember { mutableStateOf(false) }
    BackHandler(selecting) { selection = emptySet() }
    // drop ids that disappeared
    LaunchedEffect(itemsMap) { selection = selection.filter { it in itemsMap }.toSet() }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        graph.session.downloadAll()
    }
    fun downloadAll() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) graph.session.downloadAll() else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val gridState = rememberLazyGridState()
    val remaining = items.count { it.onCamera && !it.rawDone }
    val showFab = conn is ConnectionState.Connected && remaining > 0 && batch == null && !selecting
    val fabExpanded by remember { derivedStateOf { gridState.firstVisibleItemIndex < 2 } }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            AnimatedContent(selecting, label = "topbar") { sel ->
                if (sel) {
                    val chosen = selection.mapNotNull { itemsMap[it] }
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Rounded.Close, "Cancel selection") }
                        },
                        title = { Text("${selection.size} selected") },
                        actions = {
                            if (conn.isLive && chosen.any { it.onCamera && !it.rawDone }) {
                                IconButton(onClick = {
                                    chosen.filter { it.onCamera && !it.rawDone }.reversed().forEach { graph.session.requestDownload(it.id) }
                                    selection = emptySet()
                                }) { Icon(Icons.Outlined.Download, "Download selected") }
                            }
                            if (chosen.any { it.rawDone || it.rawBytes > 0 }) {
                                IconButton(onClick = { confirmRemove = true }) { Icon(Icons.Outlined.Delete, "Remove from phone") }
                            }
                            IconButton(onClick = { selection = items.map { it.id }.toSet() }) {
                                Icon(Icons.Outlined.SelectAll, "Select all")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                } else {
                    TopAppBar(
                        title = {
                            Column {
                                Text("Refocus", fontWeight = FontWeight.SemiBold)
                                Text("A WiFi Lytro Viewer", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        actions = {
                            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
                        },
                        scrollBehavior = scroll,
                    )
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(showFab, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                ExtendedFloatingActionButton(
                    onClick = ::downloadAll,
                    expanded = fabExpanded,
                    icon = { Icon(Icons.Outlined.Download, null) },
                    text = { Text("Download all ($remaining)") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            PullToRefreshBox(
                isRefreshing = false,
                onRefresh = { if (conn.isLive) graph.session.refresh() else graph.session.connect() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "connection", span = { GridItemSpan(maxLineSpan) }) {
                        ConnectionCard(
                            state = conn,
                            cameraWifi = cameraWifi != null,
                            pictureCount = items.count { it.onCamera },
                            onConnect = { graph.session.connect() },
                            onDisconnect = { graph.session.disconnect() },
                            onWifiSettings = { context.startActivity(Intent(Settings.Panel.ACTION_WIFI)) },
                        )
                    }
                    if (items.isEmpty()) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { EmptyState(conn) }
                    }
                    groups.forEach { (day, dayItems) ->
                        item(key = "h-$day", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                formatDay(dayItems.first().taken),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp),
                            )
                        }
                        items(dayItems, key = { it.id }) { item ->
                            val haptics = LocalHapticFeedback.current
                            PictureTile(
                                graph = graph,
                                item = item,
                                selecting = selecting,
                                selected = item.id in selection,
                                onClick = {
                                    if (selecting) selection = selection.toggle(item.id) else onOpen(item.id)
                                },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selection = selection.toggle(item.id)
                                },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = batch != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                batch?.let { BatchCard(it, itemsMap, onStop = { graph.session.stopDownloads() }) }
            }
        }
    }

    if (confirmRemove) {
        val chosen = selection.mapNotNull { itemsMap[it] }.filter { it.rawDone || it.rawBytes > 0 }
        val gone = chosen.count { !it.onCamera }
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            icon = { Icon(Icons.Outlined.Delete, null) },
            title = { Text(if (chosen.size == 1) "Remove from this phone?" else "Remove ${chosen.size} pictures from this phone?") },
            text = {
                Text(
                    if (gone == 0) "The downloaded data is deleted from the phone. The pictures stay on the camera and can be downloaded again."
                    else "$gone of these are no longer on the camera and will be gone for good. Exports in your gallery are not affected."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    chosen.forEach {
                        graph.session.cancelDownload(it.id)
                        graph.cache.remove(it.id)
                        graph.library.removeLocal(it.id)
                    }
                    selection = emptySet()
                    confirmRemove = false
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

// ------------------------------------------------------------------- connection
@Composable
private fun ConnectionCard(
    state: ConnectionState,
    cameraWifi: Boolean,
    pictureCount: Int,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onWifiSettings: () -> Unit,
) {
    AnimatedContent(
        targetState = state to cameraWifi,
        contentKey = { (s, w) -> s::class to w },
        label = "connection",
    ) { (s, wifi) ->
        when (s) {
            is ConnectionState.Connected -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF34A853)))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Connected · $pictureCount ${if (pictureCount == 1) "picture" else "pictures"} on the camera",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDisconnect) { Text("Disconnect") }
            }
            is ConnectionState.Reconnecting -> StatusCard(
                icon = { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                title = "Connection interrupted",
                text = "Trying again (attempt ${s.attempt} of 3)…",
            )
            ConnectionState.Connecting -> StatusCard(
                icon = { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                title = "Connecting to the camera…",
                text = null,
            )
            is ConnectionState.Failed -> StatusCard(
                icon = { Icon(Icons.Outlined.LinkOff, null) },
                title = if (s.wasConnected) "Disconnected" else "Couldn't connect",
                text = s.message,
                error = true,
                actions = {
                    if (!wifi) OutlinedButton(onClick = onWifiSettings) { Text("WiFi settings") }
                    Button(onClick = onConnect) { Text("Try again") }
                },
            )
            ConnectionState.Disconnected -> if (wifi) StatusCard(
                icon = { Icon(Icons.Outlined.Wifi, null) },
                title = "Camera WiFi found",
                text = "Connect to browse and download the pictures on your camera.",
                actions = { Button(onClick = onConnect) { Text("Connect") } },
            ) else StatusCard(
                icon = { Icon(Icons.Outlined.WifiOff, null) },
                title = "Not connected",
                text = "Turn on WiFi on the camera, join its network (lytro.camera.…), then tap Connect.",
                actions = {
                    OutlinedButton(onClick = onWifiSettings) { Text("WiFi settings") }
                    Button(onClick = onConnect) { Text("Connect") }
                },
            )
        }
    }
}

@Composable
private fun StatusCard(
    icon: @Composable () -> Unit,
    title: String,
    text: String?,
    error: Boolean = false,
    actions: (@Composable () -> Unit)? = null,
) {
    Card(
        colors = if (error) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            if (text != null) {
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 36.dp, top = 4.dp))
            }
            if (actions != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { actions() }
            }
        }
    }
}

@Composable
private fun EmptyState(conn: ConnectionState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
    ) {
        Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(
            if (conn is ConnectionState.Connected) "The camera has no pictures" else "No pictures yet",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (conn is ConnectionState.Connected) "Pictures you take with the Lytro show up here."
            else "Once connected, the pictures on your Lytro appear here. Downloaded ones stay available without the camera.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ----------------------------------------------------------------------- tiles
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PictureTile(
    graph: AppGraph,
    item: PictureItem,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = rememberPictureImage(graph.library, item)
    val scale by animateFloatAsState(if (selected) 0.88f else 1f, label = "select")
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Select"),
    ) {
        Box(Modifier.fillMaxSize().scale(scale).clip(RoundedCornerShape(if (selected) 14.dp else 10.dp))) {
            if (image != null) {
                Image(image, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest))
            }
            StatusBadge(item, Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        if (selecting) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp)
                    .background(if (selected) MaterialTheme.colorScheme.surface else Color.Black.copy(alpha = 0.25f), CircleShape),
            )
        }
    }
}

@Composable
private fun StatusBadge(item: PictureItem, modifier: Modifier) {
    val scrim = Color.Black.copy(alpha = 0.45f)
    val badge = Modifier.size(26.dp).background(scrim, CircleShape)
    when {
        item.error != null -> Box(badge.then(modifier), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.ErrorOutline, "Problem", tint = Color.White, modifier = Modifier.size(18.dp))
        }
        item.downloading && !item.rawDone -> Box(badge.then(modifier), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { item.downloadProgress },
                modifier = Modifier.size(18.dp), strokeWidth = 2.5.dp, color = Color.White,
                trackColor = Color.White.copy(alpha = 0.3f),
            )
        }
        item.downloadQueued && !item.rawDone -> Box(badge.then(modifier), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Schedule, "Waiting to download", tint = Color.White, modifier = Modifier.size(16.dp))
        }
        !item.rawDone -> Box(badge.then(modifier), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.CloudDownload, "On the camera", tint = Color.White, modifier = Modifier.size(16.dp))
        }
        item.needsProcessing -> Box(badge.then(modifier), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

// ----------------------------------------------------------------------- batch
@Composable
private fun BatchCard(batch: BatchProgress, items: Map<String, PictureItem>, onStop: () -> Unit) {
    val current = items.values.firstOrNull { it.downloading }
    val fraction = ((batch.done + (current?.downloadProgress ?: 0f)) / batch.total.coerceAtLeast(1)).coerceIn(0f, 1f)
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.navigationBarsPadding().padding(16.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Downloading ${(batch.done + 1).coerceAtMost(batch.total)} of ${batch.total}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val eta = batch.etaSeconds?.let { "about ${formatDuration(it)} left" }
                    Text(
                        listOfNotNull(batch.currentName, eta).joinToString(" · ").ifEmpty { "Waiting for the camera…" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = onStop) { Text("Stop") }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                drawStopIndicator = {},
            )
        }
    }
}
