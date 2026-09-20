package chat.mural.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** A connection to the room server. Every attempt uses a fresh one, so an old socket can never touch a new room. */
interface RoomTransport {
    fun connect(listener: Listener)
    fun send(text: String): Boolean
    fun close()

    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        /** Called once when the connection ends or could not start, for any reason. */
        fun onClosed()
    }
}

enum class RoomStatus { Idle, Connecting, InRoom, Failed }
enum class RoomFailure { NotFound, Full, Busy, RateLimited, Offline, Lost, Unavailable }

data class RoomChatLine(val id: Long, val fromMe: Boolean, val name: String, val text: String, val at: Long)

data class RoomState(
    val status: RoomStatus = RoomStatus.Idle,
    val code: String? = null,
    val myId: String? = null,
    val peers: List<RoomPeer> = emptyList(),
    val maxPeers: Int = 2,
    val chat: List<RoomChatLine> = emptyList(),
    val unread: Int = 0,
    val failure: RoomFailure? = null,
)

/** What a friend did that the sound engine has to act on. */
sealed interface RoomSignal {
    data class Touch(val from: String, val event: TouchEvent) : RoomSignal
    /** A step in setting up voice with a friend. */
    data class Voice(val from: String, val signal: VoiceSignal) : RoomSignal
    /**
     * A friend turned voice chat on or off. Voice needs this in the same ordered stream as its setup steps,
     * so an offer can never be handled before the phone knows the friend is there for it.
     */
    data class PeerVoice(val from: String, val on: Boolean) : RoomSignal
    data class PeerLeft(val id: String) : RoomSignal
    /** This phone is no longer in a room, whether it left or lost the connection. */
    data object RoomClosed : RoomSignal
    /** A friend lifted every finger, left, or dropped off: silence whatever they were holding. */
    data class Silence(val from: String) : RoomSignal
}

/**
 * One phone's place in a room: create or join, hear the friend's touches, chat, and notice when the connection
 * is lost. Methods may be called from the UI and from network threads, so all state changes hold one lock.
 */
class RoomSession(
    private val newTransport: () -> RoomTransport?,
    private val clock: () -> Long,
) {
    private sealed interface Intent {
        data class Create(val name: String) : Intent
        data class Join(val code: String, val name: String) : Intent
    }

    private val lock = Any()
    private var transport: RoomTransport? = null
    private var generation = 0L
    private var intent: Intent? = null
    private var myName = ""
    private var chatVisible = false
    private var nextLineId = 0L
    private val lastMoveSent = mutableMapOf<String, Long>()

    private val mutableState = MutableStateFlow(RoomState())
    val state: StateFlow<RoomState> = mutableState

    // Movement is disposable: if the collector is slow, old moves are dropped and the latest ones survive.
    private val mutableSignals = MutableSharedFlow<RoomSignal>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val signals: SharedFlow<RoomSignal> = mutableSignals

    fun create(name: String) = start(Intent.Create(name), keepChat = false)

    /** Returns false, without connecting, when the code cannot be a real one. */
    fun join(code: String, name: String): Boolean {
        val normalized = RoomProtocol.normalizeCode(code) ?: return false
        start(Intent.Join(normalized, name), keepChat = false)
        return true
    }

    /** After a lost connection: come back to the same room, keeping the chat so far. */
    fun rejoin() = synchronized(lock) {
        val code = mutableState.value.code ?: return@synchronized
        if (mutableState.value.failure != RoomFailure.Lost) return@synchronized
        start(Intent.Join(code, myName), keepChat = true)
    }

    fun leave() = synchronized(lock) {
        transport?.send(RoomProtocol.leave())
        dropTransport()
        silenceEveryone()
        mutableState.value = RoomState()
    }

    /** Clears a failure message and goes back to the start. */
    fun dismissFailure() = synchronized(lock) {
        if (mutableState.value.status == RoomStatus.Failed) mutableState.value = RoomState()
    }

    fun sendTouch(event: TouchEvent) = synchronized(lock) {
        if (mutableState.value.status != RoomStatus.InRoom) return@synchronized
        val now = clock()
        when (event.phase) {
            TouchPhase.Move -> {
                val last = lastMoveSent[event.texture]
                if (last != null && now - last < MOVE_INTERVAL_MILLIS) return@synchronized
                lastMoveSent[event.texture] = now
            }
            TouchPhase.Down -> lastMoveSent[event.texture] = now
            TouchPhase.Up -> lastMoveSent.remove(event.texture)
        }
        transport?.send(RoomProtocol.touch(event))
    }

    /** Tell the friend to stop everything I was holding, for when my fingers leave without an Up event. */
    fun sendRelease() = synchronized(lock) {
        if (mutableState.value.status != RoomStatus.InRoom) return@synchronized
        lastMoveSent.clear()
        transport?.send(RoomProtocol.release())
    }

    fun sendChat(text: String): Boolean = synchronized(lock) {
        val clean = text.trim()
        if (clean.isEmpty() || mutableState.value.status != RoomStatus.InRoom) return@synchronized false
        transport?.send(RoomProtocol.chat(clean)) == true
    }

    /** Tell friends whether voice chat is on for me, so whoever should start the connection can. */
    fun sendVoice(on: Boolean) = synchronized(lock) {
        if (mutableState.value.status == RoomStatus.InRoom) transport?.send(RoomProtocol.voice(on))
    }

    fun sendSignal(to: String, signal: VoiceSignal) = synchronized(lock) {
        if (mutableState.value.status == RoomStatus.InRoom) transport?.send(RoomProtocol.signal(to, signal))
    }

    fun setChatVisible(visible: Boolean) = synchronized(lock) {
        chatVisible = visible
        if (visible && mutableState.value.unread != 0) mutableState.value = mutableState.value.copy(unread = 0)
    }

    private fun start(next: Intent, keepChat: Boolean) = synchronized(lock) {
        dropTransport()
        silenceEveryone()
        lastMoveSent.clear()
        intent = next
        myName = when (next) { is Intent.Create -> next.name; is Intent.Join -> next.name }
        val connection = newTransport()
        if (connection == null) {
            mutableState.value = RoomState(status = RoomStatus.Failed, failure = RoomFailure.Unavailable)
            return@synchronized
        }
        transport = connection
        val attempt = generation
        mutableState.value = RoomState(
            status = RoomStatus.Connecting,
            code = (next as? Intent.Join)?.code,
            chat = if (keepChat) mutableState.value.chat else emptyList(),
        )
        connection.connect(object : RoomTransport.Listener {
            override fun onOpen() = synchronized(lock) { if (attempt == generation) opened() }
            override fun onMessage(text: String) = synchronized(lock) { if (attempt == generation) received(text) }
            override fun onClosed() = synchronized(lock) { if (attempt == generation) closed() }
        })
    }

    /** Ends the current connection; anything it reports afterwards is ignored. */
    private fun dropTransport() {
        generation++
        transport?.close()
        transport = null
    }

    private fun silenceEveryone() {
        mutableState.value.peers.forEach { mutableSignals.tryEmit(RoomSignal.Silence(it.id)) }
        mutableSignals.tryEmit(RoomSignal.RoomClosed)
    }

    private fun opened() {
        val text = when (val current = intent) {
            is Intent.Create -> RoomProtocol.create(current.name)
            is Intent.Join -> RoomProtocol.join(current.code, current.name)
            null -> return
        }
        if (transport?.send(text) != true) failed(RoomFailure.Offline)
    }

    private fun received(text: String) {
        val event = RoomProtocol.parse(text) ?: return
        val state = mutableState.value
        when (event) {
            is RoomEvent.Joined -> {
                mutableState.value = state.copy(status = RoomStatus.InRoom, code = event.code, myId = event.you,
                    peers = event.peers, maxPeers = event.maxPeers, failure = null)
                event.peers.filter { it.voice }.forEach { mutableSignals.tryEmit(RoomSignal.PeerVoice(it.id, true)) }
            }
            is RoomEvent.PeerJoined -> if (state.peers.none { it.id == event.peer.id }) {
                mutableState.value = state.copy(peers = state.peers + event.peer)
            }
            is RoomEvent.PeerLeft -> {
                mutableSignals.tryEmit(RoomSignal.Silence(event.id))
                mutableSignals.tryEmit(RoomSignal.PeerLeft(event.id))
                mutableState.value = state.copy(peers = state.peers.filterNot { it.id == event.id })
            }
            is RoomEvent.RemoteTouch -> if (state.status == RoomStatus.InRoom) mutableSignals.tryEmit(RoomSignal.Touch(event.from, event.event))
            is RoomEvent.RemoteRelease -> mutableSignals.tryEmit(RoomSignal.Silence(event.from))
            is RoomEvent.Chat -> if (state.status == RoomStatus.InRoom) addChat(state, event)
            is RoomEvent.PeerVoice -> {
                mutableState.value = state.copy(peers = state.peers.map { if (it.id == event.from) it.copy(voice = event.on) else it })
                mutableSignals.tryEmit(RoomSignal.PeerVoice(event.from, event.on))
            }
            is RoomEvent.Signal -> if (state.status == RoomStatus.InRoom) mutableSignals.tryEmit(RoomSignal.Voice(event.from, event.signal))
            is RoomEvent.Failure -> if (state.status == RoomStatus.Connecting) failed(when (event.error) {
                RoomError.RoomNotFound -> RoomFailure.NotFound
                RoomError.RoomFull -> RoomFailure.Full
                RoomError.ServerBusy -> RoomFailure.Busy
                RoomError.RateLimited -> RoomFailure.RateLimited
                else -> RoomFailure.Offline
            })
        }
    }

    private fun addChat(state: RoomState, event: RoomEvent.Chat) {
        val mine = event.from == state.myId
        val name = if (mine) myName else state.peers.firstOrNull { it.id == event.from }?.name.orEmpty()
        val line = RoomChatLine(nextLineId++, mine, name, event.text, event.at)
        mutableState.value = state.copy(
            chat = (state.chat + line).takeLast(MAX_CHAT_LINES),
            unread = if (mine || chatVisible) state.unread else state.unread + 1,
        )
    }

    private fun closed() {
        when (mutableState.value.status) {
            RoomStatus.InRoom -> failed(RoomFailure.Lost)
            RoomStatus.Connecting -> failed(RoomFailure.Offline)
            else -> Unit
        }
    }

    private fun failed(reason: RoomFailure) {
        val state = mutableState.value
        dropTransport()
        silenceEveryone()
        // A lost connection keeps the code and chat so the person can come back; other failures start over.
        mutableState.value = if (reason == RoomFailure.Lost) state.copy(status = RoomStatus.Failed, failure = reason, peers = emptyList(), unread = 0)
        else RoomState(status = RoomStatus.Failed, failure = reason)
    }

    private companion object {
        const val MOVE_INTERVAL_MILLIS = 33L
        const val MAX_CHAT_LINES = 200
    }
}
