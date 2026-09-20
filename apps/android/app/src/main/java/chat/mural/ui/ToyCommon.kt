package chat.mural.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.mural.R
import chat.mural.audio.Haptics
import chat.mural.audio.SoundPoolOutput
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

internal fun Color.lighten(amount: Float) = lerp(this, Color.White, amount)
internal fun Color.darken(amount: Float) = lerp(this, Color.Black, amount)

/** The sounds a toy uses, loaded for as long as the toy is on screen. */
@Composable
internal fun rememberToyOutput(ids: List<String>): SoundPoolOutput {
    val context = LocalContext.current
    val output = remember { SoundPoolOutput(context, ids) }
    DisposableEffect(output) { onDispose { output.close() } }
    return output
}

@Composable
internal fun rememberHaptics(): Haptics {
    val context = LocalContext.current
    return remember { Haptics(context) }
}

/** Calls `onFrame` with the seconds since the last frame, for as long as it is on screen. */
@Composable
internal fun FrameLoop(onFrame: (dt: Float) -> Unit) {
    val latest by rememberUpdatedState(onFrame)
    LaunchedEffect(Unit) {
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            latest(((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f))
            last = now
        }
    }
}

/** A toy's screen: a back button, its name and a hint above a rounded stage the toy fills. */
@Composable
internal fun ToyFrame(title: String, hint: String, onBack: () -> Unit, backdrop: Brush, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SoftRoundButton(MuralSymbol.Back, stringResource(R.string.onboarding_back), onBack, diameter = 44.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(32.dp)).background(backdrop), content = content)
    }
}

/** A speck of confetti, a torn piece of rubber or a spark. */
internal class Particle(
    var x: Float, var y: Float, var vx: Float, var vy: Float, val size: Float, var rotation: Float, val spin: Float,
    val color: Color, var life: Float, val shape: Int,
) {
    val startLife = life
    companion object { const val RECT = 0; const val DOT = 1; const val STAR = 2 }
}

internal fun MutableList<Particle>.burst(x: Float, y: Float, count: Int, speed: Float, size: Float, colors: List<Color>, shapes: List<Int>, life: Float = 1.3f) {
    repeat(count) {
        val angle = Random.nextFloat() * 2 * PI.toFloat()
        val power = speed * (0.35f + Random.nextFloat() * 0.9f)
        add(Particle(x, y, cos(angle) * power, sin(angle) * power - speed * 0.25f, size * (0.6f + Random.nextFloat() * 0.9f),
            Random.nextFloat() * 360f, (Random.nextFloat() - 0.5f) * 720f, colors.random(), life * (0.7f + Random.nextFloat() * 0.5f), shapes.random()))
    }
}

internal fun MutableList<Particle>.step(dt: Float, gravity: Float) {
    val iterator = iterator()
    while (iterator.hasNext()) {
        val p = iterator.next()
        p.life -= dt
        if (p.life <= 0f) { iterator.remove(); continue }
        p.vy += gravity * dt
        p.vx *= 1f - 0.6f * dt
        p.x += p.vx * dt
        p.y += p.vy * dt
        p.rotation += p.spin * dt
    }
}

internal fun DrawScope.drawParticles(particles: List<Particle>) {
    for (p in particles) {
        val fade = (p.life / p.startLife).coerceIn(0f, 1f)
        val color = p.color.copy(alpha = minOf(1f, fade * 1.6f))
        val center = Offset(p.x, p.y)
        when (p.shape) {
            Particle.DOT -> drawCircle(color, p.size * 0.5f, center)
            Particle.STAR -> rotate(p.rotation, center) { drawStar(center, p.size, color) }
            else -> rotate(p.rotation, center) {
                drawRect(color, Offset(center.x - p.size / 2, center.y - p.size * 0.22f), Size(p.size, p.size * 0.44f))
            }
        }
    }
}

/** A four-pointed sparkle. */
internal fun DrawScope.drawStar(center: Offset, size: Float, color: Color) {
    val r = size / 2f
    val inner = r * 0.28f
    val path = Path().apply {
        moveTo(center.x, center.y - r)
        lineTo(center.x + inner, center.y - inner)
        lineTo(center.x + r, center.y)
        lineTo(center.x + inner, center.y + inner)
        lineTo(center.x, center.y + r)
        lineTo(center.x - inner, center.y + inner)
        lineTo(center.x - r, center.y)
        lineTo(center.x - inner, center.y - inner)
        close()
    }
    drawPath(path, color)
}

/** A glossy sphere: lit from the top left, darker at the rim, with a highlight and a reflected glow. */
internal fun DrawScope.drawGlossyBall(center: Offset, radius: Float, base: Color, highlight: Float = 1f) {
    drawCircle(Brush.radialGradient(listOf(base.lighten(0.55f), base, base.darken(0.32f)),
        center = center + Offset(-radius * 0.32f, -radius * 0.36f), radius = radius * 1.4f), radius, center)
    drawCircle(base.darken(0.3f).copy(alpha = 0.5f), radius, center, style = androidx.compose.ui.graphics.drawscope.Stroke(radius * 0.06f))
    rotate(-32f, center + Offset(-radius * 0.28f, -radius * 0.46f)) {
        drawOval(Color.White.copy(alpha = 0.72f * highlight), center + Offset(-radius * 0.62f, -radius * 0.66f), Size(radius * 0.66f, radius * 0.34f))
    }
    drawCircle(Color.White.copy(alpha = 0.9f * highlight), radius * 0.065f, center + Offset(-radius * 0.18f, -radius * 0.54f))
    drawArc(Color.White.copy(alpha = 0.22f), 15f, 62f, false, center - Offset(radius * 0.8f, radius * 0.8f), Size(radius * 1.6f, radius * 1.6f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(radius * 0.08f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
}

internal fun twinkle(time: Float, seed: Int) = 0.5f + 0.5f * sin(time * 2.4f + seed * 1.7f)
