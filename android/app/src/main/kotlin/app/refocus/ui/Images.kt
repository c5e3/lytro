package app.refocus.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.refocus.core.camera.THUMBNAIL_SIZE
import app.refocus.core.camera.thumbnailToArgb
import app.refocus.data.Library
import app.refocus.data.PictureItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gallery images: the rendered preview once processed, otherwise the camera's thumbnail. */
object PictureImages {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val latest = HashMap<String, String>()

    private fun key(item: PictureItem) = "${item.id}:${item.previewVersion}:${item.thumbVersion}"

    fun cached(item: PictureItem): ImageBitmap? =
        cache[key(item)] ?: synchronized(latest) { latest[item.id] }?.let { cache[it] }

    suspend fun load(library: Library, item: PictureItem): ImageBitmap? = withContext(Dispatchers.IO) {
        val k = key(item)
        cache[k]?.let { return@withContext it }
        val preview = library.previewFile(item.id)
        val thumb = library.thumbFile(item.id)
        val bmp: Bitmap? = when {
            item.previewVersion > 0 && preview.exists() -> BitmapFactory.decodeFile(preview.path)
            thumb.exists() -> {
                val px = thumbnailToArgb(thumb.readBytes())
                Bitmap.createBitmap(px, THUMBNAIL_SIZE, THUMBNAIL_SIZE, Bitmap.Config.ARGB_8888)
            }
            else -> null
        }
        bmp?.asImageBitmap()?.also {
            cache.put(k, it)
            synchronized(latest) { latest[item.id] = k }
        }
    }

    fun clear() = cache.evictAll()
}

@Composable
fun rememberPictureImage(library: Library, item: PictureItem): ImageBitmap? =
    produceState(PictureImages.cached(item), item.id, item.previewVersion, item.thumbVersion) {
        PictureImages.load(library, item)?.let { value = it }
    }.value
