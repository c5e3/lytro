package app.refocus.core.export

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** Minimal writer for 16-bit greyscale PNGs (Android's Bitmap can't produce them). */
object Png16 {
    fun write(out: OutputStream, width: Int, height: Int, pixels: ShortArray) {
        val o = DataOutputStream(out)
        o.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArrayOutputStream().also {
            val d = DataOutputStream(it)
            d.writeInt(width); d.writeInt(height)
            d.writeByte(16) // bit depth
            d.writeByte(0)  // greyscale
            d.writeByte(0); d.writeByte(0); d.writeByte(0)
        }.toByteArray()
        chunk(o, "IHDR", ihdr)
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw, Deflater(6)).use { z ->
            val row = ByteArray(1 + width * 2)
            for (y in 0 until height) {
                row[0] = 0
                for (x in 0 until width) {
                    val v = pixels[y * width + x].toInt() and 0xFFFF
                    row[1 + 2 * x] = (v shr 8).toByte()
                    row[2 + 2 * x] = v.toByte()
                }
                z.write(row)
            }
        }
        chunk(o, "IDAT", raw.toByteArray())
        chunk(o, "IEND", ByteArray(0))
        o.flush()
    }

    private fun chunk(o: DataOutputStream, type: String, data: ByteArray) {
        val t = type.toByteArray(Charsets.US_ASCII)
        o.writeInt(data.size)
        o.write(t)
        o.write(data)
        val crc = CRC32()
        crc.update(t)
        crc.update(data)
        o.writeInt(crc.value.toInt())
    }
}
