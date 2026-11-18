package app.refocus.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.refocus.AppGraph
import app.refocus.BuildConfig
import app.refocus.camera.ConnectionState
import app.refocus.data.CameraAddress
import app.refocus.ui.PictureImages
import app.refocus.ui.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val address by graph.settings.address.collectAsState()
    val conn by graph.session.state.collectAsState()
    val items by graph.library.items.collectAsState()
    val scope = rememberCoroutineScope()
    var host by remember(address) { mutableStateOf(address.host) }
    var port by remember(address) { mutableStateOf(address.port.toString()) }
    var bytes by remember { mutableLongStateOf(0L) }
    var confirmClear by remember { mutableStateOf(false) }
    val local = items.values.count { it.rawDone }
    LaunchedEffect(items) { bytes = withContext(Dispatchers.IO) { graph.library.localBytes() } }

    val hostValid = host.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")) || host.matches(Regex("^[A-Za-z0-9.-]+$"))
    val portValue = port.toIntOrNull()?.takeIf { it in 1..65535 }
    fun save() {
        if (hostValid && portValue != null) scope.launch { graph.settings.setAddress(CameraAddress(host.trim(), portValue)) }
    }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            androidx.compose.material3.LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = { save(); onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 32.dp)) {
            item { Header("Camera") }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("IP address") },
                            singleLine = true,
                            isError = !hostValid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                            modifier = Modifier.weight(2f),
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter(Char::isDigit).take(5) },
                            label = { Text("Port") },
                            singleLine = true,
                            isError = portValue == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "The Lytro hotspot always uses 10.100.1.1:5678. The app connects automatically when it starts on this network.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        val changed = host.trim() != address.host || portValue != address.port
                        if (changed) TextButton(onClick = ::save, enabled = hostValid && portValue != null) { Text("Save") }
                        else if (!address.isDefault) TextButton(onClick = {
                            scope.launch { graph.settings.setAddress(CameraAddress()) }
                        }) { Text("Reset") }
                    }
                }
            }

            val c = conn
            if (c is ConnectionState.Connected) {
                item { Header("Connected camera") }
                item { Info(Icons.Outlined.CameraAlt, "Manufacturer", c.hardware.manufacturer) }
                item { Info(Icons.Outlined.Numbers, "Serial number", c.hardware.serial) }
                item { Info(Icons.Outlined.Memory, "Hardware", c.hardware.hardwareVersion) }
                item { Info(Icons.Outlined.SystemUpdate, "Firmware", c.hardware.firmware) }
            }

            item { Header("Storage") }
            item {
                Info(Icons.Outlined.SdStorage, "Pictures on this phone",
                    "$local ${if (local == 1) "picture" else "pictures"} · ${formatBytes(bytes)}")
            }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.DeleteSweep, null) },
                    headlineContent = { Text("Remove downloaded pictures") },
                    supportingContent = { Text("Frees the space. Pictures stay on the camera; thumbnails are kept.") },
                    modifier = Modifier.fillMaxWidth().then(
                        if (local > 0) Modifier.clickableItem { confirmClear = true } else Modifier
                    ),
                )
            }
            item {
                Info(Icons.Outlined.Folder, "Exports",
                    "Images in Pictures/Refocus, videos in Movies/Refocus, original RAW files in Download/Refocus. " +
                        "Downloaded pictures are kept private and never show up in the gallery.")
            }

            item { Header("About") }
            item { Info(Icons.Outlined.Info, "Refocus ${BuildConfig.VERSION_NAME}", "A WiFi Lytro Viewer for the first-generation Lytro camera") }
        }
    }

    if (confirmClear) {
        val gone = items.values.count { it.rawDone && !it.onCamera }
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Outlined.DeleteSweep, null) },
            title = { Text("Remove all downloaded pictures?") },
            text = {
                Text(
                    "This frees ${formatBytes(bytes)}." + if (gone > 0) " $gone of them are no longer on the camera and will be gone for good." else
                        " Everything stays on the camera and can be downloaded again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    graph.session.stopDownloads()
                    graph.cache.clear()
                    PictureImages.clear()
                    items.values.filter { it.rawDone || it.rawBytes > 0 }.forEach { graph.library.removeLocal(it.id) }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private fun Modifier.clickableItem(onClick: () -> Unit) = this.clickable(onClick = onClick)

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun Info(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String) {
    ListItem(
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
    )
}
