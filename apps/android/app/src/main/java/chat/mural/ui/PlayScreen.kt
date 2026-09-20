package chat.mural.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import chat.mural.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private enum class Toy(val id: String, val title: Int, val body: Int, val color: Color) {
    PopIt("popit", R.string.toy_popit, R.string.toy_popit_body, Color(0xFFE9E1FF)),
    Balloon("balloon", R.string.toy_balloon, R.string.toy_balloon_body, Color(0xFFD9F0FF)),
    Slime("slime", R.string.toy_slime, R.string.toy_slime_body, Color(0xFFFFE0EE)),
}

/** The Play tab: a list of toys, and whichever one is open. */
@Composable
fun PlayScreen(modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    when (open) {
        Toy.PopIt.id -> PopItToy { open = null }
        Toy.Balloon.id -> BalloonToy { open = null }
        Toy.Slime.id -> SlimeToy { open = null }
        else -> ToyMenu(modifier) { open = it.id }
    }
}

@Composable
private fun ToyMenu(modifier: Modifier, onOpen: (Toy) -> Unit) {
    LazyColumn(modifier.fillMaxSize().testTag("play-screen"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading(stringResource(R.string.play_eyebrow), stringResource(R.string.play_title), stringResource(R.string.play_subtitle), Modifier.padding(bottom = 6.dp)) }
        items(Toy.entries.size) { index ->
            val toy = Toy.entries[index]
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(toy.color).testTag("toy-${toy.id}")
                .clickable(role = Role.Button) { onOpen(toy) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Preview(toy, Modifier.size(96.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(toy.title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(toy.body), style = MaterialTheme.typography.bodyMedium, color = MuralColors.Secondary)
                }
                MuralIcon(MuralSymbol.ChevronRight, Modifier.size(20.dp))
            }
        }
    }
}

/** A small living picture of each toy, so the list already looks like something you want to touch. */
@Composable
private fun Preview(toy: Toy, modifier: Modifier) {
    val phase = muralPhase()
    Canvas(modifier) {
        when (toy) {
            Toy.PopIt -> drawPopItPreview(phase)
            Toy.Balloon -> drawBalloonPreview(phase)
            Toy.Slime -> drawSlimePreview(phase)
        }
    }
}

private val RAINBOW = listOf(0xFFFF5D8F, 0xFFFFC93C, 0xFF3EC1F3).map { Color(it) }

private fun DrawScope.drawPopItPreview(phase: Float) {
    val cell = size.minDimension / 3f
    drawRoundRect(Color(0xFF2E2A5E), Offset.Zero, size, androidx.compose.ui.geometry.CornerRadius(cell * 0.4f))
    for (row in 0 until 3) for (column in 0 until 3) {
        val c = Offset(cell * (column + 0.5f), cell * (row + 0.5f))
        val popped = (row * 3 + column + (phase * 0.6f).toInt()) % 4 == 0
        if (popped) {
            drawCircle(RAINBOW[row].darken(0.45f), cell * 0.36f, c)
            drawCircle(Color.White.copy(alpha = 0.25f), cell * 0.36f, c, style = Stroke(cell * 0.04f))
        } else drawGlossyBall(c, cell * 0.38f, RAINBOW[row])
    }
}

private fun DrawScope.drawBalloonPreview(phase: Float) {
    val bob = sin(phase * 1.6f) * size.height * 0.03f
    val c = Offset(size.width / 2f, size.height * 0.42f + bob)
    val bw = size.width * 0.32f
    val bh = size.height * 0.38f
    val color = Color(0xFFFF5D73)
    val string = Path().apply {
        moveTo(c.x, c.y + bh)
        cubicTo(c.x + bw * 0.4f, c.y + bh + size.height * 0.1f, c.x - bw * 0.4f, c.y + bh + size.height * 0.18f, c.x + 2f, c.y + bh + size.height * 0.26f)
    }
    drawPath(string, Color(0xFF6B6480).copy(alpha = 0.7f), style = Stroke(2.5f))
    drawOval(Brush.radialGradient(listOf(color.lighten(0.6f), color, color.darken(0.4f)), c + Offset(-bw * 0.36f, -bh * 0.4f), bh * 1.5f), c - Offset(bw, bh), Size(bw * 2, bh * 2))
    rotate(-28f, c + Offset(-bw * 0.4f, -bh * 0.48f)) { drawOval(Color.White.copy(alpha = 0.7f), c + Offset(-bw * 0.72f, -bh * 0.72f), Size(bw * 0.5f, bh * 0.28f)) }
    val knot = Path().apply { moveTo(c.x, c.y + bh - 2f); lineTo(c.x - bw * 0.14f, c.y + bh + bh * 0.1f); lineTo(c.x + bw * 0.14f, c.y + bh + bh * 0.1f); close() }
    drawPath(knot, color.darken(0.3f))
}

private fun DrawScope.drawSlimePreview(phase: Float) {
    val c = Offset(size.width / 2f, size.height * 0.5f)
    val r = size.minDimension * 0.38f
    val base = Color(0xFFFF7EB6)
    val path = Path().apply {
        val steps = 48
        for (i in 0..steps) {
            val a = 2 * PI.toFloat() * i / steps
            val wobble = 1f + 0.07f * sin(3 * a + phase * 1.4f) + 0.05f * sin(5 * a - phase * 1.1f)
            val p = Offset(c.x + cos(a) * r * wobble, c.y + sin(a) * r * wobble * 0.92f)
            if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
        }
        close()
    }
    drawPath(path, base.copy(alpha = 0.25f), style = Stroke(r * 0.3f))
    drawPath(path, Brush.radialGradient(listOf(base.lighten(0.55f), base, base.darken(0.34f)), c + Offset(-r * 0.36f, -r * 0.46f), r * 1.7f))
    rotate(-30f, c + Offset(-r * 0.4f, -r * 0.5f)) { drawOval(Color.White.copy(alpha = 0.55f), c + Offset(-r * 0.76f, -r * 0.66f), Size(r * 0.7f, r * 0.3f)) }
    drawCircle(Color.White.copy(alpha = 0.85f), r * 0.05f, c + Offset(-r * 0.1f, -r * 0.6f))
    drawPath(path, Color.White.copy(alpha = 0.5f), style = Stroke(r * 0.035f))
}
