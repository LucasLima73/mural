package chat.mural

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import chat.mural.core.RoomSession
import chat.mural.core.VoiceSignal
import chat.mural.core.TouchEvent
import chat.mural.network.OkHttpRoomTransport
import chat.mural.network.RoomsConfiguration
import chat.mural.network.VoiceChat
import chat.mural.network.VoiceOutbox
import kotlinx.coroutines.launch

/** Keeps a room alive while the person moves between tabs, and remembers the name they play under. */
class RoomViewModel(application: Application) : AndroidViewModel(application) {
    private val url = RoomsConfiguration.webSocketUrl(BuildConfig.ROOMS_ORIGIN, BuildConfig.DEBUG)
    private val preferences = application.getSharedPreferences("rooms", Context.MODE_PRIVATE)
    private val session = RoomSession({ url?.let(::OkHttpRoomTransport) }, SystemClock::elapsedRealtime)

    private val voiceChat = VoiceChat(application, object : VoiceOutbox {
        override fun voice(on: Boolean) = session.sendVoice(on)
        override fun signal(to: String, signal: VoiceSignal) = session.sendSignal(to, signal)
    })

    init {
        // Voice reads the room's events in order, and keeps listening while the Pads tab is not on screen.
        viewModelScope.launch { session.signals.collect(voiceChat::onSignal) }
    }

    /** False when this build has no room server configured, so the app can offer solo pads only. */
    val available = url != null
    val state = session.state
    val signals = session.signals
    val voice = voiceChat.state

    var name: String
        get() = preferences.getString(NAME_KEY, "").orEmpty()
        set(value) = preferences.edit().putString(NAME_KEY, value).apply()

    fun create(name: String) = session.create(name)
    fun join(code: String, name: String) = session.join(code, name)
    fun rejoin() = session.rejoin()
    fun leave() = session.leave()
    fun dismissFailure() = session.dismissFailure()
    fun sendTouch(event: TouchEvent) = session.sendTouch(event)
    fun sendRelease() = session.sendRelease()
    fun sendChat(text: String) = session.sendChat(text)
    fun setChatVisible(visible: Boolean) = session.setChatVisible(visible)

    /** The caller has already asked for the microphone permission. */
    fun startVoice() = voiceChat.start(state.value.myId)
    fun stopVoice() = voiceChat.stop()
    fun setPushToTalk(enabled: Boolean) = voiceChat.setPushToTalk(enabled)
    fun setMuted(muted: Boolean) = voiceChat.setMuted(muted)
    fun setTalking(talking: Boolean) = voiceChat.setTalking(talking)

    override fun onCleared() {
        voiceChat.release()
        session.leave()
    }

    private companion object {
        const val NAME_KEY = "name"
    }
}
