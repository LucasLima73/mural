package chat.mural

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import chat.mural.core.AccountFailure
import chat.mural.ui.MuralApp
import chat.mural.ui.MuralStartup
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val account: AccountViewModel by viewModels()
    private val purchases: MinutePurchaseViewModel by viewModels()
    private val rooms: RoomViewModel by viewModels()
    private val changingAccount get() = account.transitionBusy.value
    private val welcome by lazy { WelcomeStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        purchases.bindAccountState(account.state, account.transitionBusy)
        lifecycleScope.launch {
            combine(account.state, account.transitionBusy) { state, changing -> state.copy(busy = state.busy || changing) }
                .collect { state -> purchases.onAccountChanged(state) }
        }
        lifecycleScope.launch {
            purchases.balanceChanges.collect { account.refresh() }
        }
        setContent {
            val accountTransitionBusy by account.transitionBusy.collectAsStateWithLifecycle()
            var likes by remember { mutableStateOf(welcome.likes) }
            MuralStartup(loading = false) {
                MuralApp(
                    rooms = rooms,
                    welcomed = welcome.welcomed,
                    likes = likes,
                    onWelcomed = { name, picked ->
                        rooms.name = name
                        welcome.welcomed = true
                        welcome.likes = picked
                        likes = picked
                    },
                    account = account,
                    onGoogleSignIn = ::signInWithGoogle,
                    onSignOut = { changeAccount(delete = false) },
                    onDeleteAccount = { changeAccount(delete = true) },
                    accountTransitionBusy = accountTransitionBusy,
                    purchases = purchases,
                    onBuyMinutes = { sku -> if (!changingAccount) purchases.launch(this@MainActivity, sku) },
                )
            }
        }
    }

    private fun signInWithGoogle() {
        val config = account.configuration ?: return
        val ticket = account.beginSignInTransition() ?: return
        synchronizeAccountState()
        // Credential Manager receives the current Activity only for this lifecycle-bound call.
        // Rotation cancels the chooser; no Activity or provider token is retained in the ViewModel.
        lifecycleScope.launch {
            try {
                account.refreshAndWait()
                // AccountController rejects a different Google account before saving its bearer.
                account.signIn(expectedAccountID = null) { nonce ->
                    try {
                        val option = GetSignInWithGoogleOption.Builder(config.googleServerClientID).setNonce(nonce).build()
                        val result = CredentialManager.create(this@MainActivity).getCredential(this@MainActivity,
                            GetCredentialRequest.Builder().addCredentialOption(option).build())
                        val credential = result.credential as? CustomCredential ?: throw AccountFailure.Google
                        if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) throw AccountFailure.Google
                        GoogleIdTokenCredential.createFrom(credential.data).idToken
                    } catch (_: GetCredentialCancellationException) { throw CancellationException("Google sign-in cancelled") }
                    catch (_: NoCredentialException) { throw AccountFailure.Google }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { throw AccountFailure.Google }
                }
                if (account.state.value.signedIn) account.refreshAndWait()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(this@MainActivity, getString(R.string.account_error_google), Toast.LENGTH_LONG).show() }
            finally { account.endSignInTransition(ticket); synchronizeAccountState() }
        }
    }

    private fun changeAccount(delete: Boolean) {
        if (changingAccount || account.state.value.busy) return
        account.changeAccount(delete, prepare = { true }, finished = {})
        synchronizeAccountState()
    }

    private fun synchronizeAccountState() {
        purchases.onAccountChanged(account.state.value.copy(busy = account.state.value.busy || changingAccount))
    }

    override fun onStart() {
        super.onStart()
        account.refresh()
        purchases.onForeground(account.state.value.copy(busy = account.state.value.busy || changingAccount))
    }
}
