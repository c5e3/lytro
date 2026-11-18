package app.refocus.core.lf

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Disk format for decoded light fields. Views are stored as half floats
 * (~32 MB per picture); the depth map lives in its own file so an interrupted
 * depth pass never invalidates the decoded views.
 */
object LightFieldStore {
    /** Bump whenever decoding changes, so stale caches are ignored. */
    const val VERSION = 1
    private const val VIEWS_MAGIC = 0x52464C46 // "RFLF"
    private const val DEPTH_MAGIC = 0x52464450 // "RFDP"

    fun saveViews(lf: LightField, file: File) = atomicWrite(file) { out ->
        out.writeInt(VIEWS_MAGIC); out.writeInt(VERSION)
        out.writeInt(lf.width); out.writeInt(lf.height); out.writeInt(lf.count)
        out.writeFloat(lf.gamma)
        for (v in lf.offsets) out.writeFloat(v)
        val sat = ByteArray(lf.pixels) { if (lf.saturated[it]) 1 else 0 }
        out.write(sat)
        writeHalves(out, lf.views)
    }

    fun loadViews(file: File): LightField? = try {
        DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { inp ->
            if (inp.readInt() != VIEWS_MAGIC || inp.readInt() != VERSION) return null
            val w = inp.readInt(); val h = inp.readInt(); val k = inp.readInt()
            val gamma = inp.readFloat()
            val offsets = FloatArray(2 * k) { inp.readFloat() }
            val sat = ByteArray(w * h).also { inp.readFully(it) }
            val views = readHalves(inp, k * 3 * w * h)
            LightField(w, h, offsets, views, BooleanArray(w * h) { sat[it] != 0.toByte() }, gamma)
        }
    } catch (e: IOException) {
        null
    }

    fun saveDepth(lf: LightField, file: File) {
        val d = lf.depth ?: return
        val c = lf.depthConfidence ?: return
        atomicWrite(file) { out ->
            out.writeInt(DEPTH_MAGIC); out.writeInt(VERSION)
            out.writeInt(lf.width); out.writeInt(lf.height)
            writeFloats(out, d)
            writeFloats(out, c)
        }
    }

    /** Loads the depth map into [lf]; false if missing, stale or mismatched. */
    fun loadDepth(lf: LightField, file: File): Boolean = try {
        DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { inp ->
            if (inp.readInt() != DEPTH_MAGIC || inp.readInt() != VERSION) return false
            if (inp.readInt() != lf.width || inp.readInt() != lf.height) return false
            val d = readFloats(inp, lf.pixels)
            val c = readFloats(inp, lf.pixels)
            lf.depthConfidence = c
            lf.depth = d
            true
        }
    } catch (e: IOException) {
        false
    }

    private fun atomicWrite(file: File, body: (DataOutputStream) -> Unit) {
        val tmp = File(file.parentFile, file.name + ".part")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use(body)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Could not write ${file.name}")
        }
    }

    private const val BLOCK = 1 shl 15

    private fun writeHalves(out: DataOutputStream, data: FloatArray) {
        val buf = ByteBuffer.allocate(BLOCK * 2).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < data.size) {
            buf.clear()
            val n = minOf(BLOCK, data.size - i)
            for (j in 0 until n) buf.putShort(Half.fromFloat(data[i + j]))
            out.write(buf.array(), 0, n * 2)
            i += n
        }
    }

    private fun readHalves(inp: DataInputStream, count: Int): FloatArray {
        val out = FloatArray(count)
        val bytes = ByteArray(BLOCK * 2)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < count) {
            val n = minOf(BLOCK, count - i)
            inp.readFully(bytes, 0, n * 2)
            buf.clear()
            for (j in 0 until n) out[i + j] = Half.toFloat(buf.getShort())
            i += n
        }
        return out
    }

    private fun writeFloats(out: DataOutputStream, data: FloatArray) {
        val buf = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(data)
        out.write(buf.array())
    }

    private fun readFloats(inp: DataInputStream, count: Int): FloatArray {
        val bytes = ByteArray(count * 4)
        inp.readFully(bytes)
        val out = FloatArray(count)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }
}

/** IEEE 754 half-precision conversion (round to nearest even). */
object Half {
    fun fromFloat(f: Float): Short {
        val bits = java.lang.Float.floatToRawIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val value = bits and 0x7FFFFFFF
        if (value >= 0x47800000) { // overflow, inf or NaN
            return (sign or if (value > 0x7F800000) 0x7E00 else 0x7C00).toShort()
        }
        if (value < 0x38800000) { // subnormal or zero
            if (value < 0x33000000) return sign.toShort()
            val exp = value ushr 23
            val mant = (value and 0x7FFFFF) or 0x800000
            val shift = 126 - exp
            var h = mant ushr shift
            val rem = mant and ((1 shl shift) - 1)
            val half = 1 shl (shift - 1)
            if (rem > half || (rem == half && (h and 1) == 1)) h++
            return (sign or h).toShort()
        }
        var h = ((value - 0x38000000) ushr 13)
        val rem = value and 0x1FFF
        if (rem > 0x1000 || (rem == 0x1000 && (h and 1) == 1)) h++
        return (sign or h).toShort()
    }

    fun toFloat(hs: Short): Float {
        val h = hs.toInt() and 0xFFFF
        val sign = (h and 0x8000) shl 16
        val exp = (h ushr 10) and 0x1F
        val mant = h and 0x3FF
        val bits = when {
            exp == 0 -> {
                if (mant == 0) sign
                else {
                    var s = 0
                    var m = mant
                    while (m and 0x400 == 0) { m = m shl 1; s++ }
                    sign or ((113 - s) shl 23) or ((m and 0x3FF) shl 13)
                }
            }
            exp == 0x1F -> sign or 0x7F800000 or (mant shl 13)
            else -> sign or ((exp + 112) shl 23) or (mant shl 13)
        }
        return java.lang.Float.intBitsToFloat(bits)
    }
}
