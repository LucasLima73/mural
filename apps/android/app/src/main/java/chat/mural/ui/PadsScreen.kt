package chat.mural.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.mural.R
import chat.mural.RoomViewModel
import chat.mural.audio.SoundPoolOutput
import chat.mural.core.RoomFailure
import chat.mural.core.RoomSignal
import chat.mural.core.RoomStatus
import chat.mural.core.TouchBehavior
import chat.mural.core.TouchEvent
import chat.mural.core.TouchPhase
import chat.mural.core.TouchSoundEngine
import chat.mural.core.TouchTexture
import chat.mural.core.TouchTextures
import kotlinx.coroutines.delay

private const val TICK_MILLIS = 20L
private const val YOU = "you"
private const val PAD_COLUMNS = 3
private val CATEGORY_ORDER = listOf(TouchBehavior.Scrub, TouchBehavior.Tap, TouchBehavior.Hold)

/** Owns the sound output for as long as the room is on screen, and silences it when the app stops. */
@Composable
private fun rememberTouchSoundEngine(): TouchSoundEngine {
    val context = LocalContext.current
    val output = remember { SoundPoolOutput(context) }
    val engine = remember { TouchSoundEngine(output) }
    DisposableEffect(engine) { onDispose { engine.stopAll(); output.close() } }
    LaunchedEffect(engine) {
        while (true) {
            delay(TICK_MILLIS)
            if (engine.hasActiveStreams) engine.tick(SystemClock.uptimeMillis(), TICK_MILLIS / 1000f)
        }
    }
    return engine
}

/**
 * The pads. On your own they play for you; inside a room your friend hears every touch too, and the room's code,
 * voice and chat sit above the pads.
 */
@Composable
fun PadsScreen(room: RoomViewModel, category: TouchBehavior, onCategory: (TouchBehavior) -> Unit, modifier: Modifier = Modifier) {
    val engine = rememberTouchSoundEngine()
    val state by room.state.collectAsStateWithLifecycle()
    val voice by room.voice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val inRoom = state.status == RoomStatus.InRoom
    val inRoomOrLost = inRoom || (state.status == RoomStatus.Failed && state.failure == RoomFailure.Lost)
    var entry by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var chatOpen by rememberSaveable { mutableStateOf(false) }
    var micDenied by rememberSaveable { mutableStateOf(false) }
    val askForMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micDenied = !granted
        if (granted) room.startVoice()
    }

    // A finger that leaves the app or the room never sends its Up event, so silence it here and for the friend too.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { engine.stopAll(); room.sendRelease() }
    DisposableEffect(room) { onDispose { room.sendRelease() } }
    LaunchedEffect(room, engine) {
        room.signals.collect { signal ->
            when (signal) {
                is RoomSignal.Touch -> engine.handle(signal.from, signal.event, SystemClock.uptimeMillis())
                is RoomSignal.Silence -> engine.stopSource(signal.from)
                // Setting up voice is handled by the room, not by the pads.
                is RoomSignal.Voice, is RoomSignal.PeerVoice, is RoomSignal.PeerLeft, RoomSignal.RoomClosed -> Unit
            }
        }
    }

    Column(modifier.fillMaxSize().testTag("pads-screen").padding(horizontal = 24.dp)) {
        if (inRoomOrLost) {
            RoomBar(state, voice, micDenied, onChat = { chatOpen = true }, onRejoin = room::rejoin, onLeave = { room.stopVoice(); room.leave() },
                onVoiceStart = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        micDenied = false
                        room.startVoice()
                    } else askForMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                },
                onVoiceStop = { micDenied = false; room.stopVoice() }, onPushToTalk = room::setPushToTalk, onMute = room::setMuted, onTalking = room::setTalking)
        } else SoloBar(state, room.available, onCreate = { room.dismissFailure(); entry = false },
            onJoin = { room.dismissFailure(); entry = true }, onDismissFailure = room::dismissFailure)
        CategoryChips(category, onCategory)
        Text(stringResource(hintFor(category)), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary,
            modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
        PadGrid(TouchTextures.all.filter { it.behavior == category }, enabled = true, Modifier.weight(1f).padding(bottom = 16.dp)) { event ->
            engine.handle(YOU, event, SystemClock.uptimeMillis())
            if (inRoom) room.sendTouch(event)
        }
    }

    RoomEntryFlow(room, entry, onDismiss = { entry = null })
    if (chatOpen) RoomChatSheet(state, room, onDismiss = { chatOpen = false })
}

@Composable
private fun CategoryChips(selected: TouchBehavior, onSelect: (TouchBehavior) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("pad-categories")) {
        items(CATEGORY_ORDER) { behavior ->
            FilterChip(
                selected = selected == behavior, onClick = { onSelect(behavior) },
                label = { Text(stringResource(categoryFor(behavior)), style = MaterialTheme.typography.labelLarge) },
                shape = CircleShape, border = null,
                colors = FilterChipDefaults.filterChipColors(containerColor = MuralColors.Surface.copy(alpha = .70f),
                    selectedContainerColor = MuralColors.Peach, selectedLabelColor = MuralColors.Ink),
                modifier = Modifier.height(44.dp).testTag("pad-category-${behavior.name.lowercase()}"),
            )
        }
    }
}

internal fun categoryFor(behavior: TouchBehavior) = when (behavior) {
    TouchBehavior.Scrub -> R.string.pads_category_slide
    TouchBehavior.Tap -> R.string.pads_category_tap
    TouchBehavior.Hold -> R.string.pads_category_ambient
}

private fun hintFor(behavior: TouchBehavior) = when (behavior) {
    TouchBehavior.Scrub -> R.string.pads_hint_slide
    TouchBehavior.Tap -> R.string.pads_hint_tap
    TouchBehavior.Hold -> R.string.pads_hint_hold
}

@Composable
private fun PadGrid(textures: List<TouchTexture>, enabled: Boolean, modifier: Modifier, onEvent: (TouchEvent) -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        textures.chunked(PAD_COLUMNS).forEachIndexed { row, rowTextures ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowTextures.forEachIndexed { column, texture ->
                    PadTile(texture, enabled, MuralColors.Panels[(row + column) % MuralColors.Panels.size], Modifier.weight(1f).fillMaxSize(), onEvent)
                }
            }
        }
    }
}

/** The pad's name comes from the string `pad_<id>`, so a new sound only needs its file and its name. */
@SuppressLint("DiscouragedApi")
@Composable
internal fun padLabel(texture: TouchTexture): String {
    val context = LocalContext.current
    val resource = remember(texture.id) { context.resources.getIdentifier("pad_${texture.id}", "string", context.packageName) }
    return if (resource != 0) stringResource(resource) else texture.id
}

@Composable
private fun PadTile(texture: TouchTexture, enabled: Boolean, color: Color, modifier: Modifier, onEvent: (TouchEvent) -> Unit) {
    var finger by remember { mutableStateOf<Offset?>(null) }
    Box(
        modifier.alpha(if (enabled) 1f else .45f).clip(RoundedCornerShape(26.dp)).background(color).testTag("pad-${texture.id}")
            .drawBehind {
                finger?.let { at ->
                    drawCircle(Color.White.copy(alpha = .55f), radius = 46.dp.toPx(), center = at)
                    drawCircle(MuralColors.Orange.copy(alpha = .35f), radius = 22.dp.toPx(), center = at)
                }
            }
            .pointerInput(texture.id, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    fun send(phase: TouchPhase, change: PointerInputChange) {
                        onEvent(TouchEvent(texture.id, phase,
                            (change.position.x / size.width).coerceIn(0f, 1f),
                            (change.position.y / size.height).coerceIn(0f, 1f), change.uptimeMillis))
                    }
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    finger = down.position
                    send(TouchPhase.Down, down)
                    var last = down
                    do {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) {
                            if (change.position != last.position) send(TouchPhase.Move, change)
                            finger = change.position
                            change.consume()
                        }
                        last = change
                    } while (change.pressed)
                    send(TouchPhase.Up, last)
                    finger = null
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(padLabel(texture), style = MaterialTheme.typography.titleMedium, color = MuralColors.Ink,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 6.dp))
    }
}
