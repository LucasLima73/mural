package chat.mural.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchSoundsTest {
    private class FakeOutput(var loaded: Boolean = true) : SoundOutput {
        data class Play(val texture: String, val left: Float, val right: Float, val rate: Float, val loop: Boolean, val handle: Int)
        val plays = mutableListOf<Play>()
        val updates = mutableListOf<Triple<Int, Float, Float>>()
        val stops = mutableListOf<Int>()
        private var next = 1
        override fun isLoaded(textureId: String) = loaded
        override fun play(textureId: String, left: Float, right: Float, rate: Float, loop: Boolean): Int =
            next++.also { plays += Play(textureId, left, right, rate, loop, it) }
        override fun update(handle: Int, left: Float, right: Float, rate: Float) { updates += Triple(handle, left, right) }
        override fun stop(handle: Int) { stops += handle }
    }

    private val output = FakeOutput()
    private val engine = TouchSoundEngine(output)
    private fun event(texture: String, phase: TouchPhase, x: Float = .5f, y: Float = .5f, at: Long = 0) =
        TouchEvent(texture, phase, x, y, at)

    @Test fun panKeepsLoudnessConstantAndFollowsTheFinger() {
        val left = TouchMixer.pan(0f, 1f)
        val middle = TouchMixer.pan(.5f, 1f)
        val right = TouchMixer.pan(1f, 1f)
        assertTrue(left.left > left.right)
        assertTrue(right.right > right.left)
        assertEquals(middle.left, middle.right, 1e-6f)
        listOf(left, middle, right).forEach { assertEquals(1f, it.left * it.left + it.right * it.right, 1e-4f) }
    }

    @Test fun panNeverSilencesTheFarEarCompletely() {
        assertTrue(TouchMixer.pan(0f, 1f).right > 0f)
        assertTrue(TouchMixer.pan(1f, 1f).left > 0f)
    }

    @Test fun panAndRateStayInRangeForPositionsOutsideThePad() {
        val ears = TouchMixer.pan(7f, 1f)
        assertTrue(ears.left in 0f..1f && ears.right in 0f..1f)
        assertEquals(TouchMixer.MAX_RATE, TouchMixer.rate(-3f), 0f)
        assertEquals(TouchMixer.MIN_RATE, TouchMixer.rate(3f), 0f)
    }

    @Test fun scrubGainRisesFasterThanItFalls() {
        val up = TouchMixer.smoothGain(0f, 1f, .03f)
        val down = 1f - TouchMixer.smoothGain(1f, 0f, .03f)
        assertTrue(up > down)
        assertTrue(TouchMixer.scrubTarget(0f) == 0f && TouchMixer.scrubTarget(99f) == 1f)
    }

    @Test fun tapPlaysOneShotOnDownAndNothingElse() {
        engine.handle("you", event("key", TouchPhase.Down, x = 0f), 0)
        engine.handle("you", event("key", TouchPhase.Move), 10)
        engine.handle("you", event("key", TouchPhase.Up), 20)
        assertEquals(1, output.plays.size)
        assertFalse(output.plays.single().loop)
        assertTrue(output.plays.single().left > output.plays.single().right)
        assertTrue(output.stops.isEmpty() && output.updates.isEmpty() && !engine.hasActiveStreams)
    }

    @Test fun holdLoopsWhileTheFingerIsDownAndStopsOnUp() {
        engine.handle("you", event("rain", TouchPhase.Down), 0)
        assertTrue(output.plays.single().loop && output.plays.single().left > 0f)
        engine.handle("you", event("rain", TouchPhase.Move, x = 1f, at = 16), 16)
        assertTrue(output.updates.single().third > output.updates.single().second)
        engine.handle("you", event("rain", TouchPhase.Up, at = 32), 32)
        assertEquals(listOf(1), output.stops)
        assertFalse(engine.hasActiveStreams)
    }

    @Test fun slidingTextureStartsSilentAndSwellsOnlyWhenTheFingerMoves() {
        engine.handle("you", event("brush", TouchPhase.Down), 0)
        assertEquals(0f, output.plays.single().left, 0f)
        assertEquals(0f, output.plays.single().right, 0f)
        engine.handle("you", event("brush", TouchPhase.Move, x = .8f, at = 30), 30)
        val moving = output.updates.last()
        assertTrue(moving.second + moving.third > 0f)
    }

    @Test fun slidingTextureFadesToSilenceWhenTheFingerRests() {
        engine.handle("you", event("brush", TouchPhase.Down), 0)
        engine.handle("you", event("brush", TouchPhase.Move, x = 1f, at = 30), 30)
        var now = 30L
        repeat(60) { now += 20; engine.tick(now, .02f) }
        val rest = output.updates.last()
        assertEquals(0f, rest.second, 0f)
        assertEquals(0f, rest.third, 0f)
        assertTrue(engine.hasActiveStreams)
    }

    @Test fun tickLeavesAFreshStrokeAlone() {
        engine.handle("you", event("brush", TouchPhase.Down), 0)
        engine.handle("you", event("brush", TouchPhase.Move, x = 1f, at = 30), 30)
        val before = output.updates.size
        engine.tick(50, .02f)
        assertEquals(before, output.updates.size)
    }

    @Test fun eachSenderAndPadKeepsItsOwnStream() {
        engine.handle("you", event("rain", TouchPhase.Down), 0)
        engine.handle("friend", event("rain", TouchPhase.Down), 0)
        engine.handle("you", event("fire", TouchPhase.Down), 0)
        assertEquals(3, output.plays.size)
        engine.handle("friend", event("rain", TouchPhase.Up), 10)
        assertEquals(listOf(2), output.stops)
        engine.stopSource("you")
        assertEquals(listOf(2, 1, 3), output.stops)
        assertFalse(engine.hasActiveStreams)
    }

    @Test fun aRepeatedDownReplacesTheStreamInsteadOfLeakingIt() {
        engine.handle("you", event("rain", TouchPhase.Down), 0)
        engine.handle("you", event("rain", TouchPhase.Down), 10)
        assertEquals(listOf(1), output.stops)
        engine.stopAll()
        assertEquals(listOf(1, 2), output.stops)
    }

    @Test fun unknownOrUnloadedTexturesAndStrayEventsAreIgnored() {
        engine.handle("you", event("missing", TouchPhase.Down), 0)
        engine.handle("you", event("rain", TouchPhase.Move), 0)
        engine.handle("you", event("rain", TouchPhase.Up), 0)
        output.loaded = false
        engine.handle("you", event("rain", TouchPhase.Down), 0)
        assertTrue(output.plays.isEmpty() && output.stops.isEmpty() && !engine.hasActiveStreams)
    }

    @Test fun everyTextureHasABehaviorAndAUniqueId() {
        assertEquals(TouchTextures.all.size, TouchTextures.all.map { it.id }.toSet().size)
        assertEquals(3, TouchTextures.all.map { it.behavior }.toSet().size)
    }
}
