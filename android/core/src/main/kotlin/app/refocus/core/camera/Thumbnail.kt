package app.refocus.core.camera

/** Size of the camera's `.128` thumbnails (YUYV 4:2:2, Y0 U Y1 V). */
const val THUMBNAIL_SIZE = 128

/** Decodes a 128×128 YUYV thumbnail into ARGB_8888 pixels. */
fun thumbnailToArgb(data: ByteArray): IntArray {
    val n = THUMBNAIL_SIZE
    val out = IntArray(n * n)
    for (y in 0 until n) {
        for (x2 in 0 until n / 2) {
            val i = (y * n / 2 + x2) * 4
            if (i + 3 >= data.size) return out
            val y0 = (data[i].toInt() and 0xFF).toFloat()
            val u = (data[i + 1].toInt() and 0xFF) - 128f
            val y1 = (data[i + 2].toInt() and 0xFF).toFloat()
            val v = (data[i + 3].toInt() and 0xFF) - 128f
            out[y * n + x2 * 2] = yuv(y0, u, v)
            out[y * n + x2 * 2 + 1] = yuv(y1, u, v)
        }
    }
    return out
}

private fun yuv(y: Float, u: Float, v: Float): Int {
    val r = (y + 1.402f * v).toInt().coerceIn(0, 255)
    val g = (y - 0.344f * u - 0.714f * v).toInt().coerceIn(0, 255)
    val b = (y + 1.772f * u).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
