package app.refocus.core.camera

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Client for the first-generation Lytro camera's WiFi command channel (TCP 5678).
 * See PROTOCOL.md in the repository root.
 *
 * The camera is fragile: keep a single connection, send one command at a time and
 * never send commands that are not known to be safe (`C2 00 03` reboots it).
 * Only the commands below are ever sent.
 *
 * Not thread-safe: callers must serialise access (the app runs every camera call on
 * one dedicated thread).
 */
class LytroClient(private val socketFactory: () -> Socket) : Closeable {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null

    /** Camera path of the object currently loaded on the camera, if known. */
    var loadedPath: String? = null
        private set
    private var loadedSize: Int = -1

    val isOpen: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    fun open() {
        close()
        val s = socketFactory()
        socket = s
        input = DataInputStream(s.getInputStream().buffered(CHUNK + HEADER))
        output = s.getOutputStream()
    }

    override fun close() {
        loadedPath = null
        loadedSize = -1
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        input = null
        output = null
    }

    // ------------------------------------------------------------------ framing
    private fun command(cmd: ByteArray, length: Int, flags: Int, payload: ByteArray = EMPTY): ByteArray {
        if (!isOpen) open()
        val out = output!!
        val inp = input!!
        val header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MAGIC).putInt(length).putInt(flags)
        header.put(cmd, 0, minOf(cmd.size, 16))
        out.write(header.array())
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()

        val reply = ByteArray(HEADER)
        inp.readFully(reply)
        val rb = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN)
        val magic = rb.int
        if (magic != MAGIC) throw CameraException("Unexpected reply from camera (magic %08x)".format(magic))
        val replyLength = rb.int
        if (replyLength < 0 || replyLength > MAX_REPLY) throw CameraException("Implausible reply length $replyLength")
        val body = ByteArray(replyLength)
        if (replyLength > 0) inp.readFully(body)
        return body
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: CameraFileMissingException) {
        throw e // the camera answered normally; keep the connection
    } catch (e: IOException) {
        // Drop the connection; the next call reconnects.
        close()
        throw if (e is CameraException) e else CameraException(e.message ?: "Connection to the camera failed", e)
    }

    // ------------------------------------------------------- load / query / read
    /** Selects an object on the camera and returns its size in bytes. */
    private fun load(cmd: ByteArray, payload: ByteArray, key: String): Int = guarded {
        command(cmd, payload.size, FLAG_SEND, payload)
        val b = command(CMD_QUERY_SIZE, 4, FLAG_RECV)
        if (b.size != 4) {
            loadedPath = null
            throw CameraFileMissingException(key)
        }
        val size = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
        loadedPath = key
        loadedSize = size
        size
    }

    /** Loads a file (e.g. `I:\DCIM\100PHOTO\IMG_0001.RAW`) and returns its size. */
    fun loadFile(path: String): Int {
        if (loadedPath == path && loadedSize >= 0 && isOpen) return loadedSize
        return load(CMD_LOAD_FILE, path.toByteArray(Charsets.US_ASCII) + 0.toByte(), path)
    }

    /** Reads [length] bytes at [offset] of the currently loaded object. */
    fun readChunk(offset: Int, length: Int): ByteArray = guarded {
        val cmd = CMD_DOWNLOAD.copyOf(7)
        ByteBuffer.wrap(cmd, 3, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(offset)
        val chunk = command(cmd, length, FLAG_RECV)
        if (chunk.isEmpty()) throw CameraException("The camera sent no data at offset $offset")
        chunk
    }

    private fun readAll(size: Int, progress: ((Int, Int) -> Unit)?, cancel: (() -> Boolean)?): ByteArray {
        val out = ByteArray(size)
        var done = 0
        while (done < size) {
            if (cancel?.invoke() == true) throw CancelledException()
            val chunk = readChunk(done, minOf(CHUNK, size - done))
            val n = minOf(chunk.size, size - done)
            System.arraycopy(chunk, 0, out, done, n)
            done += n
            progress?.invoke(done, size)
        }
        return out
    }

    fun readFile(
        path: String,
        progress: ((Int, Int) -> Unit)? = null,
        cancel: (() -> Boolean)? = null,
    ): ByteArray = readAll(loadFile(path), progress, cancel)

    // ------------------------------------------------------------------ commands
    fun hardwareInfo(): HardwareInfo {
        val d = readAll(load(CMD_LOAD_HWINFO, EMPTY, KEY_HWINFO), null, null)
        return parseHardwareInfo(d)
    }

    fun listPictures(): List<Picture> {
        val d = readAll(load(CMD_LOAD_PICLIST, EMPTY, KEY_PICLIST), null, null)
        return parsePictureList(d)
    }

    companion object {
        const val DEFAULT_HOST = "10.100.1.1"
        const val DEFAULT_PORT = 5678
        const val CHUNK = 0x10000
        private const val HEADER = 28
        private const val MAGIC = 0xFAAA55AF.toInt()
        private const val MAX_REPLY = 32 * 1024 * 1024
        private const val FLAG_SEND = 0
        private const val FLAG_RECV = 1
        private val EMPTY = ByteArray(0)
        private const val KEY_HWINFO = "<hwinfo>"
        private const val KEY_PICLIST = "<piclist>"

        // Safe, known commands only.
        private val CMD_LOAD_HWINFO = byteArrayOf(0xC2.toByte(), 0x00, 0x00)
        private val CMD_LOAD_FILE = byteArrayOf(0xC2.toByte(), 0x00, 0x01)
        private val CMD_LOAD_PICLIST = byteArrayOf(0xC2.toByte(), 0x00, 0x02)
        private val CMD_QUERY_SIZE = byteArrayOf(0xC6.toByte(), 0x00)
        private val CMD_DOWNLOAD = byteArrayOf(0xC4.toByte(), 0x00, 0x00) // + u32 LE offset at byte 3

        fun parseHardwareInfo(d: ByteArray): HardwareInfo {
            // null-padded strings (256 + 3 x 128 bytes) followed by a u32
            val s = String(d, 0, minOf(640, d.size), Charsets.US_ASCII)
                .split('\u0000').filter { it.isNotBlank() } + listOf("", "", "", "")
            return HardwareInfo(s[0], s[1], s[2], s[3])
        }

        fun parsePictureList(d: ByteArray): List<Picture> {
            if (d.size < 12) return emptyList()
            val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
            val entrySize = b.getInt(4)
            val fields = b.getInt(8)
            if (entrySize < 0x80) return emptyList()
            val start = 12 + fields * 8
            val pics = ArrayList<Picture>()
            var off = start
            fun str(a: Int, e: Int): String {
                var end = off + a
                while (end < off + e && d[end] != 0.toByte()) end++
                return String(d, off + a, end - off - a, Charsets.US_ASCII)
            }
            while (off + entrySize <= d.size) {
                pics += Picture(
                    folderSuffix = str(0x00, 0x08),
                    prefix = str(0x08, 0x10),
                    folderNum = b.getInt(off + 0x10),
                    fileNum = b.getInt(off + 0x14),
                    focusLambda = b.getFloat(off + 0x2C),
                    id = str(0x30, 0x60),
                    taken = str(0x60, 0x7C),
                )
                off += entrySize
            }
            return pics
        }
    }
}

open class CameraException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The camera answered, but has nothing for the requested path (e.g. deleted picture). */
class CameraFileMissingException(path: String) : CameraException("The camera has no file $path")

class CancelledException : RuntimeException("cancelled")

data class HardwareInfo(
    val manufacturer: String,
    val serial: String,
    val hardwareVersion: String,
    val firmware: String,
)

data class Picture(
    val folderNum: Int,
    val folderSuffix: String,
    val prefix: String,
    val fileNum: Int,
    /** `sha1-<40 hex>`, stable across connections. */
    val id: String,
    /** ISO 8601 UTC timestamp. */
    val taken: String,
    val focusLambda: Float,
) {
    /** Camera path without extension, e.g. `I:\DCIM\100PHOTO\IMG_0001`. */
    val basePath: String get() = "I:\\DCIM\\%03d%s\\%s%04d".format(folderNum, folderSuffix, prefix, fileNum)
    val name: String get() = "%s%04d".format(prefix, fileNum)
    val rawPath: String get() = "$basePath.RAW"
    val metadataPath: String get() = "$basePath.TXT"
    val thumbnailPath: String get() = "$basePath.128"
}
