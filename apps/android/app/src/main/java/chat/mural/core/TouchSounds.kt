package chat.mural.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/** How a texture answers a finger: one hit, a sound that follows movement, or a sound that runs while held. */
enum class TouchBehavior { Tap, Scrub, Hold }

data class TouchTexture(val id: String, val behavior: TouchBehavior)

object TouchTextures {
    /** In the order the pads appear. The sound files for each live in assets/sounds, named after its id. */
    val all = listOf(
        // Slide: the sound follows the finger.
        TouchTexture("brush", TouchBehavior.Scrub),
        TouchTexture("brushwood", TouchBehavior.Scrub),
        TouchTexture("horsehair", TouchBehavior.Scrub),
        TouchTexture("broom", TouchBehavior.Scrub),
        TouchTexture("paper", TouchBehavior.Scrub),
        TouchTexture("newspaper", TouchBehavior.Scrub),
        TouchTexture("coat", TouchBehavior.Scrub),
        TouchTexture("cloth", TouchBehavior.Scrub),
        TouchTexture("foil", TouchBehavior.Scrub),
        TouchTexture("sponge", TouchBehavior.Scrub),
        TouchTexture("pencil", TouchBehavior.Scrub),
        TouchTexture("marker", TouchBehavior.Scrub),
        // Tap: one hit per touch.
        TouchTexture("mic", TouchBehavior.Tap),
        TouchTexture("finger", TouchBehavior.Tap),
        TouchTexture("key", TouchBehavior.Tap),
        TouchTexture("mouse", TouchBehavior.Tap),
        TouchTexture("pen", TouchBehavior.Tap),
        TouchTexture("coin", TouchBehavior.Tap),
        TouchTexture("glass", TouchBehavior.Tap),
        TouchTexture("knock", TouchBehavior.Tap),
        TouchTexture("drop", TouchBehavior.Tap),
        TouchTexture("switch", TouchBehavior.Tap),
        TouchTexture("bubblewrap", TouchBehavior.Tap),
        TouchTexture("typewriter", TouchBehavior.Tap),
        // Ambient: plays for as long as the finger stays down.
        TouchTexture("rain", TouchBehavior.Hold),
        TouchTexture("roof", TouchBehavior.Hold),
        TouchTexture("metal", TouchBehavior.Hold),
        TouchTexture("fire", TouchBehavior.Hold),
        TouchTexture("wind", TouchBehavior.Hold),
        TouchTexture("stream", TouchBehavior.Hold),
        TouchTexture("night", TouchBehavior.Hold),
        TouchTexture("waves", TouchBehavior.Hold),
        TouchTexture("boil", TouchBehavior.Hold),
        TouchTexture("rainstick", TouchBehavior.Hold),
        TouchTexture("cave", TouchBehavior.Hold),
        TouchTexture("crickets", TouchBehavior.Hold),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): TouchTexture? = byId[id]
}

enum class TouchPhase(val wire: String) { Down("down"), Move("move"), Up("up") }

/**
 * One finger event on one pad. Position is normalised to the pad: x runs from the left ear (0) to the
 * right ear (1) and y from the top (0) to the bottom (1). The time only needs to be consistent within
 * one sender, so the same event can later travel to a friend's phone unchanged.
 */
data class TouchEvent(val texture: String, val phase: TouchPhase, val x: Float, val y: Float, val timeMillis: Long)

data class StereoGain(val left: Float, val right: Float)

/** Pure mapping from finger position and speed to what the listener hears. */
object TouchMixer {
    /** Keeps the far ear from going completely silent at the edge of a pad, which is harsh on headphones. */
    private const val PAN_MARGIN = 0.1f
    const val MIN_RATE = 0.85f
    const val MAX_RATE = 1.15f

    /** Finger speed, in pad widths per second, that plays a sliding texture at full volume. */
    const val FULL_SPEED = 2.0f
    private const val ATTACK_SECONDS = 0.03f
    private const val RELEASE_SECONDS = 0.18f

    /** Equal-power pan: moving across the pad shifts the sound between ears without changing loudness. */
    fun pan(x: Float, gain: Float): StereoGain {
        val position = PAN_MARGIN + (1f - 2 * PAN_MARGIN) * x.coerceIn(0f, 1f)
        val angle = position * (PI.toFloat() / 2f)
        return StereoGain(cos(angle) * gain, sin(angle) * gain)
    }

    /** Higher on the pad plays slightly faster and brighter. */
    fun rate(y: Float): Float = MAX_RATE - (MAX_RATE - MIN_RATE) * y.coerceIn(0f, 1f)

    fun scrubTarget(speed: Float): Float = (speed / FULL_SPEED).coerceIn(0f, 1f)

    /** Rises quickly and falls slowly, so a stroke swells and fades like a real one. */
    fun smoothGain(current: Float, target: Float, dtSeconds: Float): Float {
        val tau = if (target > current) ATTACK_SECONDS else RELEASE_SECONDS
        return current + (target - current) * (1f - exp(-dtSeconds / tau))
    }
}

/** The platform side: plays loaded textures and adjusts them while they run. */
interface SoundOutput {
    fun isLoaded(textureId: String): Boolean

    /** Returns a handle for the running stream, or 0 when nothing started. */
    fun play(textureId: String, left: Float, right: Float, rate: Float, loop: Boolean): Int
    fun update(handle: Int, left: Float, right: Float, rate: Float)
    fun stop(handle: Int)
}

/**
 * Turns touch events into sound. Events are keyed by who sent them, so a friend's touches can share the
 * same engine as your own and both can be heard at once. Call from a single thread.
 */
class TouchSoundEngine(private val output: SoundOutput) {
    private class Active(
        val handle: Int,
        val texture: TouchTexture,
        var x: Float,
        var y: Float,
        var eventTimeMillis: Long,
        var lastMoveNowMillis: Long,
        var gain: Float,
    )

    private val active = mutableMapOf<String, Active>()

    val hasActiveStreams: Boolean get() = active.isNotEmpty()

    fun handle(source: String, event: TouchEvent, nowMillis: Long) {
        val texture = TouchTextures.byId(event.texture) ?: return
        val key = "$source/${texture.id}"
        when (event.phase) {
            TouchPhase.Down -> down(key, texture, event, nowMillis)
            TouchPhase.Move -> move(key, event, nowMillis)
            TouchPhase.Up -> release(key)
        }
    }

    private fun down(key: String, texture: TouchTexture, event: TouchEvent, nowMillis: Long) {
        release(key)
        if (!output.isLoaded(texture.id)) return
        val rate = TouchMixer.rate(event.y)
        when (texture.behavior) {
            TouchBehavior.Tap -> {
                val ears = TouchMixer.pan(event.x, TAP_GAIN)
                output.play(texture.id, ears.left, ears.right, rate, loop = false)
            }
            TouchBehavior.Hold -> start(key, texture, event, nowMillis, gain = HOLD_GAIN, rate)
            // A sliding texture starts silent and swells with movement.
            TouchBehavior.Scrub -> start(key, texture, event, nowMillis, gain = 0f, rate)
        }
    }

    private fun start(key: String, texture: TouchTexture, event: TouchEvent, nowMillis: Long, gain: Float, rate: Float) {
        val ears = TouchMixer.pan(event.x, gain)
        val handle = output.play(texture.id, ears.left, ears.right, rate, loop = true)
        if (handle != 0) active[key] = Active(handle, texture, event.x, event.y, event.timeMillis, nowMillis, gain)
    }

    private fun move(key: String, event: TouchEvent, nowMillis: Long) {
        val stream = active[key] ?: return
        if (stream.texture.behavior == TouchBehavior.Scrub) {
            val dt = ((event.timeMillis - stream.eventTimeMillis).coerceAtLeast(MIN_INTERVAL_MILLIS)) / 1000f
            val speed = hypot(event.x - stream.x, event.y - stream.y) / dt
            stream.gain = TouchMixer.smoothGain(stream.gain, TouchMixer.scrubTarget(speed), dt)
            stream.lastMoveNowMillis = nowMillis
        }
        stream.x = event.x
        stream.y = event.y
        stream.eventTimeMillis = event.timeMillis
        apply(stream)
    }

    /** Lets sliding textures fade out while a finger rests, since no move events arrive to lower them. */
    fun tick(nowMillis: Long, dtSeconds: Float) {
        for (stream in active.values) {
            if (stream.texture.behavior != TouchBehavior.Scrub || stream.gain == 0f) continue
            if (nowMillis - stream.lastMoveNowMillis < STILL_AFTER_MILLIS) continue
            stream.gain = TouchMixer.smoothGain(stream.gain, 0f, dtSeconds).let { if (it < SILENT) 0f else it }
            apply(stream)
        }
    }

    private fun apply(stream: Active) {
        val ears = TouchMixer.pan(stream.x, stream.gain)
        output.update(stream.handle, ears.left, ears.right, TouchMixer.rate(stream.y))
    }

    private fun release(key: String) {
        active.remove(key)?.let { output.stop(it.handle) }
    }

    fun stopSource(source: String) {
        active.keys.filter { it.startsWith("$source/") }.forEach(::release)
    }

    fun stopAll() {
        active.keys.toList().forEach(::release)
    }

    private companion object {
        const val TAP_GAIN = 0.9f
        const val HOLD_GAIN = 0.8f
        const val MIN_INTERVAL_MILLIS = 8L
        const val STILL_AFTER_MILLIS = 40L
        const val SILENT = 0.01f
    }
}
