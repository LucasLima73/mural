package chat.mural.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import chat.mural.core.TouchMixer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/** Plays a brushing sound that travels from the left ear to the right and back, so headphones can be checked by ear. */
class PanDemo(context: Context) : AutoCloseable {
    private val pool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .build()
    private val loaded = CompletableDeferred<Boolean>()
    private val sound: Int

    init {
        pool.setOnLoadCompleteListener { _, _, status -> loaded.complete(status == 0) }
        sound = context.assets.openFd("sounds/brush.ogg").use { pool.load(it, 1) }
    }

    /** Returns when the sound has finished. `onPosition` is told where it is, from 0 (left ear) to 1 (right ear). */
    suspend fun play(onPosition: (Float) -> Unit) {
        if (!loaded.await()) return
        val stream = pool.play(sound, 0f, 0f, 1, -1, 1f)
        if (stream == 0) return
        try {
            val started = SystemClock.uptimeMillis()
            while (true) {
                val t = (SystemClock.uptimeMillis() - started) / DURATION_MILLIS.toFloat()
                if (t >= 1f) break
                val x = 0.5f - 0.5f * cos(t * 2 * PI).toFloat()
                // Fade in and out at the ends so the test starts and stops gently.
                val ears = TouchMixer.pan(x, GAIN * min(1f, min(t, 1f - t) * 8f))
                pool.setVolume(stream, ears.left, ears.right)
                onPosition(x)
                delay(FRAME_MILLIS)
            }
        } finally {
            pool.stop(stream)
        }
    }

    override fun close() = pool.release()

    private companion object {
        const val DURATION_MILLIS = 4_000L
        const val FRAME_MILLIS = 16L
        const val GAIN = 0.9f
    }
}
