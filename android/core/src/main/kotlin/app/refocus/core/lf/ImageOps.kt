package app.refocus.core.lf

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Single-channel float image operations, matching the OpenCV calls the desktop
 * decoder uses (default border BORDER_REFLECT_101 unless noted).
 * Images are row-major FloatArrays of size w*h.
 */
object ImageOps {

    fun reflect101(i: Int, n: Int): Int {
        if (n == 1) return 0
        var p = i
        while (p < 0 || p >= n) {
            if (p < 0) p = -p
            if (p >= n) p = 2 * n - 2 - p
        }
        return p
    }

    /** cv2.getGaussianKernel with ksize derived from sigma for float images. */
    fun gaussianKernel(sigma: Double): FloatArray {
        val ksize = ((sigma * 4 * 2 + 1).roundToInt()) or 1
        val k = FloatArray(ksize)
        val c = (ksize - 1) / 2.0
        var sum = 0.0
        val tmp = DoubleArray(ksize) { i ->
            val x = i - c
            exp(-x * x / (2 * sigma * sigma)).also { sum += it }
        }
        for (i in 0 until ksize) k[i] = (tmp[i] / sum).toFloat()
        return k
    }

    /** Separable convolution with a symmetric 1D kernel, in place. */
    fun separable(src: FloatArray, w: Int, h: Int, kernel: FloatArray) {
        val r = kernel.size / 2
        // horizontal pass, row parallel, in place via a padded row copy
        Parallel.forRange(h) { y0, y1 ->
            val row = FloatArray(w + 2 * r)
            for (y in y0 until y1) {
                val base = y * w
                for (i in 0 until w + 2 * r) row[i] = src[base + reflect101(i - r, w)]
                for (x in 0 until w) {
                    var s = 0f
                    for (t in kernel.indices) s += kernel[t] * row[x + t]
                    src[base + x] = s
                }
            }
        }
        // vertical pass in column blocks
        val block = 32
        val nBlocks = (w + block - 1) / block
        Parallel.forRange(nBlocks) { b0, b1 ->
            val col = FloatArray((h + 2 * r) * block)
            for (b in b0 until b1) {
                val x0 = b * block
                val bw = minOf(block, w - x0)
                for (i in 0 until h + 2 * r) {
                    val sy = reflect101(i - r, h) * w + x0
                    val di = i * block
                    for (j in 0 until bw) col[di + j] = src[sy + j]
                }
                for (y in 0 until h) {
                    val dst = y * w + x0
                    for (j in 0 until bw) {
                        var s = 0f
                        var idx = y * block + j
                        for (t in kernel.indices) {
                            s += kernel[t] * col[idx]
                            idx += block
                        }
                        src[dst + j] = s
                    }
                }
            }
        }
    }

    fun gaussianBlur(src: FloatArray, w: Int, h: Int, sigma: Double): FloatArray =
        src.copyOf().also { separable(it, w, h, gaussianKernel(sigma)) }

    fun gaussianBlurInPlace(src: FloatArray, w: Int, h: Int, sigma: Double) =
        separable(src, w, h, gaussianKernel(sigma))

    /** cv2.blur (normalised box filter) with a (2r+1)² window. */
    fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val k = 2 * r + 1
        return src.copyOf().also { separable(it, w, h, FloatArray(k) { 1f / k }) }
    }

    /** cv2.Laplacian(ksize=3): kernel [[2,0,2],[0,-8,0],[2,0,2]]. */
    fun laplacian3(src: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val ym = reflect101(y - 1, h) * w
            val yp = reflect101(y + 1, h) * w
            val yc = y * w
            for (x in 0 until w) {
                val xm = reflect101(x - 1, w)
                val xp = reflect101(x + 1, w)
                out[yc + x] = 2f * (src[ym + xm] + src[ym + xp] + src[yp + xm] + src[yp + xp]) - 8f * src[yc + x]
            }
        }
        return out
    }

    /** cv2.medianBlur(ksize=5) for float images (border replicate). */
    fun medianBlur5(src: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        Parallel.forRange(h) { y0, y1 ->
            val win = FloatArray(25)
            for (y in y0 until y1) for (x in 0 until w) {
                var n = 0
                for (dy in -2..2) {
                    val yy = (y + dy).coerceIn(0, h - 1) * w
                    for (dx in -2..2) win[n++] = src[yy + (x + dx).coerceIn(0, w - 1)]
                }
                win.sort()
                out[y * w + x] = win[12]
            }
        }
        return out
    }

    /** cv2.dilate with a 5×5 rectangle on a boolean mask. */
    fun dilate5(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var hit = false
            loop@ for (dy in -2..2) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                for (dx in -2..2) {
                    val xx = x + dx
                    if (xx in 0 until w && mask[yy * w + xx]) {
                        hit = true; break@loop
                    }
                }
            }
            out[y * w + x] = hit
        }
        return out
    }

    /** numpy.percentile with linear interpolation. */
    fun percentile(data: FloatArray, q: Double): Float = percentileSorted(data.copyOf().also { it.sort() }, q)

    fun percentileSorted(sorted: FloatArray, q: Double): Float {
        if (sorted.isEmpty()) return 0f
        val pos = q / 100.0 * (sorted.size - 1)
        val lo = floor(pos).toInt().coerceIn(0, sorted.size - 1)
        val hi = minOf(lo + 1, sorted.size - 1)
        val f = (pos - lo).toFloat()
        return sorted[lo] + (sorted[hi] - sorted[lo]) * f
    }

    fun percentiles(data: FloatArray, vararg q: Double): FloatArray {
        val s = data.copyOf().also { it.sort() }
        return FloatArray(q.size) { percentileSorted(s, q[it]) }
    }

    /**
     * cv2.resize(INTER_AREA) along the y axis only (downscale): every output row is the
     * overlap-weighted mean of the input rows it covers. [src] has [stride] floats per row.
     */
    fun resizeAreaRows(src: FloatArray, h: Int, stride: Int, newH: Int): FloatArray {
        val out = FloatArray(newH * stride)
        val scale = h.toDouble() / newH
        for (oy in 0 until newH) {
            val a = oy * scale
            val b = (oy + 1) * scale
            var sy = floor(a).toInt()
            val dst = oy * stride
            while (sy < b && sy < h) {
                val wgt = ((minOf(b, sy + 1.0) - maxOf(a, sy.toDouble())) / scale).toFloat()
                if (wgt > 0) {
                    val s = sy * stride
                    for (j in 0 until stride) out[dst + j] += wgt * src[s + j]
                }
                sy++
            }
        }
        return out
    }

    // ---------------------------------------------------------- interpolation
    fun lanczos3(x: Double): Double {
        if (x == 0.0) return 1.0
        val ax = abs(x)
        if (ax >= 3.0) return 0.0
        val px = PI * x
        return 3.0 * sin(px) * sin(px / 3.0) / (px * px)
    }

    /** Catmull-Rom weights for taps -1..2 at fractional position t. */
    fun catmullRomWeights(t: Double): FloatArray {
        val t2 = t * t
        val t3 = t2 * t
        return floatArrayOf(
            (-0.5 * t3 + t2 - 0.5 * t).toFloat(),
            (1.5 * t3 - 2.5 * t2 + 1.0).toFloat(),
            (-1.5 * t3 + 2.0 * t2 + 0.5 * t).toFloat(),
            (0.5 * t3 - 0.5 * t2).toFloat(),
        )
    }

    /** Lanczos-3 weights for taps -2..3 at fractional position t (normalised). */
    fun lanczosWeights(t: Double): FloatArray {
        val w = DoubleArray(6) { lanczos3(t - (it - 2)) }
        val s = w.sum()
        return FloatArray(6) { (w[it] / s).toFloat() }
    }

    /**
     * Interpolation taps for sampling a signal at position `x - d` for every
     * integer x: returns the integer base offset and per-tap weights.
     */
    class ShiftKernel(val base: Int, val first: Int, val weights: FloatArray)

    fun shiftKernel(d: Double, lanczos: Boolean): ShiftKernel {
        val p0 = floor(-d)
        val t = -d - p0
        return if (lanczos) ShiftKernel(p0.toInt(), -2, lanczosWeights(t))
        else ShiftKernel(p0.toInt(), -1, catmullRomWeights(t))
    }

    /**
     * Shifts a w×h plane by (dx, dy): out(x, y) = in(x - dx, y - dy), reflecting at the
     * borders. [tmp] must hold w*h floats.
     */
    fun shift(src: FloatArray, srcOff: Int, w: Int, h: Int, kx: ShiftKernel, ky: ShiftKernel, tmp: FloatArray, out: FloatArray) {
        val nx = kx.weights.size
        for (y in 0 until h) {
            val row = srcOff + y * w
            val dst = y * w
            for (x in 0 until w) {
                val b = x + kx.base + kx.first
                var s = 0f
                if (b >= 0 && b + nx <= w) {
                    for (t in 0 until nx) s += kx.weights[t] * src[row + b + t]
                } else {
                    for (t in 0 until nx) s += kx.weights[t] * src[row + reflect101(b + t, w)]
                }
                tmp[dst + x] = s
            }
        }
        val ny = ky.weights.size
        for (y in 0 until h) {
            val b = y + ky.base + ky.first
            val dst = y * w
            for (x in 0 until w) out[dst + x] = 0f
            for (t in 0 until ny) {
                val sy = reflect101(b + t, h) * w
                val wt = ky.weights[t]
                for (x in 0 until w) out[dst + x] += wt * tmp[sy + x]
            }
        }
    }

    // ----------------------------------------------------------------- resize
    /** High-quality (Lanczos-3) resize of ARGB pixels, used for export scaling. */
    fun resizeArgb(src: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
        if (nw == w && nh == h) return src.copyOf()
        val planes = Array(3) { c -> FloatArray(w * h) { i -> ((src[i] shr (16 - 8 * c)) and 0xFF).toFloat() } }
        val out = IntArray(nw * nh)
        val res = Array(3) { c -> resizePlane(planes[c], w, h, nw, nh) }
        for (i in out.indices) {
            val r = res[0][i].roundToInt().coerceIn(0, 255)
            val g = res[1][i].roundToInt().coerceIn(0, 255)
            val b = res[2][i].roundToInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    fun resizePlane(src: FloatArray, w: Int, h: Int, nw: Int, nh: Int): FloatArray {
        val horiz = resample1d(src, w, h, nw, horizontal = true)
        return resample1d(horiz, nw, h, nh, horizontal = false)
    }

    private fun resample1d(src: FloatArray, w: Int, h: Int, n: Int, horizontal: Boolean): FloatArray {
        val len = if (horizontal) w else h
        val outW = if (horizontal) n else w
        val outH = if (horizontal) h else n
        val out = FloatArray(outW * outH)
        val scale = n.toDouble() / len
        val support = if (scale < 1) 3.0 / scale else 3.0
        val filterScale = if (scale < 1) scale else 1.0
        // precompute taps
        val starts = IntArray(n)
        val taps = Array(n) { o ->
            val center = (o + 0.5) / scale - 0.5
            val a = floor(center - support).toInt() + 1
            val b = floor(center + support).toInt()
            starts[o] = a
            val wts = DoubleArray(b - a + 1) { lanczos3((a + it - center) * filterScale) }
            val s = wts.sum()
            FloatArray(wts.size) { (wts[it] / s).toFloat() }
        }
        Parallel.forRange(if (horizontal) h else n) { r0, r1 ->
            for (r in r0 until r1) {
                if (horizontal) {
                    for (o in 0 until n) {
                        var s = 0f
                        val t = taps[o]
                        for (i in t.indices) s += t[i] * src[r * w + (starts[o] + i).coerceIn(0, w - 1)]
                        out[r * outW + o] = s
                    }
                } else {
                    val t = taps[r]
                    for (x in 0 until w) {
                        var s = 0f
                        for (i in t.indices) s += t[i] * src[(starts[r] + i).coerceIn(0, h - 1) * w + x]
                        out[r * w + x] = s
                    }
                }
            }
        }
        return out
    }
}
