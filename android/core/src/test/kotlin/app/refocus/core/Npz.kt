package app.refocus.core

import app.refocus.core.lf.Half
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** Just enough of a .npz reader to compare against the Python decoder's cache. */
class NpyArray(val shape: IntArray, val data: FloatArray)

fun readNpz(file: File): Map<String, NpyArray> = ZipFile(file).use { zip ->
    zip.entries().asSequence().associate { e ->
        val bytes = zip.getInputStream(e).readBytes()
        e.name.removeSuffix(".npy") to parseNpy(bytes)
    }
}

private fun parseNpy(b: ByteArray): NpyArray {
    val major = b[6].toInt()
    val headerLen: Int
    val start: Int
    if (major == 1) {
        headerLen = (b[8].toInt() and 0xFF) or ((b[9].toInt() and 0xFF) shl 8); start = 10
    } else {
        headerLen = ByteBuffer.wrap(b, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int; start = 12
    }
    val header = String(b, start, headerLen, Charsets.US_ASCII)
    val descr = Regex("'descr': '([^']+)'").find(header)!!.groupValues[1]
    val shapeStr = Regex("'shape': \\(([^)]*)\\)").find(header)!!.groupValues[1]
    val shape = shapeStr.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }.toIntArray()
    val n = shape.fold(1) { a, x -> a * x }
    require(!header.contains("'fortran_order': True"))
    val buf = ByteBuffer.wrap(b, start + headerLen, b.size - start - headerLen).order(ByteOrder.LITTLE_ENDIAN)
    val data = FloatArray(n)
    when (descr) {
        "<f2" -> for (i in 0 until n) data[i] = Half.toFloat(buf.short)
        "<f4" -> for (i in 0 until n) data[i] = buf.float
        "<f8" -> for (i in 0 until n) data[i] = buf.double.toFloat()
        "|b1" -> for (i in 0 until n) data[i] = buf.get().toFloat()
        "<i8" -> for (i in 0 until n) data[i] = buf.long.toFloat()
        "<i4" -> for (i in 0 until n) data[i] = buf.int.toFloat()
        else -> error("unsupported dtype $descr")
    }
    return NpyArray(shape, data)
}
