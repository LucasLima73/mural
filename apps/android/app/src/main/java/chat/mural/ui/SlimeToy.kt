package chat.mural.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import chat.mural.R
import chat.mural.core.SoftBlob
import chat.mural.core.TouchMixer
import chat.mural.core.ToySounds
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private val SLIME_COLORS = listOf(0xFFFF7EB6, 0xFF52E3B0, 0xFFB388FF, 0xFF4FC3F7, 0xFFFFD54F).map { Color(it) }

/** Air bubbles trapped in the slime: where they sit (as a share of the radius and an angle) and how big they are. */
private class Trapped(val distance: Float, val angle: Float, val size: Float)

private val TRAPPED = List(18) { Trapped(0.1f + Random(7 + it).nextFloat() * 0.62f, Random(31 + it).nextFloat() * 2 * PI.toFloat(), 0.03f + Random(53 + it).nextFloat() * 0.07f) }

/** Slime you can press, poke and pull by the edge. It wobbles back, and the sound and buzz follow your finger. */
@Composable
fun SlimeToy(onBack: () -> Unit) {
    val output = rememberToyOutput(listOf(ToySounds.SQUISH, ToySounds.STRETCH))
    val haptics = rememberHaptics()
    val blob = remember { SoftBlob() }
    val sparkles = remember { mutableListOf<Particle>() }
    var colorIndex by rememberSaveable { mutableIntStateOf(0) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    var stretchHandle by remember { mutableIntStateOf(0) }
    var time by remember { mutableFloatStateOf(0f) }
    var tick by remember { mutableIntStateOf(0) }

    fun center() = Offset(size.width / 2f, size.height * 0.44f)
    fun rest() = min(size.width, size.height) * 0.27f
    fun squish(x: Float, rate: Float, gain: Float) {
        val ears = TouchMixer.pan(x / size.width.coerceAtLeast(1), gain)
        output.play(ToySounds.SQUISH, ears.left, ears.right, rate, false)
    }
    fun relative(at: Offset): Offset = Offset((at.x - center().x) / rest(), (at.y - center().y) / rest())

    FrameLoop { dt ->
        time += dt
        blob.step(dt)
        sparkles.step(dt, 500f)
        val pulled = finger != null && blob.stretch > 0.12f
        if (pulled) {
            val amount = min(1f, blob.stretch)
            val ears = TouchMixer.pan((finger?.x ?: 0f) / size.width.coerceAtLeast(1), 0.35f + 0.6f * amount)
            if (stretchHandle == 0) stretchHandle = output.play(ToySounds.STRETCH, ears.left, ears.right, 0.8f, true)
            else output.update(stretchHandle, ears.left, ears.right, 0.75f + 0.6f * amount)
            haptics.tick(amount)
        } else if (stretchHandle != 0) {
            output.stop(stretchHandle)
            stretchHandle = 0
        }
        tick++
    }

    ToyFrame(stringResource(R.string.toy_slime), stringResource(R.string.toy_slime_hint), onBack,
        Brush.verticalGradient(listOf(Color(0xFFFFE3F1), Color(0xFFD5CCFF)))) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { size = it }.testTag("slime-stage").pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val start = relative(down.position)
                if (hypot(start.x, start.y) > 1.4f) return@awaitEachGesture
                finger = down.position
                blob.touch(start.x, start.y)
                if (hypot(start.x, start.y) < 0.86f) {
                    blob.poke(start.x, start.y, 2.8f)
                    squish(down.position.x, 0.85f + Random.nextFloat() * 0.35f, 0.9f)
                    haptics.pop()
                    sparkles.burst(down.position.x, down.position.y, 6, rest() * 1.4f, rest() * 0.09f, listOf(Color.White, SLIME_COLORS[colorIndex].lighten(0.6f)), listOf(Particle.STAR, Particle.DOT), 0.6f)
                }
                do {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (change.pressed) {
                        val r = relative(change.position)
                        blob.touch(r.x, r.y)
                        finger = change.position
                        change.consume()
                    }
                } while (change.pressed)
                val snap = blob.stretch
                blob.release()
                finger = null
                if (snap > 0.25f) {
                    squish(down.position.x, 1.15f, min(1f, 0.4f + snap * 0.6f))
                    haptics.pop()
                }
            }
        }) {
            tick
            drawSlime(blob, center(), rest(), SLIME_COLORS[colorIndex], finger, time)
            drawParticles(sparkles)
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SLIME_COLORS.forEachIndexed { index, color ->
                val selected = index == colorIndex
                androidx.compose.foundation.layout.Box(Modifier.size(if (selected) 40.dp else 34.dp).background(color, CircleShape)
                    .border(if (selected) 3.dp else 1.dp, Color.White.copy(alpha = if (selected) 1f else 0.6f), CircleShape)
                    .semantics { contentDescription = "Colour ${index + 1}" }.testTag("slime-color-$index")
                    .clickable(role = Role.Button) { colorIndex = index; squish(size.width / 2f, 1.3f, 0.4f) })
            }
        }
    }
}

private fun blobPath(blob: SoftBlob, center: Offset, rest: Float): Path {
    val n = blob.count
    fun point(i: Int): Offset {
        val a = blob.angle(((i % n) + n) % n)
        val r = rest * (1f + blob.offset[((i % n) + n) % n])
        return Offset(center.x + cos(a) * r, center.y + sin(a) * r)
    }
    return Path().apply {
        val first = point(0)
        moveTo(first.x, first.y)
        for (i in 0 until n) {
            val p0 = point(i - 1)
            val p1 = point(i)
            val p2 = point(i + 1)
            val p3 = point(i + 2)
            cubicTo(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f, p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f, p2.x, p2.y)
        }
        close()
    }
}

private fun DrawScope.drawSlime(blob: SoftBlob, center: Offset, rest: Float, base: Color, finger: Offset?, time: Float) {
    // Before the stage has a size there is nothing to draw, and gradients cannot have a radius of zero.
    if (rest < 2f) return
    val path = blobPath(blob, center, rest)
    val squash = 1f + 0.06f * blob.stretch
    // A soft shadow on the surface it sits on.
    drawOval(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent), Offset(center.x, center.y + rest * 1.12f), rest * 1.1f),
        Offset(center.x - rest * 1.25f * squash, center.y + rest * 0.95f), Size(rest * 2.5f * squash, rest * 0.42f))
    // A glow around it, so it looks wet and thick.
    // Soft layers, wide and faint to narrow and stronger, so the edge fades out instead of ending in a hard line.
    drawPath(path, base.copy(alpha = 0.07f), style = Stroke(rest * 0.56f))
    drawPath(path, base.copy(alpha = 0.10f), style = Stroke(rest * 0.40f))
    drawPath(path, base.copy(alpha = 0.16f), style = Stroke(rest * 0.22f))
    drawPath(path, Brush.radialGradient(listOf(base.lighten(0.55f), base, base.darken(0.34f)), center + Offset(-rest * 0.36f, -rest * 0.46f), rest * 1.7f))
    clipPath(path) {
        // Shading that makes it look see-through and deep at the bottom.
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, base.darken(0.32f).copy(alpha = 0.5f)), center.y, center.y + rest * 1.3f))
        // Air bubbles that move with the surface.
        for (b in TRAPPED) {
            val angle = b.angle
            val edge = blob.radiusAt(angle)
            val p = center + Offset(cos(angle), sin(angle)) * (rest * edge * b.distance)
            val r = rest * b.size * (0.85f + 0.15f * edge)
            drawCircle(Color.White.copy(alpha = 0.2f), r, p)
            drawCircle(Color.White.copy(alpha = 0.55f), r, p, style = Stroke(r * 0.18f))
            drawCircle(Color.White.copy(alpha = 0.85f), r * 0.22f, p + Offset(-r * 0.35f, -r * 0.35f))
        }
        // Glitter.
        for (k in 0 until 9) {
            val a = k * 2.4f + 0.5f
            val p = center + Offset(cos(a), sin(a)) * (rest * blob.radiusAt(a) * (0.25f + 0.09f * (k % 5)))
            drawStar(p, rest * 0.11f * (0.4f + twinkle(time, k)), Color.White.copy(alpha = 0.35f + 0.5f * twinkle(time, k)))
        }
        // Where a finger presses in, a dimple.
        if (finger != null && hypot(finger.x - center.x, finger.y - center.y) < rest * 0.9f) {
            drawCircle(Brush.radialGradient(listOf(base.darken(0.4f).copy(alpha = 0.55f), Color.Transparent), finger, rest * 0.34f), rest * 0.34f, finger)
            drawCircle(Color.White.copy(alpha = 0.45f), rest * 0.2f, finger + Offset(rest * 0.02f, rest * 0.03f), style = Stroke(rest * 0.03f))
        }
        // A wide soft highlight across the top left.
        rotate(-30f, center + Offset(-rest * 0.42f, -rest * 0.5f)) {
            drawOval(Color.White.copy(alpha = 0.55f), center + Offset(-rest * 0.78f, -rest * 0.68f), Size(rest * 0.7f, rest * 0.32f))
        }
        drawCircle(Color.White.copy(alpha = 0.9f), rest * 0.05f, center + Offset(-rest * 0.12f, -rest * 0.62f))
    }
    drawPath(path, Color.White.copy(alpha = 0.55f), style = Stroke(rest * 0.035f))
}
