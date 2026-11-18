package app.refocus.data

import android.content.Context
import app.refocus.core.camera.Picture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.time.Instant

enum class ProcessingStage { Queued, Decoding, Depth }

/** Everything the UI needs to know about one picture. */
data class PictureItem(
    val picture: Picture,
    /** Listed by the camera the last time we were connected (or never checked). */
    val onCamera: Boolean = true,
    /** Bumped whenever the thumbnail / preview file changes, so images reload. */
    val thumbVersion: Int = 0,
    val previewVersion: Int = 0,
    val hasMeta: Boolean = false,
    val rawBytes: Long = 0,
    val rawTotal: Long = 0,
    val rawDone: Boolean = false,
    /** Waiting in a download queue (tapped, or part of "download all"). */
    val downloadQueued: Boolean = false,
    val downloading: Boolean = false,
    val hasViews: Boolean = false,
    val hasDepth: Boolean = false,
    val processing: ProcessingStage? = null,
    val processingProgress: Float = 0f,
    val error: String? = null,
) {
    val id: String get() = picture.id
    val name: String get() = picture.name
    val taken: Instant by lazy { runCatching { Instant.parse(picture.taken) }.getOrDefault(Instant.EPOCH) }
    val ready: Boolean get() = hasViews && hasDepth
    val downloadProgress: Float get() = if (rawTotal > 0) rawBytes.toFloat() / rawTotal else 0f
    val needsDownload: Boolean get() = !rawDone
    val needsProcessing: Boolean get() = rawDone && !(hasViews && hasDepth) && error == null
}

/**
 * Pictures known to the app, stored privately under `files/pictures/<id>/`
 * (never visible to the phone's gallery). Only exports go to shared storage.
 */
class Library(context: Context) {
    val root = File(context.filesDir, "pictures").apply { mkdirs() }

    private val _items = MutableStateFlow(scan())
    val items: StateFlow<Map<String, PictureItem>> = _items.asStateFlow()

    fun dir(id: String) = File(root, safe(id))
    fun entryFile(id: String) = File(dir(id), "entry.json")
    fun thumbFile(id: String) = File(dir(id), "thumb.128")
    fun metaFile(id: String) = File(dir(id), "meta.txt")
    fun rawPartFile(id: String) = File(dir(id), "raw.part")
    fun rawFile(id: String) = File(dir(id), "raw.bin")
    fun viewsFile(id: String) = File(dir(id), "views.bin")
    fun depthFile(id: String) = File(dir(id), "depth.bin")
    fun previewFile(id: String) = File(dir(id), "preview.jpg")

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    operator fun get(id: String): PictureItem? = _items.value[id]

    fun update(id: String, f: (PictureItem) -> PictureItem) {
        _items.update { m -> m[id]?.let { m + (id to f(it)) } ?: m }
    }

    fun updateAll(f: (PictureItem) -> PictureItem) {
        _items.update { m -> m.mapValues { f(it.value) } }
    }

    /** Newest first, the order of the gallery and of "download all". */
    fun sorted(): List<PictureItem> = _items.value.values.sortedByDescending { it.taken }

    // ---------------------------------------------------------------- scanning
    private fun scan(): Map<String, PictureItem> {
        val out = HashMap<String, PictureItem>()
        root.listFiles()?.forEach { d ->
            val entry = File(d, "entry.json")
            val pic = runCatching { pictureFromJson(JSONObject(entry.readText())) }.getOrNull() ?: return@forEach
            out[pic.id] = itemFromDisk(pic, onCamera = true)
        }
        return out
    }

    private fun itemFromDisk(pic: Picture, onCamera: Boolean): PictureItem {
        val id = pic.id
        val raw = rawFile(id)
        val part = rawPartFile(id)
        return PictureItem(
            picture = pic,
            onCamera = onCamera,
            thumbVersion = if (thumbFile(id).exists()) 1 else 0,
            previewVersion = if (previewFile(id).exists()) 1 else 0,
            hasMeta = metaFile(id).exists(),
            rawDone = raw.exists(),
            rawBytes = if (raw.exists()) raw.length() else part.length(),
            rawTotal = if (raw.exists()) raw.length() else 0,
            hasViews = viewsFile(id).exists(),
            hasDepth = depthFile(id).exists(),
        )
    }

    /** Merges the camera's picture list: adds new pictures, drops vanished ones that aren't on the phone. */
    fun mergeCameraList(pics: List<Picture>) {
        val ids = pics.map { it.id }.toSet()
        for (p in pics) {
            if (!entryFile(p.id).exists()) {
                dir(p.id).mkdirs()
                entryFile(p.id).writeText(pictureToJson(p).toString())
            }
        }
        _items.update { old ->
            val next = HashMap<String, PictureItem>()
            for (p in pics) next[p.id] = (old[p.id] ?: itemFromDisk(p, true)).copy(picture = p, onCamera = true)
            for ((id, item) in old) {
                if (id in ids) continue
                if (item.rawDone || item.rawBytes > 0) {
                    next[id] = item.copy(onCamera = false, downloadQueued = false)
                } else {
                    dir(id).deleteRecursively()
                }
            }
            next
        }
    }

    // ---------------------------------------------------------------- removal
    /** Deletes the downloaded and processed data, keeping the thumbnail. */
    fun removeLocal(id: String) {
        val item = this[id] ?: return
        listOf(rawFile(id), rawPartFile(id), viewsFile(id), depthFile(id), previewFile(id),
            File(viewsFile(id).path + ".part"), File(depthFile(id).path + ".part")).forEach { it.delete() }
        if (!item.onCamera) {
            dir(id).deleteRecursively()
            _items.update { it - id }
        } else {
            update(id) {
                it.copy(rawDone = false, rawBytes = 0, rawTotal = 0, hasViews = false, hasDepth = false,
                    processing = null, previewVersion = 0, error = null, downloadQueued = false, downloading = false)
            }
        }
    }

    fun localBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun localCount(): Int = _items.value.values.count { it.rawDone }

    companion object {
        fun pictureToJson(p: Picture) = JSONObject()
            .put("folderNum", p.folderNum).put("folderSuffix", p.folderSuffix).put("prefix", p.prefix)
            .put("fileNum", p.fileNum).put("id", p.id).put("taken", p.taken).put("focusLambda", p.focusLambda.toDouble())

        fun pictureFromJson(o: JSONObject) = Picture(
            folderNum = o.getInt("folderNum"), folderSuffix = o.getString("folderSuffix"), prefix = o.getString("prefix"),
            fileNum = o.getInt("fileNum"), id = o.getString("id"), taken = o.getString("taken"),
            focusLambda = o.optDouble("focusLambda", 0.0).toFloat(),
        )
    }
}
