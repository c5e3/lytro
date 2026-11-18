package app.refocus.core

import app.refocus.core.camera.LytroClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses a picture list captured from the real camera (C2 00 02). */
class RealPictureListTest {
    @Test
    fun parsesCapturedList() {
        val bytes = javaClass.getResourceAsStream("/piclist-real.bin")!!.readBytes()
        val pics = LytroClient.parsePictureList(bytes)
        assertEquals((bytes.size - 92) / 128, pics.size)
        val first = pics.first()
        assertEquals("IMG_0001", first.name)
        assertEquals("I:\\DCIM\\100PHOTO\\IMG_0001", first.basePath)
        assertTrue(first.id.matches(Regex("sha1-[0-9a-f]{40}")))
        assertTrue(pics.all { it.taken.matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z""")) })
        println(pics.joinToString("\n") { "${it.name} ${it.id} ${it.taken} λ=${it.focusLambda}" })
    }
}
