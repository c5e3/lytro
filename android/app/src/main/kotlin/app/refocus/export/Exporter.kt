package app.refocus.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import app.refocus.core.export.GifEncoder
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
enum class StillFormat(val label: String, val mime: String, val ext: String) {
    Jpeg("JPEG", "image/jpeg", "jpg"), Png("PNG", "image/png", "png")
}
enum class AnimationFormat(val label: String, val mime: String, val ext: String) {
    Mp4("MP4", "video/mp4", "mp4"), Gif("GIF", "image/gif", "gif")
}

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
        format: StillFormat,
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
        val name = "${item.name}_${suffix}_$stamp.${format.ext}"
        val out = ArrayList<Exported>()
        out += insert(MediaKind.Image, name, format.mime, item) { os ->
            bmp.compress(if (format == StillFormat.Jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 95, os)
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

    suspend fun exportAnimation(
        item: PictureItem,
        lf: LightField,
        kind: AnimationKind,
        base: RenderParams,
        focusA: Float?,
        focusB: Float?,
        format: AnimationFormat,
        scale: Int,
        seconds: Int,
        progress: (Float) -> Unit,
    ): Exported = withContext(Dispatchers.Default) {
        val fps = if (format == AnimationFormat.Gif) 25 else 30
        val frames = Animations.frames(kind, lf, base, seconds * fps, focusA, focusB)
        // H.264 encoders want sizes in multiples of 16
        val (w, h) = if (format == AnimationFormat.Mp4) {
            ((lf.width * scale / 16f).roundToInt() * 16) to ((lf.height * scale / 16f).roundToInt() * 16)
        } else (lf.width * scale) to (lf.height * scale)
        val label = when (kind) {
            AnimationKind.FocusSweep -> "focus-sweep"
            AnimationKind.PerspectiveOrbit -> "orbit"
            AnimationKind.TapToTap -> "focus-pull"
        }
        val name = "${item.name}_${label}_$stamp.${format.ext}"
        val renderShare = if (format == AnimationFormat.Gif) 0.8f else 0.97f

        fun frameArgb(i: Int): IntArray {
            val argb = lf.toArgb(lf.render(frames[i]))
            return ImageOps.resizeArgb(argb, lf.width, lf.height, w, h)
        }

        val mediaKind = if (format == AnimationFormat.Mp4) MediaKind.Video else MediaKind.Image
        when (format) {
            AnimationFormat.Mp4 -> insertWithFd(mediaKind, name, format.mime, item) { fd ->
                val writer = Mp4Writer(fd, w, h, fps)
                try {
                    for (i in frames.indices) {
                        ensureActive()
                        writer.addFrame(frameArgb(i))
                        progress(renderShare * (i + 1) / frames.size)
                    }
                    writer.finish()
                } catch (e: Throwable) {
                    writer.abort()
                    throw e
                }
            }
            AnimationFormat.Gif -> {
                val enc = GifEncoder(w, h, 1000 / fps)
                for (i in frames.indices) {
                    ensureActive()
                    enc.add(frameArgb(i))
                    progress(renderShare * (i + 1) / frames.size)
                }
                insert(mediaKind, name, format.mime, item) { os ->
                    enc.write(os) { progress(renderShare + (1 - renderShare) * it) }
                }
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
