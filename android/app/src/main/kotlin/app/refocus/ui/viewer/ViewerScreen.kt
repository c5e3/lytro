package app.refocus.ui.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.opengl.GLSurfaceView
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined._3dRotation
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.refocus.AppGraph
import app.refocus.camera.CameraSession
import app.refocus.camera.ConnectionState
import app.refocus.core.lf.LightField
import app.refocus.core.lf.RenderParams
import app.refocus.data.PictureItem
import app.refocus.data.ProcessingStage
import app.refocus.export.Exported
import app.refocus.ui.formatDateTime
import app.refocus.ui.formatDuration
import app.refocus.ui.rememberPictureImage
import app.refocus.ui.viewer.gl.RefocusRenderer
import app.refocus.ui.viewer.gl.ViewMode
import app.refocus.ui.viewer.gl.ViewTransform
import kotlinx.coroutines.launch
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(graph: AppGraph, id: String, onBack: () -> Unit) {
    val vm: ViewerViewModel = viewModel(key = id) { ViewerViewModel(graph, id) }
    val item by vm.item.collectAsState()
    val conn by graph.session.state.collectAsState()
    val speed by graph.session.downloadSpeed.collectAsState()
    val cameraWifi by graph.network.cameraNetwork.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val lf = vm.lightField
    val current = item

    var showExport by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showViews by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    val focusAnim = remember { Animatable(0f) }
    fun animateFocus(target: Float) {
        scope.launch {
            focusAnim.snapTo(vm.slope)
            focusAnim.animateTo(target, tween(380, easing = FastOutSlowInEasing)) { vm.slope = value }
        }
    }

    LaunchedEffect(vm) { vm.focusRequests.collect { animateFocus(it) } }
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is ViewerEvent.Message -> snackbar.showSnackbar(e.text)
                is ViewerEvent.Saved -> {
                    val r = snackbar.showSnackbar(e.text, actionLabel = "Share", duration = SnackbarDuration.Long)
                    if (r == SnackbarResult.ActionPerformed) share(context, e.files)
                }
            }
        }
    }
    // the picture was removed entirely
    LaunchedEffect(current == null) { if (current == null) onBack() }
    if (current == null) return
    val hasDepth = lf?.depth != null && current.hasDepth
    if (vm.mode == InteractionMode.Perspective) {
        TiltEffect(vm.tilt) { x, y -> vm.setCenter(x * vm.maxCenter, y * vm.maxCenter) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(current.name)
                        Text(formatDateTime(current.taken), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (lf != null) {
                        Box {
                            IconButton(onClick = { showViews = true }) { Icon(Icons.Outlined.Layers, "View") }
                            DropdownMenu(expanded = showViews, onDismissRequest = { showViews = false }) {
                                listOf(ViewMode.Photo to "Photo", ViewMode.Depth to "Depth map", ViewMode.Overlay to "Depth overlay")
                                    .forEach { (m, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            enabled = m == ViewMode.Photo || hasDepth,
                                            leadingIcon = {
                                                if (vm.viewMode == m) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp))
                                            },
                                            onClick = { vm.viewMode = m; showViews = false },
                                        )
                                    }
                            }
                        }
                    }
                    IconButton(onClick = { showInfo = true }) { Icon(Icons.Outlined.Info, "Details") }
                    IconButton(onClick = { showExport = true }, enabled = lf != null && vm.exportProgress == null) {
                        Icon(Icons.Outlined.IosShare, "Export")
                    }
                    if (current.rawDone || current.rawBytes > 0) {
                        Box {
                            IconButton(onClick = { showMenu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                if (current.rawDone) {
                                    DropdownMenuItem(
                                        text = { Text("Save original RAW") },
                                        leadingIcon = { Icon(Icons.Outlined.Inventory2, null) },
                                        onClick = { showMenu = false; vm.exportOriginal() },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Remove from phone") },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                    onClick = { showMenu = false; confirmRemove = true },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (lf != null) {
                    RefocusImage(vm, lf, MaterialTheme.colorScheme.surface) { tap ->
                        val s = vm.focusSlopeAt(tap.first, tap.second)
                        if (s != null) animateFocus(s) else {
                            vm.pendingFocus = tap
                            scope.launch { snackbar.showSnackbar("Still estimating depth. It will focus there as soon as it's ready.") }
                        }
                    }
                } else {
                    StatusPane(
                        graph = graph, item = current, conn = conn, cameraWifi = cameraWifi != null, speed = speed,
                        onConnect = { graph.session.connect() },
                        onWifi = { context.startActivity(Intent(Settings.Panel.ACTION_WIFI)) },
                        onRetry = { vm.retry() },
                    )
                }

                // depth still being estimated
                Overlay(
                    visible = lf != null && !current.hasDepth,
                    enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                ) {
                    Pill {
                        CircularProgressIndicator(
                            progress = { if (current.processing == ProcessingStage.Depth) current.processingProgress else 0f },
                            modifier = Modifier.size(16.dp), strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (current.processing == ProcessingStage.Depth) "Estimating depth · ${(current.processingProgress * 100).toInt()} %"
                            else "Waiting to estimate depth",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                // export running
                Overlay(
                    visible = vm.exportProgress != null,
                    enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                ) {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 4.dp) {
                        Column(Modifier.widthIn(min = 240.dp).padding(horizontal = 20.dp, vertical = 14.dp)) {
                            val p = vm.exportProgress ?: 0f
                            Text("Exporting · ${(p * 100).toInt()} %", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                        }
                    }
                }
            }
            AnimatedVisibility(lf != null, enter = slideInVertically { it } + fadeIn(), exit = fadeOut()) {
                Controls(vm, hasDepth)
            }
        }
    }

    if (showExport && lf != null) {
        ExportSheet(
            imageWidth = lf.width,
            imageHeight = lf.height,
            currentView = vm.viewMode,
            hasDepth = hasDepth,
            pinhole = RenderParams.fNumber(LightField.RADIUS * (1f - vm.dof)) == null,
            onStill = { c, s, d -> showExport = false; vm.exportStill(c, s, d) },
            onAnimation = { k, r -> showExport = false; vm.exportAnimation(k, r) },
            onDismiss = { showExport = false },
        )
    }
    if (showInfo) {
        val bytes = remember(current.rawDone, current.hasViews, current.hasDepth) {
            graph.library.dir(id).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        InfoSheet(current, vm.metadata, bytes) { showInfo = false }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            icon = { Icon(Icons.Outlined.Delete, null) },
            title = { Text("Remove from this phone?") },
            text = {
                Text(
                    if (current.onCamera) "The downloaded data is deleted. The picture stays on the camera and can be downloaded again."
                    else "This picture is no longer on the camera, so it will be gone for good. Exports in your gallery are not affected."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    graph.session.cancelDownload(id)
                    graph.cache.remove(id)
                    graph.library.removeLocal(id)
                    if (!current.onCamera) onBack()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

// --------------------------------------------------------------------- image
@Composable
private fun RefocusImage(vm: ViewerViewModel, lf: LightField, background: Color, onTap: (Pair<Float, Float>) -> Unit) {
    val renderer = remember { RefocusRenderer() }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    // the image always fits the view (no zoom: the native resolution is only ~325 px)
    val transform = remember { ViewTransform() }
    var ring by remember { mutableStateOf<Offset?>(null) }
    val ringAnim = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, glView) {
        val v = glView ?: return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> v.onResume()
                Lifecycle.Event.ON_PAUSE -> v.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun showRing(at: Offset) {
        ring = at
        scope.launch {
            ringAnim.snapTo(0f)
            ringAnim.animateTo(1f, tween(650))
            ring = null
        }
    }

    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp))) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    preserveEGLContextOnPause = true
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    glView = this
                }
            },
            update = { v ->
                val bg = background.toArgb()
                renderer.background = floatArrayOf(
                    ((bg shr 16) and 0xFF) / 255f, ((bg shr 8) and 0xFF) / 255f, (bg and 0xFF) / 255f,
                )
                renderer.lightField = lf
                renderer.params = vm.renderParams
                renderer.mode = vm.viewMode
                renderer.depthVersion = vm.depthVersion
                renderer.transform = transform
                v.requestRender()
            },
            modifier = Modifier.fillMaxSize(),
        )

        // gestures: a layer above the GL surface. Focus mode: tap to focus.
        // Perspective mode: a one-finger drag moves the viewpoint (taps do nothing).
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(lf, vm.mode) {
                    if (vm.mode != InteractionMode.Focus) return@pointerInput
                    detectTapGestures(onTap = { o ->
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        transform.toImage(o.x, o.y, w, h, lf.width, lf.height)?.let {
                            showRing(o)
                            onTap(it)
                        }
                    })
                }
                .pointerInput(lf, vm.mode, vm.tilt) {
                    if (vm.mode != InteractionMode.Perspective || vm.tilt) return@pointerInput
                    detectDragGestures { change, drag ->
                        change.consume()
                        // a drag across ~80 % of the view sweeps the full range
                        val k = 2f * vm.maxCenter.coerceAtLeast(0.01f) / (min(size.width, size.height) * 0.8f)
                        vm.setCenter(vm.centerU - drag.x * k, vm.centerV - drag.y * k)
                    }
                },
        )

        val r = ring
        if (r != null) {
            val ringColor = Color.White
            Canvas(Modifier.fillMaxSize()) {
                val p = ringAnim.value
                val radius = 46.dp.toPx() * (1.25f - 0.35f * p)
                val alpha = if (p < 0.7f) 1f else (1f - (p - 0.7f) / 0.3f)
                drawCircle(Color.Black.copy(alpha = 0.25f * alpha), radius, r, style = Stroke(4.dp.toPx()))
                drawCircle(ringColor.copy(alpha = alpha), radius, r, style = Stroke(2.dp.toPx()))
                drawCircle(ringColor.copy(alpha = alpha), 3.dp.toPx(), r)
            }
        }
    }
}

// ------------------------------------------------------------------ controls
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Controls(vm: ViewerViewModel, hasDepth: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    SegmentedButton(
                        selected = vm.mode == InteractionMode.Focus,
                        onClick = { vm.mode = InteractionMode.Focus },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        icon = { SegmentedButtonDefaults.Icon(vm.mode == InteractionMode.Focus) { Icon(Icons.Outlined.CenterFocusWeak, null, Modifier.size(18.dp)) } },
                        label = { Text("Focus") },
                    )
                    SegmentedButton(
                        selected = vm.mode == InteractionMode.Perspective,
                        onClick = {
                            vm.mode = InteractionMode.Perspective
                            vm.setCenter(vm.centerU, vm.centerV)
                        },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        icon = { SegmentedButtonDefaults.Icon(vm.mode == InteractionMode.Perspective) { Icon(Icons.Outlined._3dRotation, null, Modifier.size(18.dp)) } },
                        label = { Text("Perspective") },
                    )
                }
                AnimatedVisibility(vm.mode == InteractionMode.Perspective) {
                    Row {
                        Spacer(Modifier.width(8.dp))
                        FilledIconToggleButton(checked = vm.tilt, onCheckedChange = { vm.tilt = it; if (!it) vm.recenter() }) {
                            Icon(Icons.Outlined.ScreenRotation, if (vm.tilt) "Tilt control on" else "Tilt control off")
                        }
                    }
                }
            }
            Text(
                when {
                    vm.mode == InteractionMode.Perspective && vm.tilt -> "Tilt the phone to look around the subject"
                    vm.mode == InteractionMode.Perspective -> "Drag the picture to change the viewpoint"
                    hasDepth -> "Tap the picture to focus there"
                    else -> "Tap to focus works once depth estimation finishes"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
            )

            val range = vm.focusRange
            val t = (vm.slope - range.start) / (range.endInclusive - range.start)
            val where = when {
                t < 0.33f -> "far"
                t > 0.67f -> "near"
                else -> "middle"
            }
            LabeledSlider(
                label = "Focus",
                valueText = where,
                value = vm.slope.coerceIn(range),
                range = range,
                startLabel = "Far",
                endLabel = "Near",
                onChange = { vm.slope = it },
            )
            val f = RenderParams.fNumber(vm.aperture)
            LabeledSlider(
                label = "Depth of field",
                valueText = f?.let { "f/%.1f".format(it) } ?: "everything sharp",
                value = vm.dof,
                range = 0f..1f,
                startLabel = if (vm.mode == InteractionMode.Perspective) "f/%.1f".format(RenderParams.fNumber(ViewerViewModel.PERSPECTIVE_MAX_APERTURE)) else "f/2",
                endLabel = "Pinhole",
                onChange = {
                    vm.dof = it
                    if (vm.mode == InteractionMode.Perspective) vm.setCenter(vm.centerU, vm.centerV)
                },
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    startLabel: String,
    endLabel: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.height(36.dp))
        Row {
            Text(startLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            Text(endLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** AnimatedVisibility without a layout-scope receiver (for overlays in a Box). */
@Composable
private fun Overlay(
    visible: Boolean,
    enter: androidx.compose.animation.EnterTransition,
    exit: androidx.compose.animation.ExitTransition,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) = AnimatedVisibility(visible = visible, modifier = modifier, enter = enter, exit = exit) { content() }

@Composable
private fun Pill(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f), shadowElevation = 2.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) { content() }
    }
}

// -------------------------------------------------------------------- status
@Composable
private fun StatusPane(
    graph: AppGraph,
    item: PictureItem,
    conn: ConnectionState,
    cameraWifi: Boolean,
    speed: Double,
    onConnect: () -> Unit,
    onWifi: () -> Unit,
    onRetry: () -> Unit,
) {
    val image = rememberPictureImage(graph.library, item)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(
                image, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(24.dp).clip(RoundedCornerShape(24.dp)).blur(20.dp).alpha(0.45f),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            when {
                item.error != null -> {
                    Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                    Text("Something went wrong", style = MaterialTheme.typography.titleLarge)
                    Body(item.error)
                    if (item.rawDone || conn.isLive) Button(onClick = onRetry) { Text("Try again") }
                }
                !item.rawDone && !conn.isLive -> {
                    Icon(Icons.Outlined.CloudDownload, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(if (item.onCamera) "This picture is on the camera" else "Not available", style = MaterialTheme.typography.titleLarge)
                    Body(
                        if (!item.onCamera) "It's no longer on the camera and wasn't downloaded."
                        else if (cameraWifi) "Connect to download it (16 MB, about 25 seconds)."
                        else "Join the camera's WiFi and connect to download it (16 MB, about 25 seconds)."
                    )
                    if (item.onCamera) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!cameraWifi) OutlinedButton(onClick = onWifi) { Text("WiFi settings") }
                        Button(onClick = onConnect, enabled = conn !is ConnectionState.Connecting) {
                            Text(if (conn is ConnectionState.Connecting) "Connecting…" else "Connect")
                        }
                    }
                }
                !item.rawDone -> {
                    val active = item.downloading && item.rawBytes > 0
                    Box(contentAlignment = Alignment.Center) {
                        if (active) CircularProgressIndicator(progress = { item.downloadProgress }, modifier = Modifier.size(64.dp), strokeWidth = 5.dp)
                        else CircularProgressIndicator(Modifier.size(64.dp), strokeWidth = 5.dp)
                        if (active) Text("${(item.downloadProgress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    }
                    Text(if (active) "Downloading" else "Waiting for the camera", style = MaterialTheme.typography.titleLarge)
                    val total = if (item.rawTotal > 0) item.rawTotal else CameraSession.RAW_SIZE
                    val eta = if (active && speed > 1000) ((total - item.rawBytes) / speed).toLong() else null
                    Body(
                        if (conn is ConnectionState.Reconnecting) "Connection interrupted, retrying…"
                        else eta?.let { "About ${formatDuration(it)} left" } ?: "The 16 MB light field starts in a moment"
                    )
                }
                else -> {
                    val (title, progress) = when (item.processing) {
                        ProcessingStage.Decoding -> "Decoding the light field" to item.processingProgress
                        ProcessingStage.Depth -> "Estimating depth" to item.processingProgress
                        ProcessingStage.Queued -> "Waiting to be processed" to null
                        null -> (if (item.hasViews) "Opening" else "Preparing") to null
                    }
                    if (progress != null) CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(64.dp), strokeWidth = 5.dp)
                    else CircularProgressIndicator(Modifier.size(64.dp), strokeWidth = 5.dp)
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Body("Downloaded. Turning the raw sensor image into a refocusable picture.")
                }
            }
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

private fun share(context: Context, files: List<Exported>) {
    val uris = ArrayList<Uri>(files.map { it.uri })
    val mime = files.map { it.mime }.distinct().singleOrNull() ?: "*/*"
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, null))
}
