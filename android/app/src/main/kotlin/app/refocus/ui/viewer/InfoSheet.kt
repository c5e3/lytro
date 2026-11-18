package app.refocus.ui.viewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Iso
import androidx.compose.material.icons.outlined.Lens
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ShutterSpeed
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.refocus.core.lf.LfMetadata
import app.refocus.data.PictureItem
import app.refocus.ui.formatBytes
import app.refocus.ui.formatDateTime
import app.refocus.ui.formatShutter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(item: PictureItem, meta: LfMetadata?, localBytes: Long, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                "Details",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Row(Icons.Outlined.Schedule, "Taken", formatDateTime(item.taken))
            Row(Icons.Outlined.Folder, "File", "${item.name} · ${"%03d".format(item.picture.folderNum)}${item.picture.folderSuffix}")
            if (meta != null) {
                val exposure = listOfNotNull(
                    meta.shutterSeconds?.let(::formatShutter),
                    meta.fNumber?.let { "f/%.1f".format(it) },
                ).joinToString(" · ")
                if (exposure.isNotEmpty()) Row(Icons.Outlined.ShutterSpeed, "Exposure", exposure)
                meta.iso?.let { Row(Icons.Outlined.Iso, "ISO", "$it") }
                meta.focalLengthMm?.let { Row(Icons.Outlined.ZoomIn, "Focal length", "%.1f mm".format(it)) }
                val camera = listOfNotNull(meta.make, meta.model).joinToString(" ")
                if (camera.isNotEmpty()) Row(Icons.Outlined.CameraAlt, "Camera", camera + (meta.firmware?.let { " · firmware $it" } ?: ""))
                meta.batteryPercent?.let { Row(Icons.Outlined.BatteryStd, "Camera battery", "%.0f %%".format(it)) }
            } else {
                Row(Icons.Outlined.Lens, "Capture details", "Available once the picture is downloaded")
            }
            Row(
                Icons.Outlined.PhoneAndroid, "On this phone",
                when {
                    item.ready -> "Downloaded and processed · ${formatBytes(localBytes)}"
                    item.rawDone -> "Downloaded · processing"
                    item.rawBytes > 0 -> "Partly downloaded (${(item.downloadProgress * 100).toInt()} %)"
                    else -> "Not downloaded"
                } + if (!item.onCamera) " · no longer on the camera" else "",
            )
        }
    }
}

@Composable
private fun Row(icon: ImageVector, label: String, value: String) {
    ListItem(
        leadingContent = { Icon(icon, null) },
        overlineContent = { Text(label) },
        headlineContent = { Text(value) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
