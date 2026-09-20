package chat.mural.audio

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator

/** Small, short vibrations that go with what the finger does. Silent on phones without a vibration motor. */
class Haptics(context: Context) {
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }
    private var lastTick = 0L

    fun pop() = vibrate(18, 255)

    /** A light tick that gets stronger with `intensity` (0 to 1); calls closer than 50 ms apart are skipped. */
    fun tick(intensity: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTick < TICK_INTERVAL_MILLIS) return
        lastTick = now
        vibrate(7, (50 + 170 * intensity.coerceIn(0f, 1f)).toInt())
    }

    fun burst() {
        val motor = vibrator ?: return
        try {
            motor.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 28, 24, 46), intArrayOf(0, 255, 0, 170), -1))
        } catch (_: Exception) { }
    }

    private fun vibrate(millis: Long, amplitude: Int) {
        val motor = vibrator ?: return
        try {
            motor.vibrate(VibrationEffect.createOneShot(millis, if (motor.hasAmplitudeControl()) amplitude else VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { }
    }

    private companion object {
        const val TICK_INTERVAL_MILLIS = 50L
    }
}
