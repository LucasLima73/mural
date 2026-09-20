package chat.mural.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A ring of springs standing in for a blob of slime. Every point sits at a distance from the centre, measured in
 * rest radii, and springs back to 1. Neighbouring points pull on each other so a push travels around the edge as a
 * ripple, and the average radius is held so that stretching one side thins the rest.
 */
class SoftBlob(val count: Int = 72) {
    /** How far each point is from its rest radius (0 is at rest, 0.5 is half a radius outward). */
    val offset = FloatArray(count)
    private val velocity = FloatArray(count)
    private val acceleration = FloatArray(count)
    private var touching = false
    private var fingerAngle = 0f
    private var fingerRadius = 0f

    /** How far the furthest point is stretched out, in rest radii. */
    val stretch: Float get() = max(0f, offset.max())

    /** The point's direction around the blob, in radians. */
    fun angle(index: Int): Float = (2 * PI * index / count).toFloat()

    /** Distance from the centre of the point at `angle`, in rest radii, smoothly interpolated. */
    fun radiusAt(angle: Float): Float {
        val position = ((angle / (2 * PI.toFloat())) % 1f + 1f) % 1f * count
        val lower = position.toInt() % count
        val fraction = position - position.toInt()
        return 1f + offset[lower] * (1 - fraction) + offset[(lower + 1) % count] * fraction
    }

    /** The finger is at (dx, dy), in rest radii from the centre. Near the edge it pulls; inside it dents. */
    fun touch(dx: Float, dy: Float) {
        touching = true
        fingerRadius = hypot(dx, dy)
        fingerAngle = kotlin.math.atan2(dy, dx)
    }

    fun release() {
        touching = false
    }

    /** A sudden press at (dx, dy): the near side flattens and the far side bulges, then it all wobbles back. */
    fun poke(dx: Float, dy: Float, strength: Float) {
        val direction = kotlin.math.atan2(dy, dx)
        val depth = 1f - min(hypot(dx, dy), 1f) * 0.45f
        for (i in 0 until count) velocity[i] -= strength * depth * cos(angle(i) - direction)
    }

    fun step(dt: Float) {
        val substeps = max(1, (dt / MAX_STEP).toInt() + 1)
        val h = dt / substeps
        repeat(substeps) { substep(h) }
    }

    private fun substep(h: Float) {
        val pulling = touching && fingerRadius > EDGE
        val denting = touching && fingerRadius in DENT_START..EDGE
        for (i in 0 until count) {
            val before = offset[(i - 1 + count) % count]
            val after = offset[(i + 1) % count]
            var a = -SPRING * offset[i] - DAMPING * velocity[i] + COUPLING * (before + after - 2 * offset[i])
            if (touching) {
                val gap = wrap(angle(i) - fingerAngle)
                // The further it is pulled, the narrower the pulled part gets, so it draws out into a long, thin tip.
                val sigma = if (pulling) SIGMA / (1f + 1.1f * max(0f, fingerRadius - 1f)) else SIGMA
                val weight = exp(-gap * gap / (2 * sigma * sigma))
                if (pulling) a += PULL * weight * (min(fingerRadius - 1f, MAX_STRETCH) - offset[i])
                else if (denting) a += PULL * 0.5f * weight * (-0.42f * (EDGE - fingerRadius) / EDGE - offset[i])
            }
            acceleration[i] = a
        }
        var mean = 0f
        for (i in 0 until count) {
            velocity[i] += acceleration[i] * h
            offset[i] += velocity[i] * h
            mean += offset[i]
        }
        mean /= count
        // Volume stays about the same: whatever one side gains the rest gives back.
        for (i in 0 until count) offset[i] -= mean * VOLUME
    }

    private fun wrap(angle: Float): Float {
        var a = angle
        while (a > PI) a -= (2 * PI).toFloat()
        while (a < -PI) a += (2 * PI).toFloat()
        return a
    }

    /** True while anything is still moving noticeably. */
    val moving: Boolean get() = touching || velocity.any { abs(it) > 0.01f } || offset.any { abs(it) > 0.004f }

    private companion object {
        const val SPRING = 150f
        const val DAMPING = 7f
        const val COUPLING = 520f
        const val PULL = 900f
        const val SIGMA = 0.5f
        const val EDGE = 0.86f
        const val DENT_START = 0.3f
        const val MAX_STRETCH = 1.9f
        const val VOLUME = 0.9f
        const val MAX_STEP = 1f / 240f
    }
}
