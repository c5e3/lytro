package app.refocus.core.lf

import app.refocus.core.camera.CancelledException
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A decoded first-generation Lytro light field: [count] sub-aperture views of
 * [width]×[height] linear sRGB pixels, one pixel per microlens.
 *
 * Port of `lytro/lightfield.py`. Views are stored planar: view k, channel c starts
 * at `(k * 3 + c) * width * height`.
 */
class LightField(
    val width: Int,
    val height: Int,
    /** (u, v) pixel offset under the lenslet for every view, interleaved. */
    val offsets: FloatArray,
    val views: FloatArray,
    /** Pixels clipped on the sensor (in any view). */
    val saturated: BooleanArray,
    val gamma: Float,
) {
    val count: Int = offsets.size / 2
    val pixels: Int get() = width * height
    val radii = FloatArray(count) { hypot(offsets[2 * it], offsets[2 * it + 1]) }

    /** Per-pixel depth as a refocus slope (higher = nearer), once computed. */
    @Volatile var depth: FloatArray? = null
    @Volatile var depthConfidence: FloatArray? = null

    @Volatile private var depthRangeCache: Pair<Float, Float>? = null

    /** 2nd and 98th percentile of the depth map, for display normalisation. */
    val depthRange: Pair<Float, Float>?
        get() {
            depthRangeCache?.let { return it }
            val d = depth ?: return null
            return ImageOps.percentiles(d, 2.0, 98.0).let { it[0] to it[1] }.also { depthRangeCache = it }
        }

    fun viewOffset(k: Int, c: Int) = (k * 3 + c) * pixels

    // ---------------------------------------------------------------- render
    /**
     * Shift-and-add refocus on the CPU (used for exports; the viewer does the same on
     * the GPU). Returns linear RGB, planar (3 × w × h).
     */
    fun render(p: RenderParams): FloatArray {
        val n = pixels
        val weights = p.viewWeights(offsets)
        val active = (0 until count).filter { weights[it] > 0f }
        val parts = Parallel.map(active.size) { a, b ->
            val acc = FloatArray(3 * n)
            val tmp = FloatArray(n)
            val out = FloatArray(n)
            for (i in a until b) {
                val k = active[i]
                val kx = ImageOps.shiftKernel(p.slope * offsets[2 * k].toDouble(), lanczos = false)
                val ky = ImageOps.shiftKernel(p.slope * offsets[2 * k + 1].toDouble(), lanczos = false)
                val wk = weights[k]
                for (c in 0 until 3) {
                    ImageOps.shift(views, viewOffset(k, c), width, height, kx, ky, tmp, out)
                    val o = c * n
                    for (j in 0 until n) acc[o + j] += wk * out[j]
                }
            }
            acc
        }
        val total = active.sumOf { weights[it].toDouble() }.toFloat().coerceAtLeast(1e-6f)
        val result = FloatArray(3 * n)
        for (part in parts) for (j in result.indices) result[j] += part[j]
        for (j in result.indices) result[j] = max(result[j] / total, 0f)
        return result
    }

    fun toArgb(linear: FloatArray): IntArray {
        val n = pixels
        val lut = srgbLut()
        return IntArray(n) { i ->
            val r = lut[(min(linear[i], 1f) * LUT_MAX).toInt()]
            val g = lut[(min(linear[n + i], 1f) * LUT_MAX).toInt()]
            val b = lut[(min(linear[2 * n + i], 1f) * LUT_MAX).toInt()]
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private var lutCache: IntArray? = null
    private fun srgbLut(): IntArray = lutCache ?: IntArray(LUT_MAX + 1) {
        ((it / LUT_MAX.toFloat()).toDouble().pow(gamma.toDouble()) * 255 + 0.5).toInt().coerceIn(0, 255)
    }.also { lutCache = it }

    /** The depth map as a turbo heatmap (red = near, blue = far). */
    fun depthArgb(): IntArray? {
        val d = depth ?: return null
        val (lo, hi) = depthRange ?: return null
        return IntArray(pixels) { Colormap.turboArgb(((d[it] - lo) / (hi - lo + 1e-9f)).coerceIn(0f, 1f)) }
    }

    /** Photo and heatmap blended the way the desktop viewer does (45 / 55). */
    fun overlayArgb(photo: IntArray): IntArray? {
        val heat = depthArgb() ?: return null
        return IntArray(pixels) { i ->
            var out = 0xFF shl 24
            for (s in intArrayOf(16, 8, 0)) {
                val a = (photo[i] shr s) and 0xFF
                val b = (heat[i] shr s) and 0xFF
                out = out or ((0.45f * a + 0.55f * b).toInt().coerceIn(0, 255) shl s)
            }
            out
        }
    }

    /** 16-bit greyscale depth (near = bright), normalised to the full range. */
    fun depth16(): ShortArray? {
        val d = depth ?: return null
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in d) { lo = min(lo, v); hi = max(hi, v) }
        return ShortArray(pixels) { (((d[it] - lo) / (hi - lo + 1e-9f)) * 65535f).toInt().coerceIn(0, 65535).toShort() }
    }

    /** Refocus slope for a point in view coordinates: confidence-weighted median. */
    fun focusSlopeAt(x: Float, y: Float, win: Int = 4): Float? {
        val d = depth ?: return null
        val conf = depthConfidence ?: return null
        val xi = x.roundToInt()
        val yi = y.roundToInt()
        val y0 = max(yi - win, 0)
        val y1 = min(yi + win + 1, height)
        val x0 = max(xi - win, 0)
        val x1 = min(xi + win + 1, width)
        if (x0 >= x1 || y0 >= y1) return null
        val vals = ArrayList<Pair<Float, Float>>()
        for (yy in y0 until y1) for (xx in x0 until x1) vals += d[yy * width + xx] to conf[yy * width + xx] + 1e-6f
        vals.sortBy { it.first }
        val total = vals.sumOf { it.second.toDouble() }
        var cum = 0.0
        for ((v, wgt) in vals) {
            cum += wgt
            if (cum >= total / 2) return v
        }
        return vals.last().first
    }

    // ----------------------------------------------------------------- depth
    private fun greyViews(): Array<FloatArray> = Array(count) { k ->
        val n = pixels
        val r = viewOffset(k, 0)
        FloatArray(n) { (views[r + it] + views[r + n + it] + views[r + 2 * n + it]) / 3f }
    }

    /**
     * Per-pixel depth (as refocus slope) in two passes: a coarse sweep over the
     * whole range, then a fine sweep over the range the scene occupies.
     */
    fun computeDepth(coarse: Int = 17, fine: Int = 25, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }) {
        val total = (coarse + fine).toFloat()
        val grey = greyViews()
        val s0 = linspace(SLOPE_MIN, SLOPE_MAX, coarse)
        val (d0, c0) = sweep(grey, s0, { progress(it / total) }, cancelled)
        val cut = ImageOps.percentile(c0, 70.0)
        val good = d0.filterIndexed { i, _ -> c0[i] > cut }.toFloatArray()
        var lo: Float
        var hi: Float
        if (good.isNotEmpty()) {
            val p = ImageOps.percentiles(good, 3.0, 97.0); lo = p[0]; hi = p[1]
        } else {
            lo = SLOPE_MIN; hi = SLOPE_MAX
        }
        val margin = 2 * (s0[1] - s0[0])
        lo = max(lo - margin, SLOPE_MIN)
        hi = min(hi + margin, SLOPE_MAX)
        val (dRaw, conf) = sweep(grey, linspace(lo, hi, fine), { progress((coarse + it) / total) }, cancelled)
        var depth = ImageOps.medianBlur5(dRaw, width, height)

        // fill unreliable areas from confident neighbours (normalised convolution)
        val p90 = ImageOps.percentile(conf, 90.0) + 1e-9f
        val c = FloatArray(pixels) { (conf[it] / p90).coerceIn(0f, 1f).let { v -> v * v } }
        val dc = FloatArray(pixels) { depth[it] * c[it] }
        fun nconv(sigma: Double): Pair<FloatArray, FloatArray> {
            val num = ImageOps.gaussianBlur(dc, width, height, sigma)
            val den = ImageOps.gaussianBlur(c, width, height, sigma)
            return FloatArray(pixels) { num[it] / (den[it] + 1e-9f) } to den
        }
        val (dLocal, wLocalRaw) = nconv(4.0)
        val (dGlobal, _) = nconv(30.0)
        val wp90 = ImageOps.percentile(wLocalRaw, 90.0) + 1e-9f
        depth = FloatArray(pixels) {
            val wl = (wLocalRaw[it] / wp90).coerceIn(0f, 1f)
            val background = wl * dLocal[it] + (1 - wl) * dGlobal[it]
            c[it] * depth[it] + (1 - c[it]) * background
        }
        // edge-aware smoothing guided by the central view
        val centre = centreView()
        val gv = grey[centre]
        val guide = FloatArray(pixels) { sqrt(max(gv[it], 0f)) }
        val gmax = (guide.maxOrNull() ?: 1f) + 1e-9f
        for (i in guide.indices) guide[i] /= gmax
        this.depthConfidence = conf
        this.depth = guidedFilter(guide, depth, 6, 1e-3f)
        progress(1f)
    }

    fun centreView(): Int = (0 until count).minBy { radii[it] }

    private fun sweep(
        grey: Array<FloatArray>,
        slopes: FloatArray,
        progress: (Int) -> Unit,
        cancelled: () -> Boolean,
    ): Pair<FloatArray, FloatArray> {
        val n = pixels
        val ns = slopes.size
        val sel = (0 until count).filter { radii[it] <= RADIUS + 1e-6f }
        val defocus = Array(ns) { FloatArray(0) }
        val corresp = Array(ns) { FloatArray(0) }
        // per-worker buffers, reused for every slope (avoids ~25 MB of garbage per step)
        val nParts = Parallel.parts(sel.size)
        val sums = Array(nParts) { DoubleArray(n) }
        val sqs = Array(nParts) { DoubleArray(n) }
        val tmps = Array(nParts) { FloatArray(n) }
        val outs = Array(nParts) { FloatArray(n) }
        for (si in 0 until ns) {
            if (cancelled()) throw CancelledException()
            val sl = slopes[si].toDouble()
            val parts = Parallel.mapParts(sel.size) { part, a, b ->
                val sum = sums[part].also { it.fill(0.0) }
                val sq = sqs[part].also { it.fill(0.0) }
                val tmp = tmps[part]
                val out = outs[part]
                for (i in a until b) {
                    val k = sel[i]
                    val kx = ImageOps.shiftKernel(sl * offsets[2 * k], lanczos = true)
                    val ky = ImageOps.shiftKernel(sl * offsets[2 * k + 1], lanczos = true)
                    ImageOps.shift(grey[k], 0, width, height, kx, ky, tmp, out)
                    for (j in 0 until n) {
                        val v = out[j].toDouble()
                        sum[j] += v
                        sq[j] += v * v
                    }
                }
                sum to sq
            }
            val k = sel.size.toDouble()
            val mean = FloatArray(n)
            val ratio = FloatArray(n)
            for (j in 0 until n) {
                var s = 0.0
                var q = 0.0
                for (p in parts) { s += p.first[j]; q += p.second[j] }
                val m = s / k
                val variance = max(q / k - m * m, 0.0)
                val mc = max(m, 0.0)
                mean[j] = sqrt(mc).toFloat()
                ratio[j] = (variance / (mc * mc + 1e-4)).toFloat()
            }
            val lap = ImageOps.laplacian3(mean, width, height)
            for (j in 0 until n) lap[j] *= lap[j]
            ImageOps.gaussianBlurInPlace(lap, width, height, 1.5)
            ImageOps.gaussianBlurInPlace(ratio, width, height, 1.5)
            defocus[si] = lap
            corresp[si] = ratio
            progress(si + 1)
        }

        val depth = FloatArray(n)
        val conf = FloatArray(n)
        val texture = FloatArray(n)
        val peak = FloatArray(n)
        val step = if (ns > 1) slopes[1] - slopes[0] else 0f
        Parallel.forRange(n) { a, b ->
            val score = FloatArray(ns)
            val sorted = FloatArray(ns)
            for (j in a until b) {
                var dLo = Float.MAX_VALUE; var dHi = -Float.MAX_VALUE
                var cLo = Float.MAX_VALUE; var cHi = -Float.MAX_VALUE
                for (s in 0 until ns) {
                    val dv = defocus[s][j]; val cv = corresp[s][j]
                    if (dv < dLo) dLo = dv; if (dv > dHi) dHi = dv
                    if (cv < cLo) cLo = cv; if (cv > cHi) cHi = cv
                }
                var best = 0
                for (s in 0 until ns) {
                    score[s] = (defocus[s][j] - dLo) / (dHi - dLo + 1e-9f) + 1f - (corresp[s][j] - cLo) / (cHi - cLo + 1e-9f)
                    if (score[s] > score[best]) best = s
                }
                val i0 = best.coerceIn(1, ns - 2)
                val sa = score[i0 - 1]; val sb = score[i0]; val sc = score[i0 + 1]
                val den = sa - 2 * sb + sc
                val frac = if (kotlin.math.abs(den) > 1e-6f) (0.5f * (sa - sc) / den).coerceIn(-0.5f, 0.5f) else 0f
                depth[j] = slopes[i0] + frac * step
                System.arraycopy(score, 0, sorted, 0, ns)
                sorted.sort()
                val median = if (ns % 2 == 1) sorted[ns / 2] else 0.5f * (sorted[ns / 2 - 1] + sorted[ns / 2])
                peak[j] = sorted[ns - 1] - median
                texture[j] = dHi
            }
        }
        val t90 = ImageOps.percentile(texture, 90.0) + 1e-12f
        for (j in 0 until n) {
            conf[j] = if (saturated[j]) 0f else max(peak[j] * sqrt(texture[j] / t90), 0f)
        }
        return depth to conf
    }

    private fun guidedFilter(guide: FloatArray, p: FloatArray, r: Int, eps: Float): FloatArray {
        val n = pixels
        val meanI = ImageOps.boxBlur(guide, width, height, r)
        val meanP = ImageOps.boxBlur(p, width, height, r)
        val ip = FloatArray(n) { guide[it] * p[it] }
        val ii = FloatArray(n) { guide[it] * guide[it] }
        val corrIp = ImageOps.boxBlur(ip, width, height, r)
        val corrI = ImageOps.boxBlur(ii, width, height, r)
        val a = FloatArray(n) { (corrIp[it] - meanI[it] * meanP[it]) / (corrI[it] - meanI[it] * meanI[it] + eps) }
        val b = FloatArray(n) { meanP[it] - a[it] * meanI[it] }
        val ma = ImageOps.boxBlur(a, width, height, r)
        val mb = ImageOps.boxBlur(b, width, height, r)
        return FloatArray(n) { ma[it] * guide[it] + mb[it] }
    }

    companion object {
        /** Sub-aperture sampling radius inside each lenslet image (pixels). */
        const val RADIUS = 4.0f
        const val SLOPE_MIN = -1.6f
        const val SLOPE_MAX = 1.6f
        private const val LUT_MAX = 4095
        private val SQRT3_2 = sqrt(3.0) / 2.0

        fun linspace(a: Float, b: Float, n: Int) = FloatArray(n) { if (n == 1) a else a + (b - a) * it / (n - 1) }

        // ------------------------------------------------------------ decode
        /**
         * Decodes a packed 12-bit RAW into sub-aperture views. [progress] receives
         * values in [0, 1]; [cancelled] is polled between steps.
         */
        fun decode(
            raw: ByteArray,
            meta: LfMetadata,
            progress: (Float) -> Unit = {},
            cancelled: () -> Boolean = { false },
        ): LightField {
            fun check() { if (cancelled()) throw CancelledException() }
            val w = meta.width
            val h = meta.height
            require(raw.size >= w * h * 3 / 2) { "RAW file is truncated (${raw.size} bytes)" }
            val rgb = demosaic(raw, meta)
            check(); progress(0.25f)

            val lum = FloatArray(w * h) { (rgb[0][it] + rgb[1][it] + rgb[2][it]) / 3f }
            ImageOps.gaussianBlurInPlace(lum, w, h, 1.0)
            check(); progress(0.4f)
            val grid = Grid(meta)
            grid.refine(lum, w, h)
            check(); progress(0.5f)
            return extractViews(rgb, meta, grid, progress, ::check)
        }

        private fun demosaic(raw: ByteArray, meta: LfMetadata): Array<FloatArray> {
            val w = meta.width
            val h = meta.height
            val bayer = ShortArray(w * h)
            Parallel.forRange(w * h / 2) { a, b ->
                for (i in a until b) {
                    val b0 = raw[3 * i].toInt() and 0xFF
                    val b1 = raw[3 * i + 1].toInt() and 0xFF
                    val b2 = raw[3 * i + 2].toInt() and 0xFF
                    bayer[2 * i] = ((b0 shl 4) or (b1 shr 4)).toShort()
                    bayer[2 * i + 1] = (((b1 and 0x0F) shl 8) or b2).toShort()
                }
            }
            // colour of the pixel at (row parity, col parity): 0 = R, 1 = G, 2 = B
            val cfa = when (meta.upperLeftPixel.lowercase()) {
                "r" -> intArrayOf(0, 1, 1, 2)
                "gr" -> intArrayOf(1, 0, 2, 1)
                "gb" -> intArrayOf(1, 2, 0, 1)
                else -> intArrayOf(2, 1, 1, 0) // "b"
            }
            val blk = floatArrayOf(meta.black.r, meta.black.g, meta.black.b)
            val wht = floatArrayOf(meta.white.r, meta.white.g, meta.white.b)
            val gain = floatArrayOf(meta.whiteBalance.r, meta.whiteBalance.g, meta.whiteBalance.b)
            val scale = FloatArray(3) { gain[it] / (wht[it] - blk[it]) }
            val out = Array(3) { FloatArray(w * h) }
            Parallel.forRange(h) { y0, y1 ->
                val v = FloatArray(3)
                for (y in y0 until y1) {
                    val ym = ImageOps.reflect101(y - 1, h) * w
                    val yp = ImageOps.reflect101(y + 1, h) * w
                    val yc = y * w
                    for (x in 0 until w) {
                        val xm = ImageOps.reflect101(x - 1, w)
                        val xp = ImageOps.reflect101(x + 1, w)
                        fun p(o: Int) = (bayer[o].toInt() and 0xFFFF).toFloat()
                        val here = cfa[(y and 1) * 2 + (x and 1)]
                        val cross = (p(ym + x) + p(yp + x) + p(yc + xm) + p(yc + xp)) * 0.25f
                        val diag = (p(ym + xm) + p(ym + xp) + p(yp + xm) + p(yp + xp)) * 0.25f
                        val horiz = (p(yc + xm) + p(yc + xp)) * 0.5f
                        val vert = (p(ym + x) + p(yp + x)) * 0.5f
                        v[here] = p(yc + x)
                        if (here == 1) {
                            // green: the colour sharing this row is left/right
                            val rowColour = cfa[(y and 1) * 2 + ((x + 1) and 1)]
                            v[rowColour] = horiz
                            v[2 - rowColour] = vert
                        } else {
                            v[1] = cross
                            v[2 - here] = diag
                        }
                        for (c in 0 until 3) out[c][yc + x] = max((v[c] - blk[c]) * scale[c], 0f)
                    }
                }
            }
            return out
        }

        private class Grid(val meta: LfMetadata) {
            var offX = meta.sensorOffsetX
            var offY = meta.sensorOffsetY
            val p = meta.lensPitch
            val nr = (meta.height / (p * SQRT3_2) / 2).toInt() + 2
            val nc = (meta.width / p / 2).toInt() + 2
            val rows = 2 * nr + 1
            val cols = 2 * nc + 1
            val c = cos(meta.rotation)
            val s = sin(meta.rotation)

            /** Lenslet centres (X, Y) on the sensor, indexed [row * cols + col]. */
            fun centres(ox: Double, oy: Double): Pair<DoubleArray, DoubleArray> {
                val xs = DoubleArray(rows * cols)
                val ys = DoubleArray(rows * cols)
                val cx = (meta.width - 1) / 2.0 + ox
                val cy = (meta.height - 1) / 2.0 + oy
                for (r in 0 until rows) {
                    val jj = r - nr
                    val odd = Math.floorMod(jj, 2)
                    for (q in 0 until cols) {
                        val ii = q - nc
                        val x = (ii + odd * 0.5) * p * meta.scaleX
                        val y = jj * p * SQRT3_2 * meta.scaleY
                        xs[r * cols + q] = c * x - s * y + cx
                        ys[r * cols + q] = s * x + c * y + cy
                    }
                }
                return xs to ys
            }

            fun rowIsOdd(r: Int) = Math.floorMod(r - nr, 2) == 1

            /**
             * Lenslet centres are the brightest point of each lenslet image
             * (vignetting): maximise the mean brightness sampled at predicted centres
             * in a bright region without clipped highlights.
             */
            fun refine(lum: FloatArray, w: Int, h: Int) {
                val t = 600
                val nty = h / t
                val ntx = w / t
                var best = -Double.MAX_VALUE
                var tx = 0
                var ty = 0
                for (a in 0 until nty) for (b in 0 until ntx) {
                    var sum = 0.0
                    var clipped = 0
                    for (y in a * t until (a + 1) * t) for (x in b * t until (b + 1) * t) {
                        val v = lum[y * w + x]
                        sum += v
                        if (v > 0.9f) clipped++
                    }
                    var mean = sum / (t * t)
                    if (clipped.toDouble() / (t * t) > 0.002) mean = -1.0
                    if (mean > best) { best = mean; tx = b; ty = a }
                }
                val x0 = tx * t + 20.0
                val y0 = ty * t + 20.0
                val x1 = x0 + t - 40
                val y1 = y0 + t - 40
                val steps = (0..12).map { -1.5 + 0.25 * it }
                val candidates = steps.flatMap { dx -> steps.map { dy -> dx to dy } }
                val scores = Parallel.map(candidates.size) { a, b ->
                    (a until b).map { i ->
                        val (dx, dy) = candidates[i]
                        val (xs, ys) = centres(offX + dx, offY + dy)
                        var sum = 0.0
                        var n = 0
                        for (j in xs.indices) {
                            val x = xs[j]; val y = ys[j]
                            if (x > x0 && x < x1 && y > y0 && y < y1) {
                                sum += bilinear(lum, w, x, y); n++
                            }
                        }
                        Triple(if (n > 0) sum / n else -1.0, dx, dy)
                    }
                }.flatten()
                // ties resolve like Python's max() over (score, dx, dy) tuples
                val top = scores.maxWith(compareBy<Triple<Double, Double, Double>> { it.first }.thenBy { it.second }.thenBy { it.third })
                offX += top.second
                offY += top.third
            }
        }

        private fun bilinear(img: FloatArray, w: Int, x: Double, y: Double): Double {
            val x0 = floor(x).toInt()
            val y0 = floor(y).toInt()
            val fx = x - x0
            val fy = y - y0
            val i = y0 * w + x0
            return img[i] * (1 - fx) * (1 - fy) + img[i + 1] * fx * (1 - fy) +
                img[i + w] * (1 - fx) * fy + img[i + w + 1] * fx * fy
        }

        private fun extractViews(
            rgb: Array<FloatArray>,
            meta: LfMetadata,
            grid: Grid,
            progress: (Float) -> Unit,
            check: () -> Unit,
        ): LightField {
            val w = meta.width
            val h = meta.height
            val (xs, ys) = grid.centres(grid.offX, grid.offY)
            val m = RADIUS + 2
            fun inside(r: Int, q: Int): Boolean {
                val x = xs[r * grid.cols + q]; val y = ys[r * grid.cols + q]
                return x > m && x < w - m - 1 && y > m && y < h - m - 1
            }
            // largest rectangle of lenslets fully on the sensor: start from the
            // rows/cols through the centre, then shrink until everything is inside
            val cr = grid.rows / 2
            val cc = grid.cols / 2
            var rows = (0 until grid.rows).filter { inside(it, cc) }
            var cols = (0 until grid.cols).filter { inside(cr, it) }
            while (rows.isNotEmpty() && cols.isNotEmpty() && !rows.all { r -> cols.all { q -> inside(r, q) } }) {
                rows = rows.subList(1, rows.size - 1)
                cols = cols.subList(1, cols.size - 1)
            }
            val nR = rows.size
            val nC = cols.size
            require(nR > 10 && nC > 10) { "Could not locate the microlens grid" }
            val outH = Math.rint(nR * SQRT3_2).toInt()
            val n = outH * nC

            val r = floor(RADIUS).toInt()
            val offs = ArrayList<Pair<Int, Int>>()
            for (v in -r..r) for (u in -r..r) if (u * u + v * v <= RADIUS * RADIUS) offs += u to v
            val count = offs.size
            val views = FloatArray(count * 3 * n)
            val cosR = cos(meta.rotation)
            val sinR = sin(meta.rotation)
            val done = java.util.concurrent.atomic.AtomicInteger()

            Parallel.forEach(count) { k ->
                check()
                val (u, v) = offs[k]
                val du = cosR * u - sinR * v
                val dv = sinR * u + cosR * v
                val hex = FloatArray(nR * nC)
                for (c in 0 until 3) {
                    val img = rgb[c]
                    for (ri in 0 until nR) for (qi in 0 until nC) {
                        val gi = rows[ri] * grid.cols + cols[qi]
                        hex[ri * nC + qi] = bilinear(img, w, xs[gi] + du, ys[gi] + dv).toFloat()
                    }
                    // odd rows were sampled at x = i + 0.5: interpolate back to x = i
                    for (ri in 0 until nR) {
                        if (!grid.rowIsOdd(rows[ri])) continue
                        val base = ri * nC
                        for (qi in nC - 1 downTo 1) hex[base + qi] = 0.5f * (hex[base + qi - 1] + hex[base + qi])
                    }
                    val rect = ImageOps.resizeAreaRows(hex, nR, nC, outH)
                    System.arraycopy(rect, 0, views, (k * 3 + c) * n, n)
                }
                progress(0.5f + 0.4f * done.incrementAndGet() / count)
            }
            check()

            // crude devignetting: equalise each view's brightness to the central view
            val means = DoubleArray(count) { k ->
                var s = 0.0
                val o = k * 3 * n
                for (j in 0 until 3 * n) s += views[o + j]
                s / (3 * n)
            }
            val centre = offs.indexOf(0 to 0)
            for (k in 0 until count) {
                val f = (means[centre] / max(means[k], 1e-6)).toFloat()
                val o = k * 3 * n
                for (j in 0 until 3 * n) views[o + j] *= f
            }

            // pixels that were clipped on the sensor (in any view)
            val clipped = BooleanArray(n)
            for (k in 0 until count) for (c in 0 until 3) {
                val o = (k * 3 + c) * n
                for (j in 0 until n) if (views[o + j] > 0.95f) clipped[j] = true
            }
            val saturated = ImageOps.dilate5(clipped, nC, outH)

            // colour: camera RGB -> sRGB primaries, plus exposure bias (still linear)
            val ccm = meta.ccm
            val e = meta.exposure
            Parallel.forEach(count) { k ->
                val o = k * 3 * n
                for (j in 0 until n) {
                    val a = views[o + j]; val b = views[o + n + j]; val cc2 = views[o + 2 * n + j]
                    views[o + j] = max((ccm[0] * a + ccm[1] * b + ccm[2] * cc2) * e, 0f)
                    views[o + n + j] = max((ccm[3] * a + ccm[4] * b + ccm[5] * cc2) * e, 0f)
                    views[o + 2 * n + j] = max((ccm[6] * a + ccm[7] * b + ccm[8] * cc2) * e, 0f)
                }
            }
            progress(1f)
            val offsets = FloatArray(count * 2) { if (it % 2 == 0) offs[it / 2].first.toFloat() else offs[it / 2].second.toFloat() }
            return LightField(nC, outH, offsets, views, saturated, meta.gamma)
        }
    }
}

/**
 * Rendering parameters shared by the GPU viewer and the CPU exporter.
 * [aperture] is the synthetic aperture radius in lenslet pixels (0 = pinhole,
 * [LightField.RADIUS] = the real f/2 lens); ([centerU], [centerV]) moves the
 * synthetic aperture across the lens for a perspective shift.
 */
data class RenderParams(
    val slope: Float = 0f,
    val aperture: Float = LightField.RADIUS,
    val centerU: Float = 0f,
    val centerV: Float = 0f,
) {
    /** Soft-edged aperture: views fade in and out instead of popping. */
    fun viewWeights(offsets: FloatArray): FloatArray {
        val n = offsets.size / 2
        val dist = FloatArray(n) { k -> hypot(offsets[2 * k] - centerU, offsets[2 * k + 1] - centerV) }
        val w = FloatArray(n) { k -> (aperture + 0.5f - dist[k]).coerceIn(0f, 1f) }
        // a pinhole between two views: use the nearest one
        if (w.all { it <= 0f } && n > 0) w[(0 until n).minBy { dist[it] }] = 1f
        return w
    }

    companion object {
        const val FULL_APERTURE_F = 2.0f

        /** How far the aperture centre may move for a given aperture radius. */
        fun maxCenterOffset(aperture: Float) = max(LightField.RADIUS - aperture, 0f)

        /** f-number for an aperture radius, or null for a pinhole. */
        fun fNumber(aperture: Float): Float? = if (aperture < 0.75f) null else FULL_APERTURE_F * LightField.RADIUS / aperture
    }
}
