package app.refocus.core

import app.refocus.core.camera.CameraException
import app.refocus.core.camera.LytroClient
import app.refocus.core.camera.thumbnailToArgb
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random

class LytroClientTest {

    private val raw = Random(1).nextBytes(300_000) // spans several 64 KiB chunks
    private val list = FakeCamera.pictureList(listOf(
        Triple(1, "sha1-66b7b07db7d6d295a7cf62f7cb7a29ec644e26ab", "2026-01-30T16:57:15.000Z"),
        Triple(2, "sha1-e9ea9b4cd479386596855fc8e0610da59e4c5e72", "2026-01-31T22:27:32.000Z"),
    ))
    private val files = mapOf(
        "I:\\DCIM\\100PHOTO\\IMG_0001.RAW" to raw,
        "I:\\DCIM\\100PHOTO\\IMG_0001.128" to ByteArray(32768) { 0x80.toByte() },
    )

    private fun client(cam: FakeCamera) = LytroClient {
        Socket().apply { connect(InetSocketAddress("127.0.0.1", cam.port), 2000); soTimeout = 5000 }
    }

    @Test
    fun listsPicturesAndHardware() = FakeCamera(files, list).use { cam ->
        val c = client(cam)
        val pics = c.listPictures()
        assertEquals(2, pics.size)
        assertEquals("IMG_0001", pics[0].name)
        assertEquals("I:\\DCIM\\100PHOTO\\IMG_0002", pics[1].basePath)
        assertEquals("sha1-e9ea9b4cd479386596855fc8e0610da59e4c5e72", pics[1].id)
        assertEquals("2026-01-31T22:27:32.000Z", pics[1].taken)
        assertEquals(1.5f, pics[0].focusLambda)
        assertEquals("Lytro, Inc.", c.hardwareInfo().manufacturer)
        // only known-safe commands were sent
        assertTrue(cam.commands.all { it in setOf("C2 00 00", "C2 00 01", "C2 00 02", "C6 00 00", "C4 00 00") })
    }

    @Test
    fun readsFilesInChunksAndResumes() = FakeCamera(files, list).use { cam ->
        val c = client(cam)
        assertArrayEquals(raw, c.readFile("I:\\DCIM\\100PHOTO\\IMG_0001.RAW"))
        // interleave: load another file, then resume the first one at an offset
        val size = c.loadFile("I:\\DCIM\\100PHOTO\\IMG_0001.RAW")
        val first = c.readChunk(0, LytroClient.CHUNK)
        c.readFile("I:\\DCIM\\100PHOTO\\IMG_0001.128")
        c.loadFile("I:\\DCIM\\100PHOTO\\IMG_0001.RAW")
        val second = c.readChunk(LytroClient.CHUNK, LytroClient.CHUNK)
        assertEquals(raw.size, size)
        assertArrayEquals(raw.copyOfRange(0, LytroClient.CHUNK), first)
        assertArrayEquals(raw.copyOfRange(LytroClient.CHUNK, 2 * LytroClient.CHUNK), second)
    }

    @Test(expected = CameraException::class)
    fun unknownFileFails() = FakeCamera(files, list).use { cam ->
        client(cam).readFile("I:\\DCIM\\100PHOTO\\IMG_0099.RAW")
        Unit
    }

    @Test
    fun greyThumbnail() {
        val px = thumbnailToArgb(ByteArray(32768) { 0x80.toByte() })
        assertEquals(128 * 128, px.size)
        assertEquals(0xFF808080.toInt(), px[0])
    }
}
