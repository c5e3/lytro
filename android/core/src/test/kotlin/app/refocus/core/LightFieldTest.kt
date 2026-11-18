package app.refocus.core

import app.refocus.core.export.Png16
import app.refocus.core.lf.Half
import app.refocus.core.lf.LfMetadata
import app.refocus.core.lf.LightField
import app.refocus.core.lf.LightFieldStore
import app.refocus.core.lf.RenderParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Compares the Kotlin decoder with the Python one, using a picture from the desktop
 * viewer's cache (skipped when the cache isn't there).
 */
class LightFieldTest {

    private val cache = File(System.getProperty("lytro.cache") ?: "../../cache")

    private fun samplePicture(): Triple<File, File, File>? {
        val dirs = cache.listFiles()?.filter { it.isDirectory } ?: return null
        for (d in dirs.sortedBy { it.name }) {
            val npz = d.listFiles()?.firstOrNull { it.name.endsWith(".lf.npz") } ?: continue
            val base = npz.name.removeSuffix(".lf.npz")
            val raw = File(d, "$base.RAW")
            val txt = File(d, "$base.TXT")
            if (raw.exists() && txt.exists()) return Triple(raw, txt, npz)
        }
        return null
    }

    private fun correlation(a: FloatArray, b: FloatArray): Double {
        val ma = a.average(); val mb = b.average()
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in a.indices) {
            val x = a[i] - ma; val y = b[i] - mb
            sab += x * y; saa += x * x; sbb += y * y
        }
        return sab / sqrt(saa * sbb)
    }

    @Test
    fun decodeMatchesPython() {
        val sample = samplePicture()
        assumeTrue("no cached sample picture", sample != null)
        val (rawFile, txtFile, npzFile) = sample!!
        val meta = LfMetadata.parse(txtFile.readBytes())
        val t0 = System.nanoTime()
        val lf = LightField.decode(rawFile.readBytes(), meta)
        val t1 = System.nanoTime()
        println("decode: ${(t1 - t0) / 1e6} ms, ${lf.width}x${lf.height}, ${lf.count} views")

        val py = readNpz(npzFile)
        val pv = py.getValue("views")
        val (k, h, w) = Triple(pv.shape[0], pv.shape[1], pv.shape[2])
        assertEquals("view count", k, lf.count)
        assertEquals("height", h, lf.height)
        assertEquals("width", w, lf.width)
        val grid = py.getValue("grid_offset").data
        println("grid offset python=(${grid[0]}, ${grid[1]})")

        // views: python (K,h,w,3) interleaved, ours planar
        var maxRel = 0.0
        val centre = lf.centreView()
        val ours = FloatArray(h * w)
        val theirs = FloatArray(h * w)
        for (kk in 0 until k) {
            for (c in 0 until 3) {
                for (j in 0 until h * w) {
                    ours[j] = lf.views[lf.viewOffset(kk, c) + j]
                    theirs[j] = pv.data[(kk * h * w + j) * 3 + c]
                }
                if (kk == centre || kk == 0) {
                    val r = correlation(ours, theirs)
                    println("view $kk channel $c correlation $r")
                    assertTrue("view $kk/$c correlation $r", r > 0.995)
                }
                val diff = ours.indices.sumOf { abs(ours[it] - theirs[it]).toDouble() } / ours.size
                val mag = theirs.sumOf { abs(it).toDouble() } / theirs.size
                maxRel = maxOf(maxRel, diff / (mag + 1e-9))
            }
        }
        println("max mean relative error over views: $maxRel")
        assertTrue("views differ by $maxRel", maxRel < 0.03)

        val t2 = System.nanoTime()
        lf.computeDepth()
        val t3 = System.nanoTime()
        println("depth: ${(t3 - t2) / 1e6} ms")
        py["depth"]?.let { pd ->
            val r = correlation(lf.depth!!, pd.data)
            println("depth correlation with python: $r")
            assertTrue("depth correlation $r", r > 0.85)
        }

        // render + store round trip
        val img = lf.render(RenderParams(slope = 0.3f, aperture = 4f))
        assertEquals(3 * lf.pixels, img.size)
        val tmp = File.createTempFile("lfstore", ".bin")
        val dtmp = File.createTempFile("lfstore", ".depth")
        try {
            LightFieldStore.saveViews(lf, tmp)
            LightFieldStore.saveDepth(lf, dtmp)
            val back = LightFieldStore.loadViews(tmp)!!
            assertTrue(LightFieldStore.loadDepth(back, dtmp))
            assertEquals(lf.count, back.count)
            assertEquals(lf.views[12345], back.views[12345], 1e-3f * maxOf(1f, lf.views[12345]))
            assertEquals(lf.focusSlopeAt(100f, 100f)!!, back.focusSlopeAt(100f, 100f)!!, 1e-6f)
        } finally {
            tmp.delete(); dtmp.delete()
        }

        // write previews for a visual check
        val out = File(System.getProperty("java.io.tmpdir"), "refocus-test").apply { mkdirs() }
        fun save(name: String, argb: IntArray) {
            val bi = java.awt.image.BufferedImage(lf.width, lf.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            bi.setRGB(0, 0, lf.width, lf.height, argb, 0, lf.width)
            ImageIO.write(bi, "png", File(out, name))
        }
        val photo = lf.toArgb(lf.render(RenderParams()))
        save("photo.png", photo)
        save("depth.png", lf.depthArgb()!!)
        save("overlay.png", lf.overlayArgb(photo)!!)
        save("pinhole.png", lf.toArgb(lf.render(RenderParams(aperture = 0f))))
        save("near.png", lf.toArgb(lf.render(RenderParams(slope = lf.depthRange!!.second))))
        println("previews in $out")
    }

    @Test
    fun halfRoundTrip() {
        for (v in floatArrayOf(0f, 1f, -2.5f, 0.1f, 65504f, 6.1e-5f, 3e-6f, 1e-8f)) {
            val back = Half.toFloat(Half.fromFloat(v))
            assertEquals("$v", v, back, maxOf(abs(v) * 1e-3f, 6e-8f))
        }
    }

    @Test
    fun depthPngIsReadable() {
        val w = 40; val h = 30
        val px = ShortArray(w * h) { (it * 50).toShort() }
        val png = ByteArrayOutputStream().also { Png16.write(it, w, h, px) }.toByteArray()
        val back = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(w, back.width)
        assertEquals(16, back.colorModel.pixelSize)
        assertEquals((10 * 50) and 0xFFFF, back.raster.getSample(10, 0, 0))
    }
}
