package app.refocus.core.export

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Animated GIF writer with one global median-cut palette for all frames and a
 * fixed ordered dither (stable from frame to frame, so it doesn't shimmer).
 */
class GifEncoder(private val width: Int, private val height: Int, private val delayMs: Int) {

    private val frames = ArrayList<IntArray>()

    fun add(argb: IntArray) {
        require(argb.size == width * height)
        frames += argb
    }

    fun write(out: OutputStream, progress: (Float) -> Unit = {}) {
        val palette = buildPalette()
        val lookup = IntArray(32 * 32 * 32) { -1 }

        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        le16(out, width); le16(out, height)
        out.write(0xF7) // global colour table, 8 bits/colour, 256 entries
        out.write(0); out.write(0)
        for (c in palette) { out.write((c shr 16) and 0xFF); out.write((c shr 8) and 0xFF); out.write(c and 0xFF) }
        // loop forever
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))

        val delay = (delayMs + 5) / 10
        val indices = ByteArray(width * height)
        frames.forEachIndexed { fi, frame ->
            for (y in 0 until height) for (x in 0 until width) {
                val p = frame[y * width + x]
                val d = BAYER[(y and 3) * 4 + (x and 3)]
                val r = (((p shr 16) and 0xFF) + d).coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) + d).coerceIn(0, 255)
                val b = ((p and 0xFF) + d).coerceIn(0, 255)
                val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
                var idx = lookup[key]
                if (idx < 0) {
                    idx = nearest(palette, (r and 0xF8) or 4, (g and 0xF8) or 4, (b and 0xF8) or 4)
                    lookup[key] = idx
                }
                indices[y * width + x] = idx.toByte()
            }
            // graphic control extension
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x04))
            le16(out, delay)
            out.write(0); out.write(0)
            // image descriptor
            out.write(0x2C)
            le16(out, 0); le16(out, 0); le16(out, width); le16(out, height)
            out.write(0)
            lzw(out, indices)
            progress((fi + 1f) / frames.size)
        }
        out.write(0x3B)
        out.flush()
    }

    // ---------------------------------------------------------------- palette
    private fun buildPalette(): IntArray {
        val total = frames.size.toLong() * width * height
        val step = maxOf(1L, total / 250_000L).toInt()
        val samples = ArrayList<Int>()
        var n = 0L
        for (f in frames) for (p in f) {
            if (n++ % step == 0L) samples += p and 0xFFFFFF
        }
        val boxes = ArrayList<IntArray>()
        boxes += samples.toIntArray()
        while (boxes.size < 256) {
            // split the box with the widest channel range
            var bestI = -1
            var bestRange = -1
            var bestCh = 0
            for ((i, box) in boxes.withIndex()) {
                if (box.size < 2) continue
                for (ch in 0 until 3) {
                    val s = 16 - 8 * ch
                    var lo = 255; var hi = 0
                    for (p in box) { val v = (p shr s) and 0xFF; if (v < lo) lo = v; if (v > hi) hi = v }
                    if (hi - lo > bestRange) { bestRange = hi - lo; bestI = i; bestCh = ch }
                }
            }
            if (bestI < 0 || bestRange <= 0) break
            val box = boxes.removeAt(bestI)
            val s = 16 - 8 * bestCh
            val sorted = box.sortedBy { (it shr s) and 0xFF }
            boxes += sorted.subList(0, sorted.size / 2).toIntArray()
            boxes += sorted.subList(sorted.size / 2, sorted.size).toIntArray()
        }
        val palette = IntArray(256)
        boxes.forEachIndexed { i, box ->
            var r = 0L; var g = 0L; var b = 0L
            for (p in box) { r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF }
            val c = maxOf(box.size, 1)
            palette[i] = ((r / c).toInt() shl 16) or ((g / c).toInt() shl 8) or (b / c).toInt()
        }
        return palette
    }

    private fun nearest(palette: IntArray, r: Int, g: Int, b: Int): Int {
        var best = 0
        var bestD = Int.MAX_VALUE
        for (i in palette.indices) {
            val c = palette[i]
            val dr = ((c shr 16) and 0xFF) - r
            val dg = ((c shr 8) and 0xFF) - g
            val db = (c and 0xFF) - b
            val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    // -------------------------------------------------------------------- LZW
    private fun lzw(out: OutputStream, data: ByteArray) {
        val minCode = 8
        out.write(minCode)
        val clear = 1 shl minCode
        val eoi = clear + 1
        val blocks = ByteArrayOutputStream()
        var bitBuf = 0L
        var bitCount = 0
        var codeSize = minCode + 1
        var next = eoi + 1
        var clearFlag = false
        // same code-size bookkeeping as the classic LZWEncoder: after every code,
        // grow the code size once the next free code no longer fits
        fun emit(code: Int) {
            bitBuf = bitBuf or (code.toLong() shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                blocks.write((bitBuf and 0xFF).toInt())
                bitBuf = bitBuf ushr 8
                bitCount -= 8
            }
            if (clearFlag) {
                codeSize = minCode + 1
                clearFlag = false
            } else if (next > (1 shl codeSize) - 1 && codeSize < 12) {
                codeSize++
            }
        }
        val table = HashMap<Int, Int>(8192)
        emit(clear)
        var prefix = data[0].toInt() and 0xFF
        for (i in 1 until data.size) {
            val c = data[i].toInt() and 0xFF
            val key = (prefix shl 8) or c
            val found = table[key]
            if (found != null) {
                prefix = found
                continue
            }
            emit(prefix)
            if (next < 4096) {
                table[key] = next++
            } else {
                table.clear()
                next = eoi + 1
                clearFlag = true
                emit(clear)
            }
            prefix = c
        }
        emit(prefix)
        emit(eoi)
        if (bitCount > 0) blocks.write((bitBuf and 0xFF).toInt())
        val bytes = blocks.toByteArray()
        var off = 0
        while (off < bytes.size) {
            val n = minOf(255, bytes.size - off)
            out.write(n)
            out.write(bytes, off, n)
            off += n
        }
        out.write(0)
    }

    private fun le16(out: OutputStream, v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

    companion object {
        private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5).map { it - 8 }.toIntArray()
    }
}
