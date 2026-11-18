package app.refocus.core

import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * A minimal stand-in for the camera's command channel, serving files from memory.
 * Records every command it receives so tests can assert nothing unsafe is sent.
 */
class FakeCamera(private val files: Map<String, ByteArray>, private val pictureList: ByteArray) : AutoCloseable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    val commands = java.util.Collections.synchronizedList(ArrayList<String>())

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { serve(s) }
            }
        }
    }

    private fun serve(s: Socket) = s.use {
        val inp = DataInputStream(s.getInputStream())
        val out = s.getOutputStream()
        var loaded: ByteArray? = null
        try {
            while (true) {
                val hdr = ByteArray(28)
                inp.readFully(hdr)
                val b = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                check(b.int == 0xFAAA55AF.toInt())
                val length = b.int
                val flags = b.int
                val cmd = hdr.copyOfRange(12, 28)
                val hex = cmd.take(3).joinToString(" ") { "%02X".format(it) }
                commands += hex
                var body = ByteArray(0)
                when {
                    cmd[0] == 0xC2.toByte() -> {
                        val payload = ByteArray(length).also { inp.readFully(it) }
                        loaded = when (cmd[2].toInt()) {
                            0 -> ByteArray(644).also { "Lytro, Inc.".toByteArray().copyInto(it) ; "A2023".toByteArray().copyInto(it, 256) }
                            1 -> files[String(payload, Charsets.US_ASCII).trimEnd('\u0000')]
                            2 -> pictureList
                            else -> error("unsafe command $hex")
                        }
                    }
                    cmd[0] == 0xC6.toByte() -> loaded?.let {
                        body = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(it.size).array()
                    }
                    cmd[0] == 0xC4.toByte() -> {
                        val off = ByteBuffer.wrap(cmd, 3, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        val data = loaded!!
                        body = data.copyOfRange(off, minOf(off + length, data.size))
                    }
                }
                val reply = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(0xFAAA55AF.toInt()).putInt(body.size).putInt(if (body.isEmpty()) 2 else 3).put(cmd)
                out.write(reply.array())
                out.write(body)
                out.flush()
                if (flags < 0) break
            }
        } catch (_: java.io.EOFException) {
        }
    }

    override fun close() = server.close()

    companion object {
        fun pictureList(entries: List<Triple<Int, String, String>>): ByteArray {
            val b = ByteBuffer.allocate(92 + 128 * entries.size).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(1).putInt(0x80).putInt(10)
            repeat(10) { b.putInt(it).putInt(it * 4) }
            for ((num, id, taken) in entries) {
                val e = ByteArray(128)
                val eb = ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN)
                "PHOTO".toByteArray().copyInto(e, 0)
                "IMG_".toByteArray().copyInto(e, 8)
                eb.putInt(0x10, 100).putInt(0x14, num).putFloat(0x2C, 1.5f)
                id.toByteArray().copyInto(e, 0x30)
                taken.toByteArray().copyInto(e, 0x60)
                b.put(e)
            }
            return b.array()
        }
    }
}
