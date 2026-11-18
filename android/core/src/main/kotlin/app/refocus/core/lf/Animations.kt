package app.refocus.core.lf

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class AnimationKind { FocusSweep, PerspectiveOrbit }

/** Builds the per-frame render parameters of an animation. */
object Animations {

    /**
     * Both animations keep the user's settings from [base]: the focus sweep keeps the
     * depth of field and moves the focus once from one end of the scene's depth to the
     * other ([nearToFar] sets the direction, eased at both ends); the perspective
     * orbit keeps focus and depth of field and circles the viewpoint as far as that
     * aperture allows, [cycles] times (each cycle a seamless loop).
     */
    fun frames(
        kind: AnimationKind,
        lf: LightField,
        base: RenderParams,
        frameCount: Int,
        nearToFar: Boolean = true,
        cycles: Int = 1,
    ): List<RenderParams> = List(frameCount) { i ->
        when (kind) {
            AnimationKind.FocusSweep -> {
                // higher slope = nearer
                val (far, near) = lf.depthRange ?: (LightField.SLOPE_MIN * 0.6f to LightField.SLOPE_MAX * 0.6f)
                val (from, to) = if (nearToFar) near to far else far to near
                val t = if (frameCount > 1) i.toFloat() / (frameCount - 1) else 0f
                val eased = t * t * (3 - 2 * t)
                base.copy(slope = from + (to - from) * eased, centerU = 0f, centerV = 0f)
            }
            AnimationKind.PerspectiveOrbit -> {
                val r = RenderParams.maxCenterOffset(base.aperture) * 0.9f
                val a = 2 * PI * cycles * i / frameCount
                base.copy(centerU = (r * cos(a)).toFloat(), centerV = (r * sin(a)).toFloat())
            }
        }
    }
}
