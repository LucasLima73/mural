package chat.mural.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import chat.mural.R
import chat.mural.audio.PanDemo
import chat.mural.core.TouchBehavior
import kotlinx.coroutines.launch

private const val STEPS = 4
private val GREETINGS = listOf("Hei!", "¡Hola!", "Hi!", "Salut !", "Hallo!", "Ciao!", "Olá!", "你好！")

/**
 * First-run screens: a name, a headphone check, what you like to hear, and the microphone. Each is skippable by
 * pressing Continue, and the answers only shape Home and the pads.
 */
@Composable
fun OnboardingScreen(initialName: String, onComplete: (name: String, likes: List<TouchBehavior>) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf(initialName) }
    var likes by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val phase = muralPhase()
    val greeting = GREETINGS[((phase / 2.736f).toInt()) % GREETINGS.size]
    Box(Modifier.fillMaxSize()) {
        SoftAnimatedBackground(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) SoftRoundButton(MuralSymbol.Back, stringResource(R.string.onboarding_back),
                    onClick = { step -= 1 }, modifier = Modifier.testTag("onboarding-back"), diameter = 44.dp)
                else Brand()
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(STEPS) { index ->
                        Box(Modifier.size(width = if (index == step) 24.dp else 8.dp, height = 6.dp)
                            .background(if (index == step) MuralColors.Orange else MuralColors.Peach, CircleShape))
                    }
                }
            }
            AnimatedContent(targetState = step, transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(200)) },
                modifier = Modifier.weight(1f), label = "onboarding step") { current ->
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val compact = maxHeight < 620.dp
                    val orbSize = when {
                        !compact -> if (current == 0) 132.dp else 84.dp
                        current == 0 -> minOf(104.dp, maxHeight * .20f)
                        else -> minOf(56.dp, maxHeight * .12f)
                    }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 28.dp, vertical = if (compact) 12.dp else 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(if (compact) 4.dp else if (current == 0) 12.dp else 0.dp))
                        MuralOrb(modifier = Modifier.size(orbSize))
                        AnimatedContent(greeting, transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(350)) },
                            label = "hello", modifier = Modifier.heightIn(min = if (current == 0) 76.dp else 54.dp)) { hello ->
                            Text(hello, style = if (current == 0) MaterialTheme.typography.displayLarge else MaterialTheme.typography.displaySmall,
                                modifier = Modifier.testTag("onboarding-greeting"), textAlign = TextAlign.Center)
                        }
                        Spacer(Modifier.height(if (compact) 8.dp else 22.dp))
                        when (current) {
                            0 -> NameStep(name, compact) { name = it }
                            1 -> HeadphonesStep(compact)
                            2 -> TastesStep(likes, compact) { likes = it }
                            else -> MicrophoneStep(compact)
                        }
                        Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 26.dp).padding(top = 14.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    if (step < STEPS - 1) step += 1
                    else onComplete(name.trim(), likes.mapNotNull { key -> TouchBehavior.entries.firstOrNull { it.name == key } })
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("onboarding-continue"), shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MuralColors.Orange, contentColor = MuralColors.Ink)) {
                    Text(stringResource(if (step == STEPS - 1) R.string.onboarding_start_button else R.string.onboarding_continue_button))
                }
                Text(stringResource(when (step) {
                    0 -> R.string.onboarding_name_note
                    1 -> R.string.onboarding_phones_note
                    2 -> R.string.onboarding_tastes_note
                    else -> R.string.onboarding_mic_note
                }), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun StepTitle(text: String, tag: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() }.testTag(tag))
}

@Composable
private fun NameStep(name: String, compact: Boolean, onChange: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 22.dp)) {
        StepTitle(stringResource(R.string.onboarding_name_title), "onboarding-name-title")
        MuralTextField(name, { onChange(it.take(20)) }, Modifier.fillMaxWidth().testTag("onboarding-name"), singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            label = { Text(stringResource(R.string.onboarding_name_label)) })
    }
}

@Composable
private fun HeadphonesStep(compact: Boolean) {
    val context = LocalContext.current
    val demo = remember { PanDemo(context) }
    DisposableEffect(demo) { onDispose { demo.close() } }
    val scope = rememberCoroutineScope()
    var position by remember { mutableFloatStateOf(.5f) }
    var playing by rememberSaveable { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp)) {
        StepTitle(stringResource(R.string.onboarding_phones_title), "onboarding-phones-title")
        Text(stringResource(R.string.onboarding_phones_body), style = MaterialTheme.typography.bodyMedium, color = MuralColors.Secondary, textAlign = TextAlign.Center)
        PanBar(position)
        OutlinedButton(onClick = {
            playing = true
            scope.launch {
                demo.play { position = it }
                position = .5f
                playing = false
            }
        }, enabled = !playing, shape = CircleShape, modifier = Modifier.heightIn(min = 52.dp).testTag("onboarding-phones-play")) {
            Text(stringResource(if (playing) R.string.onboarding_phones_playing else R.string.onboarding_phones_play))
        }
    }
}

/** A bar from "L" to "R" with a dot that follows the sound, so what you hear and what you see agree. */
@Composable
private fun PanBar(position: Float) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = .78f))
        .border(1.dp, Color.White.copy(alpha = .9f), RoundedCornerShape(28.dp)).testTag("onboarding-pan-bar")) {
        Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.onboarding_pan_left), style = MaterialTheme.typography.labelLarge, color = MuralColors.Secondary)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.onboarding_pan_right), style = MaterialTheme.typography.labelLarge, color = MuralColors.Secondary)
        }
        val travel = maxWidth - 28.dp - 24.dp
        Box(Modifier.align(Alignment.CenterStart).offset(x = 12.dp + travel * position).size(28.dp).background(MuralColors.Orange, CircleShape))
    }
}

@Composable
private fun TastesStep(likes: List<String>, compact: Boolean, onChange: (List<String>) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp)) {
        StepTitle(stringResource(R.string.onboarding_tastes_title), "onboarding-tastes-title")
        Text(stringResource(R.string.onboarding_tastes_body), style = MaterialTheme.typography.bodyMedium, color = MuralColors.Secondary, textAlign = TextAlign.Center)
        listOf(
            Triple(TouchBehavior.Scrub, MuralSymbol.Wave, R.string.onboarding_taste_slide_body),
            Triple(TouchBehavior.Tap, MuralSymbol.Sparkles, R.string.onboarding_taste_tap_body),
            Triple(TouchBehavior.Hold, MuralSymbol.Leaf, R.string.onboarding_taste_ambient_body),
        ).forEachIndexed { index, (behavior, symbol, body) ->
            val selected = behavior.name in likes
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(if (selected) MuralColors.Panels[index] else Color.White.copy(alpha = .78f))
                .border(if (selected) 2.dp else 1.dp, if (selected) MuralColors.Orange else Color.White.copy(alpha = .9f), RoundedCornerShape(24.dp))
                .clickable(role = Role.Checkbox) { onChange(if (selected) likes - behavior.name else likes + behavior.name) }
                .testTag("onboarding-taste-${behavior.name.lowercase()}").padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MuralIcon(symbol, Modifier.size(26.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(categoryFor(behavior)), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
                }
                if (selected) MuralIcon(MuralSymbol.Check, Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun MicrophoneStep(compact: Boolean) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp)) {
        StepTitle(stringResource(R.string.onboarding_mic_title), "onboarding-mic-title")
        Text(stringResource(R.string.onboarding_mic_body), style = MaterialTheme.typography.bodyMedium, color = MuralColors.Secondary, textAlign = TextAlign.Center)
        MuralIcon(if (allowed) MuralSymbol.Mic else MuralSymbol.MicOff, Modifier.size(40.dp), color = if (allowed) MuralColors.Orange else MuralColors.Secondary)
        OutlinedButton(onClick = { ask.launch(Manifest.permission.RECORD_AUDIO) }, enabled = !allowed, shape = CircleShape,
            modifier = Modifier.heightIn(min = 52.dp).testTag("onboarding-mic-allow")) {
            Text(stringResource(if (allowed) R.string.onboarding_mic_allowed else R.string.onboarding_mic_allow))
        }
    }
}
