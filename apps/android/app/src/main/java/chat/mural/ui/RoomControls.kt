package chat.mural.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.mural.R
import chat.mural.RoomViewModel
import chat.mural.core.RoomChatLine
import chat.mural.core.RoomFailure
import chat.mural.core.RoomProtocol
import chat.mural.core.RoomState
import chat.mural.core.RoomStatus
import chat.mural.core.VoiceProblem
import chat.mural.core.VoiceState
import chat.mural.core.VoiceStatus

@Composable
internal fun failureText(failure: RoomFailure) = stringResource(when (failure) {
    RoomFailure.NotFound -> R.string.rooms_failure_not_found
    RoomFailure.Full -> R.string.rooms_failure_full
    RoomFailure.Busy -> R.string.rooms_failure_busy
    RoomFailure.RateLimited -> R.string.rooms_failure_rate_limited
    RoomFailure.Offline -> R.string.rooms_failure_offline
    RoomFailure.Lost -> R.string.rooms_lost
    RoomFailure.Unavailable -> R.string.rooms_failure_unavailable
})

/** The code, who is here, and the room's buttons. Replaces the page heading while in a room. */
@Composable
internal fun RoomBar(
    state: RoomState, voice: VoiceState, micDenied: Boolean, onChat: () -> Unit, onRejoin: () -> Unit, onLeave: () -> Unit,
    onVoiceStart: () -> Unit, onVoiceStop: () -> Unit, onPushToTalk: (Boolean) -> Unit, onMute: (Boolean) -> Unit, onTalking: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val code = state.code.orEmpty()
    val lost = state.failure == RoomFailure.Lost
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp).testTag("room-bar"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.rooms_code_label).uppercase(), style = MaterialTheme.typography.labelSmall,
                    color = MuralColors.Secondary, letterSpacing = 1.5.sp)
                Text(code.chunked(3).joinToString(" ").ifEmpty { "···" }, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 3.sp),
                    modifier = Modifier.testTag("room-code").semantics { contentDescription = code.toList().joinToString(" ") })
            }
            if (code.isNotEmpty()) MuralTextButton({
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.rooms_share_message, code)), null))
            }, Modifier.testTag("room-share")) { Text(stringResource(R.string.rooms_share)) }
            if (state.status == RoomStatus.InRoom) Box {
                SoftRoundButton(MuralSymbol.Transcript, stringResource(R.string.rooms_chat), onChat, Modifier.testTag("room-chat"), diameter = 44.dp)
                if (state.unread > 0) Box(Modifier.align(Alignment.TopEnd).size(18.dp).background(MuralColors.Orange, CircleShape).testTag("room-unread"),
                    contentAlignment = Alignment.Center) {
                    Text(minOf(state.unread, 9).toString(), style = MaterialTheme.typography.labelSmall, color = MuralColors.Ink)
                }
            }
            SoftRoundButton(MuralSymbol.Close, stringResource(R.string.rooms_leave), onLeave, Modifier.testTag("room-leave"), diameter = 44.dp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                lost -> stringResource(R.string.rooms_lost)
                state.status == RoomStatus.Connecting -> stringResource(R.string.rooms_status_connecting)
                state.peers.isEmpty() -> stringResource(R.string.rooms_status_waiting)
                else -> stringResource(R.string.rooms_status_with, state.peers.joinToString(", ") { it.name.ifBlank { "?" } })
            }, style = MaterialTheme.typography.bodyMedium, color = if (lost) MuralColors.Red else MuralColors.Secondary,
                modifier = Modifier.weight(1f).testTag("room-status"))
            if (lost) MuralTextButton(onRejoin, Modifier.testTag("room-rejoin")) { Text(stringResource(R.string.rooms_rejoin)) }
        }
        if (state.status == RoomStatus.InRoom) VoiceRow(state, voice, micDenied, onVoiceStart, onVoiceStop, onPushToTalk, onMute, onTalking)
    }
}

@Composable
private fun problemText(problem: VoiceProblem) = stringResource(when (problem) {
    VoiceProblem.Permission -> R.string.voice_problem_permission
    VoiceProblem.Busy -> R.string.voice_problem_busy
    VoiceProblem.Interrupted -> R.string.voice_problem_interrupted
    VoiceProblem.Audio -> R.string.voice_problem_audio
    VoiceProblem.Network -> R.string.voice_problem_network
})

/** Voice chat controls: turn it on, mute or hold to talk, and say how it is going. */
@Composable
private fun VoiceRow(
    state: RoomState, voice: VoiceState, micDenied: Boolean, onStart: () -> Unit, onStop: () -> Unit,
    onPushToTalk: (Boolean) -> Unit, onMute: (Boolean) -> Unit, onTalking: (Boolean) -> Unit,
) {
    val on = voice.status != VoiceStatus.Off && voice.status != VoiceStatus.Failed
    val friendOnVoice = state.peers.firstOrNull { it.voice }
    Column(Modifier.fillMaxWidth().testTag("voice-row"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!on) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onStart, Modifier.testTag("voice-start"), shape = CircleShape,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MuralColors.Peach, contentColor = MuralColors.Ink)) {
                    MuralIcon(MuralSymbol.Mic, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.rooms_voice), style = MaterialTheme.typography.labelLarge)
                }
                val problem = if (micDenied) VoiceProblem.Permission else voice.problem
                Text(
                    when {
                        problem != null -> problemText(problem)
                        friendOnVoice != null -> stringResource(R.string.rooms_voice_friend_on, friendOnVoice.name.ifBlank { "?" })
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall, color = if (problem != null) MuralColors.Red else MuralColors.Secondary,
                    modifier = Modifier.weight(1f).testTag("voice-note"),
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MicButton(voice, onMute, onTalking)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(when (voice.status) {
                        VoiceStatus.Live -> R.string.rooms_voice_live
                        VoiceStatus.Connecting -> R.string.rooms_voice_connecting
                        else -> R.string.rooms_voice_waiting
                    }), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("voice-status"))
                    Text(stringResource(when {
                        voice.pushToTalk -> R.string.rooms_voice_hold
                        voice.muted -> R.string.rooms_voice_muted
                        else -> R.string.rooms_voice_open
                    }), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
                }
            }
            // The two choices sit on their own line so the status text always has room.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuralTextButton({ onPushToTalk(!voice.pushToTalk) }, Modifier.testTag("voice-mode")) {
                    Text(stringResource(if (voice.pushToTalk) R.string.rooms_voice_switch_open else R.string.rooms_voice_switch_ptt))
                }
                MuralTextButton(onStop, Modifier.testTag("voice-stop")) { Text(stringResource(R.string.rooms_voice_end)) }
            }
        }
    }
}

/** Tap to mute or unmute with an open microphone; press and hold to talk with push to talk. */
@Composable
private fun MicButton(voice: VoiceState, onMute: (Boolean) -> Unit, onTalking: (Boolean) -> Unit) {
    val live = voice.micLive
    val description = stringResource(when {
        voice.pushToTalk -> R.string.rooms_mic_hold
        voice.muted -> R.string.rooms_mic_unmute
        else -> R.string.rooms_mic_mute
    })
    val gesture = if (voice.pushToTalk) Modifier.pointerInput(true) {
        detectTapGestures(onPress = {
            onTalking(true)
            try { tryAwaitRelease() } finally { onTalking(false) }
        })
    } else Modifier.clickable(role = Role.Button) { onMute(!voice.muted) }
    Box(
        Modifier.size(56.dp).background(if (live) MuralColors.Orange else Color.White.copy(alpha = .78f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = .7f), CircleShape).clip(CircleShape).then(gesture)
            .semantics { contentDescription = description }.testTag("voice-mic"),
        contentAlignment = Alignment.Center,
    ) { MuralIcon(if (live) MuralSymbol.Mic else MuralSymbol.MicOff, Modifier.size(26.dp)) }
}

@Composable
internal fun RoomEntryDialog(joining: Boolean, initialName: String, onDismiss: () -> Unit, onConfirm: (name: String, code: String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var code by rememberSaveable { mutableStateOf("") }
    val valid = !joining || RoomProtocol.normalizeCode(code) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (joining) R.string.rooms_join_title else R.string.rooms_create_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MuralTextField(name, { name = it.take(RoomProtocol.MAX_NAME) }, Modifier.fillMaxWidth().testTag("room-name-input"), singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    label = { Text(stringResource(R.string.rooms_name_label)) })
                if (joining) MuralTextField(code, { code = it.take(8) }, Modifier.fillMaxWidth().testTag("room-code-input"), singleLine = true,
                    label = { Text(stringResource(R.string.rooms_code_label)) },
                    supportingText = { Text(stringResource(R.string.rooms_code_hint)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
            }
        },
        confirmButton = {
            MuralTextButton({ onConfirm(name.trim(), code) }, enabled = valid, modifier = Modifier.testTag("room-entry-confirm")) {
                Text(stringResource(if (joining) R.string.rooms_join_confirm else R.string.rooms_create_confirm))
            }
        },
        dismissButton = { MuralTextButton(onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoomChatSheet(state: RoomState, room: RoomViewModel, onDismiss: () -> Unit) {
    // Lines that arrive while the sheet is open count as read.
    DisposableEffect(room) { room.setChatVisible(true); onDispose { room.setChatVisible(false) } }
    var text by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(state.chat.size) { if (state.chat.isNotEmpty()) list.animateScrollToItem(state.chat.lastIndex) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MuralColors.Cream, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(.9f).imePadding().padding(horizontal = 24.dp).padding(bottom = 16.dp).testTag("room-chat-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.rooms_chat), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                SoftRoundButton(MuralSymbol.Close, stringResource(R.string.common_close), onDismiss, diameter = 40.dp)
            }
            if (state.chat.isEmpty()) Text(stringResource(R.string.rooms_chat_empty), color = MuralColors.Secondary,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.chat, key = { it.id }) { line -> ChatBubble(line) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MuralTextField(text, { text = it.take(RoomProtocol.MAX_CHAT) }, Modifier.weight(1f).testTag("room-chat-input"), maxLines = 4,
                    label = { Text(stringResource(R.string.rooms_chat_field)) })
                SoftRoundButton(MuralSymbol.ArrowUp, stringResource(R.string.rooms_chat_send),
                    { if (room.sendChat(text)) text = "" }, Modifier.testTag("room-chat-send"), diameter = 52.dp,
                    tint = MuralColors.Orange, enabled = text.isNotBlank())
            }
        }
    }
}

@Composable
private fun ChatBubble(line: RoomChatLine) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (line.fromMe) Alignment.End else Alignment.Start) {
        if (!line.fromMe && line.name.isNotBlank()) Text(line.name, style = MaterialTheme.typography.labelSmall, color = MuralColors.Secondary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        Box(Modifier.background(if (line.fromMe) MuralColors.Peach else MuralColors.Surface, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(line.text, style = MaterialTheme.typography.bodyMedium, color = MuralColors.Ink)
        }
    }
}

/** Above the pads while you are on your own: a small way to start playing with a friend. */
@Composable
internal fun SoloBar(state: RoomState, available: Boolean, onCreate: () -> Unit, onJoin: () -> Unit, onDismissFailure: () -> Unit) {
    val connecting = state.status == RoomStatus.Connecting
    val failure = if (state.status == RoomStatus.Failed) state.failure else null
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp).testTag("solo-bar"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (connecting) R.string.rooms_status_connecting else R.string.pads_solo_label),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).testTag("solo-status"))
            if (available) {
                val padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                Button(onCreate, Modifier.height(40.dp).testTag("room-create"), enabled = !connecting, shape = CircleShape, contentPadding = padding) {
                    Text(stringResource(R.string.rooms_create), style = MaterialTheme.typography.labelLarge)
                }
                Button(onJoin, Modifier.height(40.dp).testTag("room-join"), enabled = !connecting, shape = CircleShape, contentPadding = padding,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MuralColors.Peach, contentColor = MuralColors.Ink)) {
                    Text(stringResource(R.string.rooms_join_short), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (failure != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(failureText(failure), style = MaterialTheme.typography.bodySmall, color = MuralColors.Red, modifier = Modifier.weight(1f).testTag("room-failure"))
            if (failure != RoomFailure.Unavailable) MuralTextButton(onDismissFailure) { Text(stringResource(R.string.common_ok)) }
        }
    }
}

/** Asks for a name (and a code, to join) and then makes or joins the room. `entry` is null, false to create, true to join. */
@Composable
internal fun RoomEntryFlow(room: RoomViewModel, entry: Boolean?, onDismiss: () -> Unit) {
    entry?.let { joining ->
        RoomEntryDialog(joining, room.name, onDismiss) { name, code ->
            onDismiss()
            room.name = name
            if (joining) room.join(code, name) else room.create(name)
        }
    }
}
