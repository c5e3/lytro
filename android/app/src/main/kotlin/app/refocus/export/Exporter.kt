package app.refocus.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import app.refocus.core.export.Png16
import app.refocus.core.lf.AnimationKind
import app.refocus.core.lf.Animations
import app.refocus.core.lf.ImageOps
import app.refocus.core.lf.LightField
import app.refocus.core.lf.RenderParams
import app.refocus.data.Library
import app.refocus.data.PictureItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

enum class StillContent(val label: String) { Photo("Photo"), Depth("Depth map"), Overlay("Overlay") }

data class Exported(val uri: Uri, val mime: String, val displayName: String)

/**
 * Writes exports to shared storage (Pictures/Refocus, Movies/Refocus and
 * Download/Refocus). Only exports ever appear in the phone's gallery.
 */
class Exporter(private val context: Context, private val library: Library) {

    private val resolver get() = context.contentResolver
    private val stamp get() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

    suspend fun exportStill(
        item: PictureItem,
        lf: LightField,
        params: RenderParams,
        content: StillContent,
        scale: Int,
        withDepth16: Boolean,
    ): List<Exported> = withContext(Dispatchers.Default) {
        val photo = { lf.toArgb(lf.render(params)) }
        val argb = when (content) {
            StillContent.Photo -> photo()
            StillContent.Depth -> lf.depthArgb() ?: error("The depth map isn't ready yet")
            StillContent.Overlay -> lf.overlayArgb(photo()) ?: error("The depth map isn't ready yet")
        }
        val w = lf.width * scale
        val h = lf.height * scale
        val scaled = ImageOps.resizeArgb(argb, lf.width, lf.height, w, h)
        val bmp = Bitmap.createBitmap(scaled, w, h, Bitmap.Config.ARGB_8888)
        val suffix = when (content) { StillContent.Photo -> "refocused"; StillContent.Depth -> "depth"; StillContent.Overlay -> "overlay" }
        val name = "${item.name}_${suffix}_$stamp.jpg"
        val out = ArrayList<Exported>()
        out += insert(MediaKind.Image, name, "image/jpeg", item) { os ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, os)
        }
        bmp.recycle()
        if (withDepth16 && content == StillContent.Depth) {
            val d16 = lf.depth16() ?: error("The depth map isn't ready yet")
            out += insert(MediaKind.Image, "${item.name}_depth16_$stamp.png", "image/png", item) { os ->
                Png16.write(os, lf.width, lf.height, d16)
            }
        }
        out
    }

    /** An H.264 MP4 at 30 fps, to Movies/Refocus. */
    suspend fun exportAnimation(
        item: PictureItem,
        lf: LightField,
        kind: AnimationKind,
        base: RenderParams,
        nearToFar: Boolean,
        cycles: Int,
        scale: Int,
        seconds: Int,
        progress: (Float) -> Unit,
    ): Exported = withContext(Dispatchers.Default) {
        val fps = 30
        // [seconds] is the length of the sweep, or of one orbit (repeated [cycles] times)
        val repeats = if (kind == AnimationKind.PerspectiveOrbit) cycles.coerceAtLeast(1) else 1
        val frames = Animations.frames(kind, lf, base, seconds * fps * repeats, nearToFar, repeats)
        // H.264 encoders want sizes in multiples of 16
        val w = (lf.width * scale / 16f).roundToInt() * 16
        val h = (lf.height * scale / 16f).roundToInt() * 16
        val label = when (kind) {
            AnimationKind.FocusSweep -> if (nearToFar) "focus-near-to-far" else "focus-far-to-near"
            AnimationKind.PerspectiveOrbit -> "orbit"
        }
        val name = "${item.name}_${label}_$stamp.mp4"
        insertWithFd(MediaKind.Video, name, "video/mp4", item) { fd ->
            val writer = Mp4Writer(fd, w, h, fps)
            try {
                for (i in frames.indices) {
                    ensureActive()
                    val argb = lf.toArgb(lf.render(frames[i]))
                    writer.addFrame(ImageOps.resizeArgb(argb, lf.width, lf.height, w, h))
                    progress(0.98f * (i + 1) / frames.size)
                }
                writer.finish()
            } catch (e: Throwable) {
                writer.abort()
                throw e
            }
        }.also { progress(1f) }
    }

    /** The original RAW and its metadata, to Download/Refocus. */
    suspend fun exportOriginal(item: PictureItem): List<Exported> = withContext(Dispatchers.IO) {
        val raw = library.rawFile(item.id)
        require(raw.exists()) { "The RAW file isn't on the phone" }
        listOf(
            insert(MediaKind.Download, "${item.name}.RAW", "application/octet-stream", item) { os ->
                raw.inputStream().use { it.copyTo(os, 1 shl 16) }
            },
            insert(MediaKind.Download, "${item.name}.TXT", "text/plain", item) { os ->
                library.metaFile(item.id).inputStream().use { it.copyTo(os) }
            },
        )
    }

    // -------------------------------------------------------------- MediaStore
    private enum class MediaKind { Image, Video, Download }

    private fun values(kind: MediaKind, name: String, mime: String, item: PictureItem) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
        put(MediaStore.MediaColumns.RELATIVE_PATH, when (kind) {
            MediaKind.Image -> "${Environment.DIRECTORY_PICTURES}/Refocus"
            MediaKind.Video -> "${Environment.DIRECTORY_MOVIES}/Refocus"
            MediaKind.Download -> "${Environment.DIRECTORY_DOWNLOADS}/Refocus"
        })
        if (kind != MediaKind.Download) put(MediaStore.MediaColumns.DATE_TAKEN, item.taken.toEpochMilli())
    }

    private fun collection(kind: MediaKind): Uri = when (kind) {
        MediaKind.Image -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        MediaKind.Video -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        MediaKind.Download -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }

    private inline fun insert(kind: MediaKind, name: String, mime: String, item: PictureItem, write: (OutputStream) -> Unit): Exported {
        val uri = resolver.insert(collection(kind), values(kind, name, mime, item)) ?: error("Couldn't create $name")
        try {
            resolver.openOutputStream(uri)!!.use(write)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
        return Exported(uri, mime, name)
    }

    private inline fun insertWithFd(kind: MediaKind, name: String, mime: String, item: PictureItem, write: (java.io.FileDescriptor) -> Unit): Exported {
        val uri = resolver.insert(collection(kind), values(kind, name, mime, item)) ?: error("Couldn't create $name")
        try {
            resolver.openFileDescriptor(uri, "rw")!!.use { write(it.fileDescriptor) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
        return Exported(uri, mime, name)
    }
}
