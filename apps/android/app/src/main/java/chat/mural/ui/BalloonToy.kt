package chat.mural.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import chat.mural.R
import chat.mural.core.ToySounds
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

private val PALETTE = listOf(0xFFFF5D73, 0xFF4FB4FF, 0xFFFFC93C, 0xFF56D68C, 0xFFB388FF, 0xFFFF8FC7).map { Color(it) }
private const val TAP_SECONDS = 0.16f
private const val HOLD_DELAY = 0.14f

/** Everything the balloon is doing, stepped once a frame. Sounds and buzzes are handed in, so the maths stays here. */
private class BalloonSim(
    val startPump: () -> Unit, val updatePump: (Float) -> Unit, val stopPump: () -> Unit,
    val onBurst: () -> Unit, val tick: (Float) -> Unit,
) {
    var size = 0.25f
    var limit = 0.95f + Random.nextFloat() * 0.05f
    var color = PALETTE.random()
    var bursting = false
    var respawn = 0f
    var spawn = 1f
    var holding = false
    var heldTime = 0f
    var bounce = 0f
    var bounceVelocity = 0f
    var time = 0f
    var flash = 0f
    var ring = 0f
    var popText = 0f
    val particles = mutableListOf<Particle>()
    private var pumping = false

    fun center(w: Float, h: Float) = Offset(w / 2f, h * 0.42f)
    fun base(w: Float, h: Float) = min(w, h * 0.8f) * 0.34f
    fun radius(w: Float, h: Float) = base(w, h) * (0.3f + 0.7f * size) * easeOutBack(spawn)

    fun hits(at: Offset, w: Float, h: Float): Boolean {
        val r = radius(w, h)
        val c = center(w, h)
        return ((at.x - c.x) / (r * 0.92f)).pow(2) + ((at.y - c.y) / (r * 1.08f)).pow(2) <= 1.1f
    }

    fun step(dt: Float, w: Float, h: Float) {
        time += dt
        bounceVelocity += (-bounce * 380f - bounceVelocity * 14f) * dt
        bounce += bounceVelocity * dt
        flash = (flash - dt * 4.5f).coerceAtLeast(0f)
        ring = (ring - dt * 1.7f).coerceAtLeast(0f)
        popText = (popText - dt * 0.9f).coerceAtLeast(0f)
        particles.step(dt, 1100f)
        if (bursting) {
            respawn -= dt
            if (respawn <= 0f) spawnNew()
            return
        }
        spawn = min(1f, spawn + dt * 2.6f)
        if (holding) heldTime += dt
        if (holding && heldTime > HOLD_DELAY) {
            if (!pumping) { pumping = true; startPump() }
            size += 0.19f * (1f - 0.45f * size) * dt
            updatePump(size)
            tick(size)
            if (size >= limit) burst(w, h)
        } else if (pumping) {
            pumping = false
            stopPump()
        }
    }

    fun burst(w: Float, h: Float) {
        if (bursting) return
        bursting = true
        respawn = 1.15f
        if (pumping) { pumping = false; stopPump() }
        val c = center(w, h)
        val r = radius(w, h)
        val pieces = listOf(color, color.lighten(0.25f), color.darken(0.15f))
        particles.burst(c.x, c.y, 34, r * 3.4f, r * 0.24f, pieces, listOf(Particle.RECT), 1.5f)
        particles.burst(c.x, c.y, 18, r * 3.8f, r * 0.2f, listOf(Color.White, Color(0xFFFFE066)), listOf(Particle.STAR), 0.9f)
        particles.burst(c.x, c.y, 14, r * 2.6f, r * 0.12f, pieces, listOf(Particle.DOT), 1.2f)
        flash = 1f
        ring = 1f
        popText = 1f
        onBurst()
    }

    private fun spawnNew() {
        bursting = false
        size = 0.25f
        limit = 0.93f + Random.nextFloat() * 0.07f
        color = PALETTE.filter { it != color }.random()
        spawn = 0f
        heldTime = 0f
    }

    fun poke() { bounceVelocity += 7f }
}

private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val x = t.coerceIn(0f, 1f) - 1f
    return 1f + c3 * x * x * x + c1 * x * x
}

/** A glossy balloon you hold to inflate. The longer you hold, the tighter it gets, until it bursts. */
@Composable
fun BalloonToy(onBack: () -> Unit) {
    val output = rememberToyOutput(listOf(ToySounds.BALLOON_PUMP, ToySounds.BALLOON_BURST))
    val haptics = rememberHaptics()
    var pumpHandle by remember { mutableIntStateOf(0) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var tick by remember { mutableIntStateOf(0) }
    val sim = remember {
        BalloonSim(
            startPump = { pumpHandle = output.play(ToySounds.BALLOON_PUMP, 0.7f, 0.7f, 0.7f, true) },
            updatePump = { fill -> if (pumpHandle != 0) output.update(pumpHandle, 0.55f + 0.35f * fill, 0.55f + 0.35f * fill, 0.7f + 0.95f * fill) },
            stopPump = { if (pumpHandle != 0) output.stop(pumpHandle); pumpHandle = 0 },
            onBurst = { output.play(ToySounds.BALLOON_BURST, 1f, 1f, 1f, false); haptics.burst() },
            tick = { fill -> haptics.tick(fill) },
        )
    }

    FrameLoop { dt ->
        sim.step(dt, size.width.toFloat(), size.height.toFloat())
        tick++
    }

    ToyFrame(stringResource(R.string.toy_balloon), stringResource(R.string.toy_balloon_hint), onBack,
        Brush.verticalGradient(listOf(Color(0xFF9ADCFF), Color(0xFFFFE3F0)))) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { size = it }.testTag("balloon-stage").pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                sim.holding = true
                sim.heldTime = 0f
                do {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (change.pressed) change.consume()
                } while (change.pressed)
                sim.holding = false
                // A quick touch on the balloon pokes it; a poke on a filled balloon pops it.
                if (!sim.bursting && sim.heldTime < TAP_SECONDS && sim.hits(down.position, size.width.toFloat(), size.height.toFloat())) {
                    if (sim.size >= 0.38f) sim.burst(size.width.toFloat(), size.height.toFloat()) else { sim.poke(); haptics.tick(0.5f) }
                }
            }
        }) {
            tick
            drawBalloonScene(sim)
        }
        if (sim.popText > 0f) {
            val progress = 1f - sim.popText
            Text(stringResource(R.string.toy_balloon_pop), modifier = Modifier.align(Alignment.Center)
                .graphicsLayer { val s = easeOutBack(progress * 1.6f) * 1.35f; scaleX = s; scaleY = s; rotationZ = -8f }
                .alpha(min(1f, sim.popText * 2.5f)),
                style = TextStyle(color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.ExtraBold, shadow = Shadow(Color(0x66000000), Offset(4f, 8f), 14f)))
        }
    }
}

private fun DrawScope.drawBalloonScene(sim: BalloonSim) {
    val w = size.width
    val h = size.height
    if (w < 2f || h < 2f) return
    // Slow clouds.
    for (i in 0 until 4) {
        val cloudWidth = w * (0.28f + 0.05f * i)
        val x = ((i * 0.31f * w + sim.time * (10f + 6f * i)) % (w + cloudWidth * 2)) - cloudWidth
        val y = h * (0.12f + 0.17f * i) + 6f * sin(sim.time * 0.6f + i)
        val cloud = Color.White.copy(alpha = 0.55f)
        drawCircle(cloud, cloudWidth * 0.2f, Offset(x + cloudWidth * 0.25f, y))
        drawCircle(cloud, cloudWidth * 0.27f, Offset(x + cloudWidth * 0.5f, y - cloudWidth * 0.06f))
        drawCircle(cloud, cloudWidth * 0.2f, Offset(x + cloudWidth * 0.78f, y))
        drawRoundRect(cloud, Offset(x + cloudWidth * 0.1f, y), Size(cloudWidth * 0.8f, cloudWidth * 0.17f), CornerRadius(cloudWidth * 0.09f))
    }
    if (!sim.bursting || sim.spawn < 1f) drawBalloon(sim, w, h)
    // Flash and shock ring where it burst.
    val c = sim.center(w, h)
    if (sim.flash > 0f) drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = sim.flash * 0.9f), Color.Transparent), c, sim.base(w, h) * 1.6f), sim.base(w, h) * 1.6f, c)
    if (sim.ring > 0f) {
        val p = 1f - sim.ring
        drawCircle(Color.White.copy(alpha = sim.ring * 0.9f), sim.base(w, h) * (0.6f + 1.9f * p), c, style = Stroke(sim.base(w, h) * 0.12f * sim.ring + 1f))
    }
    drawParticles(sim.particles)
    // Pressure gauge.
    if (!sim.bursting) {
        val barWidth = w * 0.5f
        val left = (w - barWidth) / 2f
        val top = h * 0.9f
        drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(left, top), Size(barWidth, 14f), CornerRadius(7f))
        val fill = ((sim.size - 0.25f) / 0.75f).coerceIn(0f, 1f)
        val tint = lerp(Color(0xFF56D68C), Color(0xFFFF5D73), fill)
        drawRoundRect(tint, Offset(left, top), Size(barWidth * fill.coerceAtLeast(0.02f), 14f), CornerRadius(7f))
    }
}

private fun DrawScope.drawBalloon(sim: BalloonSim, w: Float, h: Float) {
    val r = sim.radius(w, h)
    // A brand new balloon starts at no size at all, and a gradient cannot have a radius of zero.
    if (r < 4f) return
    val tension = ((sim.size - 0.78f) / 0.22f).coerceIn(0f, 1f)
    val shake = sin(sim.time * 70f) * tension * r * 0.025f
    val bob = sin(sim.time * 1.7f) * r * 0.03f
    val c = sim.center(w, h) + Offset(shake, bob)
    val bw = r * 0.92f
    val bh = r * 1.08f
    val base = lerp(sim.color, sim.color.lighten(0.3f), tension)
    val knot = c + Offset(0f, bh)
    // The string sways under the balloon.
    val sway = sin(sim.time * 2.2f) * r * 0.16f
    val string = Path().apply {
        moveTo(knot.x, knot.y + 4f)
        cubicTo(knot.x + sway, knot.y + r * 0.45f, knot.x - sway, knot.y + r * 0.95f, knot.x + sway * 0.3f, knot.y + r * 1.5f)
    }
    drawPath(string, Color(0xFF6B6480).copy(alpha = 0.75f), style = Stroke(r * 0.03f + 1.5f))
    withTransform({ scale(1f + 0.1f * sim.bounce, 1f - 0.1f * sim.bounce, pivot = knot) }) {
        if (tension > 0f) drawOval(Color.White.copy(alpha = 0.25f * tension), c - Offset(bw * 1.06f, bh * 1.06f), Size(bw * 2.12f, bh * 2.12f))
        drawOval(Brush.radialGradient(listOf(base.lighten(0.6f), base, base.darken(0.4f)), c + Offset(-bw * 0.36f, -bh * 0.4f), bh * 1.55f), c - Offset(bw, bh), Size(bw * 2, bh * 2))
        drawOval(base.darken(0.35f).copy(alpha = 0.5f), c - Offset(bw, bh), Size(bw * 2, bh * 2), style = Stroke(bw * 0.04f))
        rotate(-28f, c + Offset(-bw * 0.42f, -bh * 0.48f)) {
            drawOval(Color.White.copy(alpha = 0.7f + 0.2f * tension), c + Offset(-bw * 0.74f, -bh * 0.74f), Size(bw * 0.5f, bh * 0.28f))
        }
        drawCircle(Color.White.copy(alpha = 0.85f), bw * 0.06f, c + Offset(-bw * 0.16f, -bh * 0.66f))
        drawArc(Color.White.copy(alpha = 0.2f), 20f, 58f, false, c - Offset(bw * 0.82f, bh * 0.82f), Size(bw * 1.64f, bh * 1.64f), style = Stroke(bw * 0.07f))
        val tie = Path().apply {
            moveTo(knot.x, knot.y - bh * 0.03f)
            lineTo(knot.x - bw * 0.13f, knot.y + bh * 0.11f)
            lineTo(knot.x + bw * 0.13f, knot.y + bh * 0.11f)
            close()
        }
        drawPath(tie, base.darken(0.3f))
    }
}
