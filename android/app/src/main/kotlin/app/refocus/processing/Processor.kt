package app.refocus.processing

import android.graphics.Bitmap
import android.util.Log
import app.refocus.core.camera.CancelledException
import app.refocus.core.lf.LfMetadata
import app.refocus.core.lf.LightField
import app.refocus.core.lf.LightFieldStore
import app.refocus.core.lf.RenderParams
import app.refocus.data.Library
import app.refocus.data.ProcessingStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

/** The most recently used decoded light fields (they are ~64 MB each). */
class LightFieldCache(private val library: Library) {
    private val map = LinkedHashMap<String, LightField>(4, 0.75f, true)

    @Synchronized fun get(id: String): LightField? = map[id]

    @Synchronized fun put(id: String, lf: LightField) {
        map[id] = lf
        while (map.size > MAX) map.remove(map.keys.first())
    }

    @Synchronized fun remove(id: String) { map.remove(id) }

    @Synchronized fun clear() = map.clear()

    /** From memory, or from disk (views plus the depth map when available). */
    suspend fun load(id: String): LightField? = withContext(Dispatchers.IO) {
        get(id)?.let { lf ->
            if (lf.depth == null && library.depthFile(id).exists()) LightFieldStore.loadDepth(lf, library.depthFile(id))
            return@withContext lf
        }
        val lf = LightFieldStore.loadViews(library.viewsFile(id)) ?: return@withContext null
        if (library.depthFile(id).exists()) LightFieldStore.loadDepth(lf, library.depthFile(id))
        put(id, lf)
        lf
    }

    companion object { private const val MAX = 2 }
}

/**
 * Decodes downloaded RAWs and estimates their depth, one picture at a time.
 * The picture open in the viewer always goes first, interrupting work on
 * another picture (which is picked up again afterwards).
 */
class Processor(
    private val library: Library,
    private val cache: LightFieldCache,
    scope: CoroutineScope,
) {
    private val focused = MutableStateFlow<String?>(null)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current.asStateFlow()

    val busy: StateFlow<Boolean> = library.items.map { m -> m.values.any { it.needsProcessing } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch(Dispatchers.Default) { loop() }
        // forget stale progress from a previous run
        library.updateAll { it.copy(processing = if (it.needsProcessing) ProcessingStage.Queued else null) }
    }

    /** The picture the user is looking at (null when the viewer closes). */
    fun focus(id: String?) {
        focused.value = id
        wake.trySend(Unit)
    }

    fun kick() {
        wake.trySend(Unit)
    }

    private fun pick(): String? {
        val f = focused.value
        if (f != null && library[f]?.needsProcessing == true) return f
        return library.sorted().firstOrNull { it.needsProcessing }?.id
    }

    private suspend fun loop() {
        while (true) {
            val id = pick()
            if (id == null) {
                wake.receive()
                continue
            }
            _current.value = id
            try {
                process(id)
            } catch (e: CancelledException) {
                library.update(id) { it.copy(processing = if (it.needsProcessing) ProcessingStage.Queued else null) }
            } catch (e: OutOfMemoryError) {
                cache.clear()
                System.gc()
                fail(id, "Not enough memory to process this picture")
            } catch (e: Exception) {
                Log.w(TAG, "processing $id failed", e)
                fail(id, e.message ?: "Processing failed")
            } finally {
                _current.value = null
            }
        }
    }

    private fun fail(id: String, message: String) =
        library.update(id) { it.copy(processing = null, error = message) }

    private fun process(id: String) {
        val cancelled = {
            val f = focused.value
            val item = library[id]
            item == null || !item.rawDone || (f != null && f != id && library[f]?.needsProcessing == true)
        }
        var lf: LightField? = null
        val item = library[id] ?: return
        val t0 = System.nanoTime()
        if (!item.hasViews) {
            library.update(id) { it.copy(processing = ProcessingStage.Decoding, processingProgress = 0f) }
            val meta = LfMetadata.parse(library.metaFile(id).readBytes())
            val raw = library.rawFile(id).readBytes()
            lf = LightField.decode(raw, meta, progress = { p ->
                library.update(id) { it.copy(processingProgress = p) }
            }, cancelled = cancelled)
            if (cancelled()) throw CancelledException()
            LightFieldStore.saveViews(lf, library.viewsFile(id))
            Log.i(TAG, "${item.name}: decoded in ${(System.nanoTime() - t0) / 1_000_000} ms")
            cache.put(id, lf)
            writePreview(id, lf)
            library.update(id) { it.copy(hasViews = true, previewVersion = it.previewVersion + 1) }
        }
        if (library[id]?.hasDepth != true) {
            lf = lf ?: cache.get(id) ?: LightFieldStore.loadViews(library.viewsFile(id))?.also { cache.put(id, it) }
                ?: throw IllegalStateException("Decoded data is missing")
            library.update(id) { it.copy(processing = ProcessingStage.Depth, processingProgress = 0f) }
            var last = 0L
            lf.computeDepth(progress = { p ->
                val now = System.nanoTime()
                if (now - last > 100_000_000) {
                    last = now
                    library.update(id) { it.copy(processingProgress = p) }
                }
            }, cancelled = cancelled)
            LightFieldStore.saveDepth(lf, library.depthFile(id))
            Log.i(TAG, "${item.name}: processed in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
        library.update(id) { it.copy(hasDepth = true, processing = null, processingProgress = 1f) }
    }

    private fun writePreview(id: String, lf: LightField) {
        val argb = lf.toArgb(lf.render(RenderParams()))
        val bmp = Bitmap.createBitmap(argb, lf.width, lf.height, Bitmap.Config.ARGB_8888)
        val tmp = java.io.File(library.previewFile(id).path + ".part")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        tmp.renameTo(library.previewFile(id))
        bmp.recycle()
    }

    companion object { private const val TAG = "Processor" }
}
