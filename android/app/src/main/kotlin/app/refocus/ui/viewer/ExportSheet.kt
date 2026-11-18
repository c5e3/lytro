package app.refocus.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined._3dRotation
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.refocus.core.lf.AnimationKind
import app.refocus.export.AnimationFormat
import app.refocus.export.StillContent
import app.refocus.export.StillFormat
import app.refocus.ui.viewer.gl.ViewMode

/** Last used export options, for this session. */
private object ExportPrefs {
    var tab = 0
    var format = StillFormat.Jpeg
    var scale = 2
    var depth16 = false
    var kind = AnimationKind.FocusSweep
    var animFormat = AnimationFormat.Mp4
    var animScale = 2
    var seconds = 3
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    imageWidth: Int,
    imageHeight: Int,
    currentView: ViewMode,
    hasDepth: Boolean,
    hasRaw: Boolean,
    onStill: (StillContent, StillFormat, Int, Boolean) -> Unit,
    onAnimation: (AnimationKind, AnimationRequest) -> Unit,
    onOriginal: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableIntStateOf(ExportPrefs.tab) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.navigationBarsPadding()) {
            Text("Export", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                listOf(
                    "Image" to Icons.Outlined.Image,
                    "Animation" to Icons.Outlined.Animation,
                    "Original" to Icons.Outlined.Inventory2,
                ).forEachIndexed { i, (label, icon) ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i; ExportPrefs.tab = i },
                        text = { Text(label) },
                        icon = { Icon(icon, null) },
                    )
                }
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (tab) {
                    0 -> StillTab(imageWidth, imageHeight, currentView, hasDepth, onStill)
                    1 -> AnimationTab(imageWidth, hasDepth, onAnimation)
                    else -> OriginalTab(hasRaw, onOriginal)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.StillTab(
    w: Int,
    h: Int,
    currentView: ViewMode,
    hasDepth: Boolean,
    onStill: (StillContent, StillFormat, Int, Boolean) -> Unit,
) {
    var content by remember {
        mutableStateOf(
            when (currentView) {
                ViewMode.Photo -> StillContent.Photo
                ViewMode.Depth -> if (hasDepth) StillContent.Depth else StillContent.Photo
                ViewMode.Overlay -> if (hasDepth) StillContent.Overlay else StillContent.Photo
            }
        )
    }
    var format by remember { mutableStateOf(ExportPrefs.format) }
    var scale by remember { mutableIntStateOf(ExportPrefs.scale) }
    var depth16 by remember { mutableStateOf(ExportPrefs.depth16) }

    Section("Content") {
        Segmented(StillContent.entries, content, { it.label }, enabled = { it == StillContent.Photo || hasDepth }) { content = it }
    }
    if (!hasDepth) Hint("Depth map and overlay are available once depth estimation has finished.")
    Section("Format") { Segmented(StillFormat.entries, format, { it.label }) { format = it; ExportPrefs.format = it } }
    Section("Size") {
        Segmented(listOf(1, 2, 3, 4), scale, { "$it×" }) { scale = it; ExportPrefs.scale = it }
        Hint("${w * scale} × ${h * scale} px")
    }
    if (content == StillContent.Depth) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Also save depth values", style = MaterialTheme.typography.bodyLarge)
                Text("16-bit greyscale PNG, near = bright", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = depth16, onCheckedChange = { depth16 = it; ExportPrefs.depth16 = it })
        }
    }
    Button(onClick = { onStill(content, format, scale, depth16) }, modifier = Modifier.fillMaxWidth()) {
        Text("Save to gallery")
    }
}

@Composable
private fun ColumnScope.AnimationTab(w: Int, hasDepth: Boolean, onAnimation: (AnimationKind, AnimationRequest) -> Unit) {
    var kind by remember { mutableStateOf(ExportPrefs.kind) }
    var format by remember { mutableStateOf(ExportPrefs.animFormat) }
    var scale by remember { mutableIntStateOf(ExportPrefs.animScale) }
    var seconds by remember { mutableIntStateOf(ExportPrefs.seconds) }
    val needsDepth = kind != AnimationKind.PerspectiveOrbit

    Column(Modifier.selectableGroup()) {
        AnimationOption(AnimationKind.FocusSweep, kind, Icons.Outlined.CenterFocusStrong, "Focus sweep",
            "Focus travels from near to far and back") { kind = it; ExportPrefs.kind = it }
        AnimationOption(AnimationKind.PerspectiveOrbit, kind, Icons.Outlined._3dRotation, "Perspective orbit",
            "The viewpoint circles around the lens for a 3D effect") { kind = it; ExportPrefs.kind = it }
        AnimationOption(AnimationKind.TapToTap, kind, Icons.Outlined.TouchApp, "Tap-to-tap focus",
            "Focus pulls between two points you choose") { kind = it; ExportPrefs.kind = it }
    }
    if (needsDepth && !hasDepth) Hint("This animation needs the depth map, which is still being estimated.")
    Section("Format") {
        Segmented(AnimationFormat.entries, format, { it.label }) {
            format = it; ExportPrefs.animFormat = it
            if (it == AnimationFormat.Gif && scale > 2) { scale = 2; ExportPrefs.animScale = 2 }
        }
        if (format == AnimationFormat.Gif) Hint("GIFs are large and limited to 256 colours. MP4 looks better and is smaller.")
    }
    Section("Size") {
        val sizes = if (format == AnimationFormat.Gif) listOf(1, 2) else listOf(1, 2, 3)
        Segmented(sizes, scale, { "$it×" }) { scale = it; ExportPrefs.animScale = it }
        Hint("about ${w * scale} px wide")
    }
    Section("Length") { Segmented(listOf(2, 3, 5), seconds, { "$it s" }) { seconds = it; ExportPrefs.seconds = it } }
    Button(
        onClick = { onAnimation(kind, AnimationRequest(format, scale, seconds)) },
        enabled = !needsDepth || hasDepth,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (kind == AnimationKind.TapToTap) "Choose the two points" else "Create animation") }
}

@Composable
private fun ColumnScope.OriginalTab(hasRaw: Boolean, onOriginal: () -> Unit) {
    Text(
        "The unprocessed 16 MB light-field RAW and its metadata (.TXT), exactly as stored on the camera. " +
            "They're saved to Download/Refocus, not to the gallery.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onOriginal, enabled = hasRaw, modifier = Modifier.fillMaxWidth()) { Text("Save RAW + metadata") }
}

@Composable
private fun AnimationOption(
    value: AnimationKind,
    selected: AnimationKind,
    icon: ImageVector,
    title: String,
    text: String,
    onSelect: (AnimationKind) -> Unit,
) {
    ListItem(
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
        trailingContent = { RadioButton(selected = value == selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) }),
    )
}

@Composable
private fun Section(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    enabled: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected,
                onClick = { onSelect(o) },
                enabled = enabled(o),
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(label(o)) },
            )
        }
    }
}
