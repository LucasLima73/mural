package chat.mural.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.mural.R
import chat.mural.core.TouchMixer
import chat.mural.core.ToySounds
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val COLUMNS = 5
private const val ROWS = 7
private const val BUBBLES = COLUMNS * ROWS
private val TRAY = Brush.verticalGradient(listOf(Color(0xFF35316B), Color(0xFF231F4D)))
private val ROW_COLORS = listOf(0xFFFF5D8F, 0xFFFF8A4C, 0xFFFFC93C, 0xFF56D68C, 0xFF3EC1F3, 0xFF7C83FF, 0xFFC86BFF).map { Color(it) }

/** A silicone pop-it: press the raised bubbles and they snap in with a pop, a flash and a buzz. */
@Composable
fun PopItToy(onBack: () -> Unit) {
    val output = rememberToyOutput(listOf(ToySounds.POP))
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val popped = remember { BooleanArray(BUBBLES) }
    val position = remember { FloatArray(BUBBLES) }
    val velocity = remember { FloatArray(BUBBLES) }
    val flash = remember { FloatArray(BUBBLES) }
    val particles = remember { mutableListOf<Particle>() }
    var poppedCount by remember { mutableIntStateOf(0) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var tick by remember { mutableIntStateOf(0) }
    var time by remember { mutableFloatStateOf(0f) }
    var celebrate by remember { mutableFloatStateOf(0f) }
    var flipping by remember { mutableStateOf(false) }

    fun geometry(): Triple<Float, Float, Float> {
        val cell = min(size.width / COLUMNS.toFloat(), size.height / ROWS.toFloat()) * 0.94f
        return Triple(cell, (size.width - cell * COLUMNS) / 2f, (size.height - cell * ROWS) / 2f)
    }
    fun center(index: Int): Offset {
        val (cell, left, top) = geometry()
        return Offset(left + cell * (index % COLUMNS + 0.5f), top + cell * (index / COLUMNS + 0.5f))
    }
    fun color(index: Int) = ROW_COLORS[index / COLUMNS]

    fun play(index: Int, rate: Float, gain: Float) {
        val ears = TouchMixer.pan(center(index).x / size.width.coerceAtLeast(1), gain)
        output.play(ToySounds.POP, ears.left, ears.right, rate, false)
    }

    fun flipAll() {
        if (flipping) return
        flipping = true
        scope.launch {
            // A wave of bubbles springing back, from the top left corner.
            for (step in 0 until COLUMNS + ROWS) {
                for (i in 0 until BUBBLES) if (i % COLUMNS + i / COLUMNS == step && popped[i]) {
                    popped[i] = false
                    velocity[i] -= 5f
                    poppedCount--
                }
                if (step % 2 == 0) play(min(step * COLUMNS, BUBBLES - 1), 1.25f, 0.45f)
                delay(45)
            }
            flipping = false
        }
    }

    fun handle(at: Offset, isDown: Boolean) {
        if (flipping) return
        val (cell, left, top) = geometry()
        val column = ((at.x - left) / cell).toInt()
        val row = ((at.y - top) / cell).toInt()
        if (column !in 0 until COLUMNS || row !in 0 until ROWS) return
        val index = row * COLUMNS + column
        if (hypot(at.x - center(index).x, at.y - center(index).y) > cell * 0.5f) return
        if (!popped[index]) {
            popped[index] = true
            poppedCount++
            velocity[index] += 9f
            flash[index] = 1f
            play(index, 0.88f + Random.nextFloat() * 0.3f, 0.95f)
            haptics.pop()
            particles.burst(center(index).x, center(index).y, 7, cell * 2.6f, cell * 0.16f, listOf(color(index).lighten(0.5f), Color.White), listOf(Particle.STAR, Particle.DOT), 0.5f)
            if (poppedCount == BUBBLES) {
                celebrate = 1f
                haptics.burst()
                repeat(70) { particles.burst(size.width / 2f, size.height * 0.4f, 1, cell * 7f, cell * 0.24f, ROW_COLORS + Color.White, listOf(Particle.RECT, Particle.STAR, Particle.DOT), 1.8f) }
                scope.launch { delay(2200); flipAll() }
            }
        } else if (isDown) {
            popped[index] = false
            poppedCount--
            velocity[index] -= 7f
            play(index, 0.7f, 0.45f)
            haptics.tick(0.6f)
        }
    }

    FrameLoop { dt ->
        time += dt
        for (i in 0 until BUBBLES) {
            val target = if (popped[i]) 1f else 0f
            velocity[i] += ((target - position[i]) * 620f - velocity[i] * 26f) * dt
            position[i] += velocity[i] * dt
            flash[i] = (flash[i] - dt * 3.4f).coerceAtLeast(0f)
        }
        celebrate = (celebrate - dt * 0.5f).coerceAtLeast(0f)
        particles.step(dt, 900f)
        tick++
    }

    ToyFrame(stringResource(R.string.toy_popit), stringResource(R.string.toy_popit_hint), onBack,
        Brush.verticalGradient(listOf(Color(0xFF8E7BFF), Color(0xFFFF9BC6)))) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { size = it }.testTag("popit-board").pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                handle(down.position, true)
                do {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (change.pressed) { handle(change.position, false); change.consume() }
                } while (change.pressed)
            }
        }) {
            tick
            val (cell, left, top) = geometry()
            if (cell < 2f) return@Canvas
            val pad = cell * 0.22f
            drawRoundRect(Color.Black.copy(alpha = 0.25f), Offset(left - pad, top - pad + cell * 0.08f), Size(cell * COLUMNS + pad * 2, cell * ROWS + pad * 2), CornerRadius(cell * 0.5f))
            drawRoundRect(TRAY, Offset(left - pad, top - pad), Size(cell * COLUMNS + pad * 2, cell * ROWS + pad * 2), CornerRadius(cell * 0.5f))
            drawRoundRect(Color.White.copy(alpha = 0.14f), Offset(left - pad, top - pad), Size(cell * COLUMNS + pad * 2, cell * ROWS + pad * 2), CornerRadius(cell * 0.5f), style = Stroke(cell * 0.05f))
            for (i in 0 until BUBBLES) drawBubble(center(i), cell * 0.42f, color(i), position[i], flash[i])
            drawParticles(particles)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.toy_popit_count, poppedCount.coerceAtLeast(0), BUBBLES), style = MaterialTheme.typography.titleMedium, color = Color.White,
                modifier = Modifier.weight(1f).testTag("popit-count"))
            Button({ flipAll() }, Modifier.testTag("popit-flip"), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.9f), contentColor = MuralColors.Ink)) {
                Text(stringResource(R.string.toy_popit_flip), style = MaterialTheme.typography.labelLarge)
            }
        }
        if (celebrate > 0f) Text(stringResource(R.string.toy_popit_done), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.align(Alignment.Center).graphicsLayer { val s = 0.6f + 0.6f * (1f - (1f - celebrate) * (1f - celebrate)); scaleX = s; scaleY = s }.alpha(min(1f, celebrate * 2.2f)))
    }
}

/** One bubble. `amount` is 0 while it stands up and 1 once it has been pressed in; it can overshoot for a springy pop. */
private fun DrawScope.drawBubble(center: Offset, radius: Float, base: Color, amount: Float, flash: Float) {
    // The well the bubble sits in.
    drawCircle(Color.Black.copy(alpha = 0.32f), radius * 1.1f, center)
    if (amount < 0.5f) {
        val lift = 1f - amount
        val r = radius * (0.9f + 0.1f * lift + 0.05f * (1f - lift))
        drawCircle(Color.Black.copy(alpha = 0.22f * lift), r, center + Offset(r * 0.08f, r * 0.14f))
        drawGlossyBall(center + Offset(0f, -radius * 0.04f * lift), r, base)
    } else {
        val dull = base.darken(0.1f)
        val r = radius * 0.9f
        drawCircle(Brush.radialGradient(listOf(dull.darken(0.38f), dull, dull.lighten(0.22f)), center = center + Offset(-r * 0.3f, -r * 0.34f), radius = r * 1.5f), r, center)
        drawCircle(Color.Black.copy(alpha = 0.3f), r, center, style = Stroke(r * 0.09f))
        drawArc(Color.White.copy(alpha = 0.32f), 10f, 70f, false, center - Offset(r * 0.86f, r * 0.86f), Size(r * 1.72f, r * 1.72f), style = Stroke(r * 0.07f))
    }
    if (flash > 0f) {
        val p = 1f - flash
        drawCircle(base.lighten(0.5f).copy(alpha = flash * 0.9f), radius * (1f + 0.9f * p), center, style = Stroke(radius * 0.16f * flash + 1f))
        drawCircle(Color.White.copy(alpha = flash * 0.45f), radius * (0.5f + 0.7f * p), center)
    }
}
