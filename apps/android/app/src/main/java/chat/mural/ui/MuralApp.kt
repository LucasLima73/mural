package chat.mural.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.mural.AccountViewModel
import chat.mural.MinutePurchaseViewModel
import chat.mural.R
import chat.mural.RoomViewModel
import chat.mural.core.RoomFailure
import chat.mural.core.RoomStatus
import chat.mural.core.TouchBehavior

private const val PADS_TAB = 2

private val TABS = listOf(
    NavTab(R.string.tab_home, MuralSymbol.Home, "tab-home"),
    NavTab(R.string.tab_play, MuralSymbol.Sparkles, "tab-play"),
    NavTab(R.string.tab_pads, MuralSymbol.Pads, "tab-pads"),
)

/**
 * The welcome screens on first run, then three tabs: Home, Play (the satisfying toys) and Pads, which play on your
 * own or with a friend in a room. The settings gear opens account and notices from either tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuralApp(
    rooms: RoomViewModel,
    welcomed: Boolean = true,
    likes: List<TouchBehavior> = emptyList(),
    onWelcomed: (name: String, likes: List<TouchBehavior>) -> Unit = { _, _ -> },
    account: AccountViewModel? = null,
    onGoogleSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onDeleteAccount: () -> Unit = {},
    accountTransitionBusy: Boolean = false,
    purchases: MinutePurchaseViewModel? = null,
    onBuyMinutes: (String) -> Unit = {},
) {
    val room by rooms.state.collectAsStateWithLifecycle()
    var done by rememberSaveable { mutableStateOf(welcomed) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showMinutes by rememberSaveable { mutableStateOf(false) }
    val joined = room.status == RoomStatus.InRoom
    // A dropped connection keeps you in the room, so you can rejoin it; every other failure means you are not in one.
    val inRoom = joined || (room.status == RoomStatus.Failed && room.failure == RoomFailure.Lost)
    var padCategory by rememberSaveable { mutableStateOf(likes.firstOrNull() ?: TouchBehavior.Scrub) }
    // Joining a room takes you to the pads to play; nothing ever moves you away from a tab you chose.
    var wasJoined by rememberSaveable { mutableStateOf(joined) }
    LaunchedEffect(joined) {
        if (joined && !wasJoined) tab = PADS_TAB
        wasJoined = joined
    }

    MuralTheme {
        Surface(Modifier.fillMaxSize(), color = MuralColors.Cream) {
            if (!done) {
                OnboardingScreen(initialName = rooms.name) { name, picked ->
                    onWelcomed(name, picked)
                    // The pads open on the first kind of sound the person said they like.
                    picked.firstOrNull()?.let { padCategory = it }
                    done = true
                }
            } else {
                Scaffold(
                    containerColor = MuralColors.Cream,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    topBar = {
                        Row(
                            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Brand()
                            Spacer(Modifier.weight(1f))
                            SoftRoundButton(MuralSymbol.Settings, stringResource(R.string.settings_tab_title),
                                onClick = { showSettings = true }, modifier = Modifier.testTag("tab-settings"), diameter = 44.dp)
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        Column(Modifier.fillMaxSize().padding(bottom = 90.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
                            when (tab) {
                                0 -> HomeScreen(rooms, likes, onOpenPads = { tab = PADS_TAB }, onOpenCategory = { padCategory = it; tab = PADS_TAB })
                                1 -> PlayScreen()
                                else -> PadsScreen(rooms, padCategory, onCategory = { padCategory = it })
                            }
                        }
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(108.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, MuralColors.Cream.copy(alpha = .92f)))))
                        Box(Modifier.align(Alignment.BottomCenter)) { FloatingNavigation(TABS, tab) { tab = it } }
                    }
                }
            }

            if (showSettings) {
                ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = MuralColors.Cream,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    Column(Modifier.fillMaxHeight(.94f)) {
                        SettingsScreen(onAccount = if (account?.configuration != null) ({
                            account.refresh(); showAccount = true
                        }) else null, onDismiss = { showSettings = false })
                    }
                }
            }

            if (showAccount && !showMinutes && account?.configuration != null) {
                val accountState by account.state.collectAsStateWithLifecycle()
                AccountSheet(accountState, onDismiss = { showAccount = false }, onSignIn = onGoogleSignIn,
                    onSignOut = onSignOut, onDelete = onDeleteAccount, onRefresh = account::refresh,
                    transitionBusy = accountTransitionBusy,
                    onBuyMinutes = if (purchases?.enabled == true) ({ purchases.refresh(); showMinutes = true }) else null)
            }
            if (showMinutes && purchases?.enabled == true && account != null) {
                val purchaseState by purchases.state.collectAsStateWithLifecycle()
                val accountState by account.state.collectAsStateWithLifecycle()
                MinutePurchaseSheet(purchaseState, accountState.signedIn, onBuyMinutes,
                    onSignIn = onGoogleSignIn, onRefresh = purchases::refresh,
                    onDismiss = { showMinutes = false; account.refresh() },
                    accountBusy = accountTransitionBusy || accountState.busy)
            }
        }
    }
}
