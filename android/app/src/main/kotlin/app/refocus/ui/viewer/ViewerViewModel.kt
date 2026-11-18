package app.refocus.ui.viewer

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.refocus.AppGraph
import app.refocus.core.lf.AnimationKind
import app.refocus.core.lf.LfMetadata
import app.refocus.core.lf.LightField
import app.refocus.core.lf.LightFieldStore
import app.refocus.core.lf.RenderParams
import app.refocus.data.PictureItem
import app.refocus.export.AnimationFormat
import app.refocus.export.Exported
import app.refocus.export.StillContent
import app.refocus.export.StillFormat
import app.refocus.ui.viewer.gl.ViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.min

enum class InteractionMode { Focus, Perspective }

/** Choosing the two focus points of a tap-to-tap animation. */
data class PickState(val first: Float? = null, val firstAt: Pair<Float, Float>? = null)

data class AnimationRequest(val format: AnimationFormat, val scale: Int, val seconds: Int)

sealed interface ViewerEvent {
    data class Message(val text: String) : ViewerEvent
    data class Saved(val text: String, val files: List<Exported>) : ViewerEvent
}

class ViewerViewModel(private val graph: AppGraph, val id: String) : ViewModel() {

    val item: StateFlow<PictureItem?> = graph.library.items.map { it[id] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, graph.library[id])

    var lightField by mutableStateOf<LightField?>(null)
        private set
    var depthVersion by mutableIntStateOf(0)
        private set
    var metadata by mutableStateOf<LfMetadata?>(null)
        private set

    private val saved = remembered[id]
    var slope by mutableFloatStateOf(saved?.slope ?: 0f)
    /** 0 = shallow (the real f/2 lens), 1 = deep (pinhole). */
    var dof by mutableFloatStateOf(saved?.dof ?: 0f)
    /** Slider range: the whole possible range until the scene's depth is known. */
    var focusRange by mutableStateOf(LightField.SLOPE_MIN..LightField.SLOPE_MAX)
        private set
    var mode by mutableStateOf(InteractionMode.Focus)
    var viewMode by mutableStateOf(saved?.viewMode ?: ViewMode.Photo)
    var centerU by mutableFloatStateOf(0f)
        private set
    var centerV by mutableFloatStateOf(0f)
        private set
    var tilt by mutableStateOf(false)

    /** Export progress (0..1), null when idle. */
    var exportProgress by mutableStateOf<Float?>(null)
        private set
    var pick by mutableStateOf<PickState?>(null)
        private set
    private var pendingAnimation: AnimationRequest? = null
    /** A tap that arrived before the depth map: focus there once it's ready. */
    var pendingFocus: Pair<Float, Float>? = null

    private val _events = Channel<ViewerEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var loading: Job? = null
    private var depthSignalled = false
    private var priorityRequested = false

    /** Slopes to animate the focus to (the screen animates the slider). */
    private val _focusRequests = Channel<Float>(Channel.CONFLATED)
    val focusRequests = _focusRequests.receiveAsFlow()


    val aperture: Float
        get() {
            val a = LightField.RADIUS * (1f - dof)
            return if (mode == InteractionMode.Perspective) min(a, PERSPECTIVE_MAX_APERTURE) else a
        }

    val maxCenter: Float get() = RenderParams.maxCenterOffset(aperture)

    val renderParams: RenderParams
        get() = if (mode == InteractionMode.Perspective) RenderParams(slope, aperture, centerU, centerV)
        else RenderParams(slope, aperture)

    init {
        graph.processor.focus(id)
        viewModelScope.launch {
            combine(item, graph.session.state) { it, conn -> it to conn }.collect { (it, conn) ->
                if (it == null) return@collect
                // opening a picture that's only on the camera downloads it before
                // anything else, even when it's already queued by "download all"
                if (!conn.isLive) {
                    priorityRequested = false
                } else if (!it.rawDone && it.onCamera && it.error == null && !priorityRequested) {
                    priorityRequested = true
                    graph.session.requestDownload(id)
                }
                if (it.hasMeta && metadata == null) loadMetadata()
                if (it.hasViews && lightField == null && loading == null) loadLightField()
                val lf = lightField
                if (it.hasDepth && lf != null && !depthSignalled) {
                    // the processor may have filled in the depth of this very instance
                    if (lf.depth == null) loadDepth(lf) else depthReady(lf)
                }
                if (!it.rawDone && lightField != null) {
                    // removed from the phone meanwhile
                    lightField = null
                    loading = null
                    depthSignalled = false
                }
            }
        }
    }

    private fun loadMetadata() = viewModelScope.launch {
        metadata = withContext(Dispatchers.IO) {
            runCatching { LfMetadata.parse(graph.library.metaFile(id).readBytes()) }.getOrNull()
        }
    }

    private fun loadLightField() {
        loading = viewModelScope.launch {
            val lf = try {
                graph.cache.load(id)
            } catch (e: OutOfMemoryError) {
                graph.cache.clear()
                null
            }
            if (lf == null) {
                loading = null
                return@launch
            }
            lightField = lf
            if (lf.depth != null && item.value?.hasDepth == true) depthReady(lf)
        }
    }

    private fun loadDepth(lf: LightField) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            lf.depth != null || LightFieldStore.loadDepth(lf, graph.library.depthFile(id))
        }
        if (ok) depthReady(lf)
    }

    private fun depthReady(lf: LightField) {
        if (depthSignalled) return
        depthSignalled = true
        depthVersion++
        lf.depthRange?.let { (lo, hi) ->
            // the scene's depth plus a margin, so the slider can still defocus everything
            val margin = maxOf((hi - lo) * 0.4f, 0.25f)
            focusRange = maxOf(lo - margin, LightField.SLOPE_MIN)..minOf(hi + margin, LightField.SLOPE_MAX)
            slope = slope.coerceIn(focusRange)
        }
        pendingFocus?.let { (x, y) ->
            pendingFocus = null
            lf.focusSlopeAt(x, y)?.let { _focusRequests.trySend(it) }
        }
    }

    fun focusSlopeAt(x: Float, y: Float): Float? = lightField?.focusSlopeAt(x, y)

    fun retry() {
        val it = item.value ?: return
        graph.library.update(id) { i -> i.copy(error = null) }
        if (it.rawDone) graph.processor.kick() else graph.session.requestDownload(id)
    }

    // ------------------------------------------------------------ perspective
    fun setCenter(u: Float, v: Float) {
        val m = maxCenter
        val d = hypot(u, v)
        val s = if (d > m && d > 0) m / d else 1f
        centerU = u * s
        centerV = v * s
    }

    fun recenter() = setCenter(0f, 0f)

    // ---------------------------------------------------------------- export
    fun exportStill(content: StillContent, format: StillFormat, scale: Int, depth16: Boolean) {
        val lf = lightField ?: return
        val pic = item.value ?: return
        val params = renderParams
        runExport {
            val files = graph.exporter.exportStill(pic, lf, params, content, format, scale, depth16)
            ViewerEvent.Saved(if (files.size > 1) "Saved ${files.size} images to Pictures/Refocus" else "Saved to Pictures/Refocus", files)
        }
    }

    fun exportAnimation(kind: AnimationKind, request: AnimationRequest) {
        if (kind == AnimationKind.TapToTap) {
            pendingAnimation = request
            pick = PickState()
            return
        }
        startAnimation(kind, request, null, null)
    }

    private fun startAnimation(kind: AnimationKind, request: AnimationRequest, a: Float?, b: Float?) {
        val lf = lightField ?: return
        val pic = item.value ?: return
        val params = renderParams
        runExport { progress ->
            val file = graph.exporter.exportAnimation(pic, lf, kind, params, a, b, request.format, request.scale, request.seconds, progress)
            val where = if (request.format == AnimationFormat.Mp4) "Movies/Refocus" else "Pictures/Refocus"
            ViewerEvent.Saved("Saved to $where", listOf(file))
        }
    }

    /** A tap while choosing tap-to-tap points. Returns true when it was consumed. */
    fun pickPoint(x: Float, y: Float): Boolean {
        val p = pick ?: return false
        val lf = lightField ?: return true
        if (lf.depth == null) {
            _events.trySend(ViewerEvent.Message("Still estimating depth. Try again in a moment."))
            return true
        }
        val s = lf.focusSlopeAt(x, y) ?: return true
        if (p.first == null) {
            pick = PickState(s, x to y)
            _focusRequests.trySend(s)
        } else {
            pick = null
            val req = pendingAnimation ?: return true
            pendingAnimation = null
            _focusRequests.trySend(s)
            startAnimation(AnimationKind.TapToTap, req, p.first, s)
        }
        return true
    }

    fun cancelPick() {
        pick = null
        pendingAnimation = null
    }

    fun exportOriginal() {
        val pic = item.value ?: return
        runExport {
            val files = graph.exporter.exportOriginal(pic)
            ViewerEvent.Saved("Saved RAW and metadata to Download/Refocus", files)
        }
    }

    private fun runExport(block: suspend (progress: (Float) -> Unit) -> ViewerEvent) {
        if (exportProgress != null) return
        exportProgress = 0f
        // the app scope: an export finishes even if the viewer is closed
        graph.scope.launch {
            val event = try {
                block { p -> graph.scope.launch { exportProgress = p } }
            } catch (e: OutOfMemoryError) {
                ViewerEvent.Message("Not enough memory for this export. Try a smaller size.")
            } catch (e: Exception) {
                ViewerEvent.Message("Export failed: ${e.message}")
            }
            exportProgress = null
            _events.trySend(event)
        }
    }

    fun shareUris(files: List<Exported>): Pair<List<Uri>, String> =
        files.map { it.uri } to (files.map { it.mime }.distinct().singleOrNull() ?: "*/*")

    override fun onCleared() {
        remembered[id] = Remembered(slope, dof, viewMode)
        graph.processor.focus(null)
    }

    private data class Remembered(val slope: Float, val dof: Float, val viewMode: ViewMode)

    companion object {
        /** Aperture limit in perspective mode, so the viewpoint has room to move. */
        const val PERSPECTIVE_MAX_APERTURE = 1.5f

        /** Per-picture focus and depth of field, kept for the session (like the desktop app). */
        private val remembered = HashMap<String, Remembered>()
    }
}
