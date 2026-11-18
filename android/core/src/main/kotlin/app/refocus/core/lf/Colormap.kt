package app.refocus.core.lf

/**
 * Google's "Turbo" colormap (polynomial approximation by Ruofei Du), the same
 * formula the viewer's shader uses, so exports match the screen.
 */
object Colormap {
    fun turbo(x: Float): FloatArray {
        val t = x.coerceIn(0f, 1f)
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t2 * t2
        val t5 = t4 * t
        val r = 0.13572138f + 4.61539260f * t - 42.66032258f * t2 + 132.13108234f * t3 - 152.94239396f * t4 + 59.28637943f * t5
        val g = 0.09140261f + 2.19418839f * t + 4.84296658f * t2 - 14.18503333f * t3 + 4.27729857f * t4 + 2.82956604f * t5
        val b = 0.10667330f + 12.64194608f * t - 60.58204836f * t2 + 110.36276771f * t3 - 89.90310912f * t4 + 27.34824973f * t5
        return floatArrayOf(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    fun turboArgb(x: Float): Int {
        val c = turbo(x)
        return (0xFF shl 24) or ((c[0] * 255 + 0.5f).toInt() shl 16) or ((c[1] * 255 + 0.5f).toInt() shl 8) or (c[2] * 255 + 0.5f).toInt()
    }
}
