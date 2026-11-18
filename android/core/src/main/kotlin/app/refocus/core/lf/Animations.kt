package app.refocus.core.lf

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class AnimationKind { FocusSweep, PerspectiveOrbit, TapToTap }

/** Builds the per-frame render parameters of a seamlessly looping animation. */
object Animations {

    fun frames(
        kind: AnimationKind,
        lf: LightField,
        base: RenderParams,
        frameCount: Int,
        focusA: Float? = null,
        focusB: Float? = null,
    ): List<RenderParams> = List(frameCount) { i ->
        val t = i.toFloat() / frameCount
        // 0 → 1 → 0 with eased ends, so the loop has no visible seam
        val pingPong = ((1 - cos(2 * PI * t)) / 2).toFloat()
        when (kind) {
            AnimationKind.FocusSweep -> {
                val (lo, hi) = lf.depthRange ?: (LightField.SLOPE_MIN * 0.6f to LightField.SLOPE_MAX * 0.6f)
                base.copy(slope = hi + (lo - hi) * pingPong, aperture = max(base.aperture, 2f), centerU = 0f, centerV = 0f)
            }
            AnimationKind.PerspectiveOrbit -> {
                val ap = min(base.aperture, 1f)
                val r = RenderParams.maxCenterOffset(ap) * 0.9f
                val a = 2 * PI * t
                base.copy(aperture = ap, centerU = (r * cos(a)).toFloat(), centerV = (r * sin(a)).toFloat())
            }
            AnimationKind.TapToTap -> {
                val a = focusA ?: base.slope
                val b = focusB ?: base.slope
                base.copy(slope = a + (b - a) * pingPong, aperture = max(base.aperture, 2f), centerU = 0f, centerV = 0f)
            }
        }
    }
}
