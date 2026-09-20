package chat.mural.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.mural.R
import chat.mural.RoomViewModel
import chat.mural.core.RoomFailure
import chat.mural.core.RoomState
import chat.mural.core.RoomStatus
import chat.mural.core.TouchBehavior
import chat.mural.core.TouchTextures
import java.time.LocalTime

private val COLLECTIONS = listOf(
    Triple(TouchBehavior.Scrub, MuralSymbol.Wave, MuralColors.Peach),
    Triple(TouchBehavior.Tap, MuralSymbol.Sparkles, MuralColors.Lilac),
    Triple(TouchBehavior.Hold, MuralSymbol.Leaf, MuralColors.Sage),
)

/** The first tab: start playing, invite a friend, or pick a family of sounds to explore. */
@Composable
fun HomeScreen(room: RoomViewModel, likes: List<TouchBehavior>, onOpenPads: () -> Unit, onOpenCategory: (TouchBehavior) -> Unit, modifier: Modifier = Modifier) {
    val state by room.state.collectAsStateWithLifecycle()
    var entry by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val inRoom = state.status == RoomStatus.InRoom || (state.status == RoomStatus.Failed && state.failure == RoomFailure.Lost)

    LazyColumn(modifier.fillMaxSize().testTag("home-screen"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Greeting(room.name) }
        item { SoloCard(onOpenPads) }
        item {
            if (inRoom) InRoomCard(state, onOpenPads, onLeave = { room.stopVoice(); room.leave() })
            else FriendCard(state, room.available, onCreate = { room.dismissFailure(); entry = false },
                onJoin = { room.dismissFailure(); entry = true }, onDismissFailure = room::dismissFailure)
        }
        item { Text(stringResource(R.string.home_collections), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp)) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("home-collections")) {
                // What the person said they like comes first.
                items(COLLECTIONS.sortedBy { if (it.first in likes) 0 else 1 }) { (behavior, symbol, color) ->
                    CollectionCard(behavior, symbol, color, liked = behavior in likes) { onOpenCategory(behavior) }
                }
            }
        }
        item { Text(stringResource(R.string.home_tip), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary) }
    }
    RoomEntryFlow(room, entry, onDismiss = { entry = null })
}

@Composable
private fun Greeting(name: String) {
    val hour = remember { LocalTime.now().hour }
    val greeting = stringResource(when (hour) {
        in 5..11 -> R.string.home_greeting_morning
        in 12..17 -> R.string.home_greeting_afternoon
        in 18..22 -> R.string.home_greeting_evening
        else -> R.string.home_greeting_night
    })
    Row(Modifier.fillMaxWidth().testTag("home-greeting"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (name.isBlank()) greeting else "$greeting, $name", style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyMedium, color = MuralColors.Secondary)
        }
        MuralOrb(modifier = Modifier.size(88.dp))
    }
}

@Composable
private fun SoloCard(onOpenPads: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MuralColors.Butter).testTag("home-solo")
        .clickable(role = Role.Button, onClick = onOpenPads).padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.home_solo_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.home_solo_body), style = MaterialTheme.typography.bodyMedium, color = MuralColors.Secondary)
        }
        Spacer(Modifier.width(12.dp))
        MuralIcon(MuralSymbol.ChevronRight, Modifier.size(22.dp))
    }
}

@Composable
private fun FriendCard(state: RoomState, available: Boolean, onCreate: () -> Unit, onJoin: () -> Unit, onDismissFailure: () -> Unit) {
    val connecting = state.status == RoomStatus.Connecting
    val failure = when {
        !available -> RoomFailure.Unavailable
        state.status == RoomStatus.Failed -> state.failure
        else -> null
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MuralColors.Peach).testTag("home-friend").padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.home_friend_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.home_friend_body), style = MaterialTheme.typography.bodyMedium, color = MuralColors.Secondary)
        Spacer(Modifier.height(6.dp))
        if (available) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onCreate, Modifier.testTag("room-create"), enabled = !connecting, shape = CircleShape) {
                Text(stringResource(R.string.rooms_create), style = MaterialTheme.typography.labelLarge)
            }
            MuralTextButton(onJoin, Modifier.testTag("room-join"), enabled = !connecting) { Text(stringResource(R.string.rooms_join)) }
            if (connecting) Text(stringResource(R.string.rooms_status_connecting), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
        }
        if (failure != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(failureText(failure), style = MaterialTheme.typography.bodySmall, color = MuralColors.Red, modifier = Modifier.weight(1f).testTag("room-failure"))
            if (failure != RoomFailure.Unavailable) MuralTextButton(onDismissFailure) { Text(stringResource(R.string.common_ok)) }
        }
    }
}

@Composable
private fun InRoomCard(state: RoomState, onOpenPads: () -> Unit, onLeave: () -> Unit) {
    val lost = state.failure == RoomFailure.Lost
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(MuralColors.Peach).testTag("home-room-card").padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.home_in_room).uppercase(), style = MaterialTheme.typography.labelSmall, color = MuralColors.Secondary)
        Text(state.code.orEmpty().chunked(3).joinToString(" "), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("home-room-code"))
        Text(when {
            lost -> stringResource(R.string.rooms_lost)
            state.peers.isEmpty() -> stringResource(R.string.rooms_status_waiting)
            else -> stringResource(R.string.rooms_status_with, state.peers.joinToString(", ") { it.name.ifBlank { "?" } })
        }, style = MaterialTheme.typography.bodyMedium, color = if (lost) MuralColors.Red else MuralColors.Secondary)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onOpenPads, Modifier.testTag("home-open-pads"), shape = CircleShape) {
                Text(stringResource(R.string.home_open_pads), style = MaterialTheme.typography.labelLarge)
            }
            MuralTextButton(onLeave, Modifier.testTag("home-leave")) { Text(stringResource(R.string.rooms_leave)) }
        }
    }
}

@Composable
private fun CollectionCard(behavior: TouchBehavior, symbol: MuralSymbol, color: androidx.compose.ui.graphics.Color, liked: Boolean, onClick: () -> Unit) {
    val textures = TouchTextures.all.filter { it.behavior == behavior }
    val names = textures.take(3).map { padLabel(it) }
    Column(Modifier.width(168.dp).clip(RoundedCornerShape(26.dp)).background(color).testTag("home-collection-${behavior.name.lowercase()}")
        .clickable(role = Role.Button, onClick = onClick).padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MuralIcon(symbol, Modifier.size(28.dp))
            Spacer(Modifier.weight(1f))
            if (liked) Text(stringResource(R.string.home_for_you), style = MaterialTheme.typography.labelSmall, color = MuralColors.Secondary,
                modifier = Modifier.background(Color.White.copy(alpha = .7f), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(categoryFor(behavior)), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.home_collection_count, textures.size), style = MaterialTheme.typography.labelMedium, color = MuralColors.Secondary)
        Text(names.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
