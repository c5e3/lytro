package app.refocus.ui.viewer

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined._3dRotation
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
import androidx.compose.material3.Slider
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
import app.refocus.export.StillContent
import app.refocus.ui.viewer.gl.ViewMode
import kotlin.math.roundToInt

/** Last used export options, for this session. */
private object ExportPrefs {
    var tab = 0
    var scale = 2
    var depth16 = false
    var kind = AnimationKind.FocusSweep
    var nearToFar = true
    var animScale = 2
    var seconds = 3
    var cycles = 1
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    imageWidth: Int,
    imageHeight: Int,
    currentView: ViewMode,
    hasDepth: Boolean,
    pinhole: Boolean,
    onStill: (StillContent, Int, Boolean) -> Unit,
    onAnimation: (AnimationKind, AnimationRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableIntStateOf(ExportPrefs.tab) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.navigationBarsPadding()) {
            Text("Export", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                listOf("Image" to Icons.Outlined.Image, "Animation" to Icons.Outlined.Animation)
                    .forEachIndexed { i, (label, icon) ->
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
                    else -> AnimationTab(imageWidth, hasDepth, pinhole, onAnimation)
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
    onStill: (StillContent, Int, Boolean) -> Unit,
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
    var scale by remember { mutableIntStateOf(ExportPrefs.scale) }
    var depth16 by remember { mutableStateOf(ExportPrefs.depth16) }

    Section("Content") {
        Segmented(StillContent.entries, content, { it.label }, enabled = { it == StillContent.Photo || hasDepth }) { content = it }
    }
    if (!hasDepth) Hint("Depth map and overlay are available once depth estimation has finished.")
    Section("Size") {
        Segmented(listOf(1, 2, 3, 4), scale, { "$it×" }) { scale = it; ExportPrefs.scale = it }
        Hint("${w * scale} × ${h * scale} px JPEG")
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
    Button(onClick = { onStill(content, scale, depth16) }, modifier = Modifier.fillMaxWidth()) {
        Text("Save to gallery")
    }
}

@Composable
private fun ColumnScope.AnimationTab(w: Int, hasDepth: Boolean, pinhole: Boolean, onAnimation: (AnimationKind, AnimationRequest) -> Unit) {
    var kind by remember { mutableStateOf(ExportPrefs.kind) }
    var nearToFar by remember { mutableStateOf(ExportPrefs.nearToFar) }
    var scale by remember { mutableIntStateOf(ExportPrefs.animScale) }
    var seconds by remember { mutableIntStateOf(ExportPrefs.seconds) }
    var cycles by remember { mutableIntStateOf(ExportPrefs.cycles) }
    val needsDepth = kind == AnimationKind.FocusSweep

    Column(Modifier.selectableGroup()) {
        AnimationOption(AnimationKind.FocusSweep, kind, Icons.Outlined.CenterFocusStrong, "Focus sweep",
            "Focus travels through the scene") { kind = it; ExportPrefs.kind = it }
        AnimationOption(AnimationKind.PerspectiveOrbit, kind, Icons.Outlined._3dRotation, "Perspective orbit",
            "The viewpoint circles around the lens for a 3D effect") { kind = it; ExportPrefs.kind = it }
    }
    AnimatedVisibility(kind == AnimationKind.FocusSweep) {
        Section("Direction") {
            Segmented(listOf(true, false), nearToFar, { if (it) "Near → far" else "Far → near" }) {
                nearToFar = it; ExportPrefs.nearToFar = it
            }
        }
    }
    if (needsDepth && !hasDepth) Hint("The focus sweep needs the depth map, which is still being estimated.")
    Hint(
        if (kind == AnimationKind.FocusSweep && pinhole) "Depth of field is set to pinhole, so everything stays sharp and the sweep won't show. Choose a shallower depth of field first."
        else if (kind == AnimationKind.FocusSweep) "Uses your current depth of field."
        else "Uses your current focus and depth of field (at most f/5.3, so the viewpoint can move)."
    )
    Section("Size") {
        Segmented(listOf(1, 2, 3), scale, { "$it×" }) { scale = it; ExportPrefs.animScale = it }
        Hint("about ${w * scale} px wide, MP4")
    }
    val orbit = kind == AnimationKind.PerspectiveOrbit
    StepSlider(
        label = if (orbit) "Length of one orbit" else "Length",
        value = seconds, range = 1..10, valueText = "$seconds s",
    ) { seconds = it; ExportPrefs.seconds = it }
    AnimatedVisibility(orbit) {
        StepSlider(
            label = "Orbits", value = cycles, range = 1..5,
            valueText = if (cycles == 1) "1 time" else "$cycles times",
        ) { cycles = it; ExportPrefs.cycles = it }
    }
    if (orbit) Hint("Video length: ${seconds * cycles} s")
    Button(
        onClick = { onAnimation(kind, AnimationRequest(scale, seconds, nearToFar, if (orbit) cycles else 1)) },
        enabled = !needsDepth || hasDepth,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Create video") }
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

/** A latching slider over whole numbers. */
@Composable
private fun StepSlider(label: String, value: Int, range: IntRange, valueText: String, onChange: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
        )
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
