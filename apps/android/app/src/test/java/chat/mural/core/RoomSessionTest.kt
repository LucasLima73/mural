package chat.mural.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomSessionTest {
    private class FakeTransport : RoomTransport {
        var listener: RoomTransport.Listener? = null
        val sent = mutableListOf<String>()
        var closed = false
        var accepts = true
        override fun connect(listener: RoomTransport.Listener) { this.listener = listener }
        override fun send(text: String): Boolean = accepts.also { if (it) sent += text }
        override fun close() { closed = true }
        fun types() = sent.map { (Json.parseToJsonElement(it).jsonObject["type"] as JsonPrimitive).content }
        fun hear(text: String) = listener!!.onMessage(text)
    }

    private val transports = mutableListOf<FakeTransport>()
    private var now = 1_000L
    private var available = true
    private val session = RoomSession({ if (available) FakeTransport().also { transports += it } else null }, { now })
    private val current get() = transports.last()

    private fun joined(code: String = "ABC234", you: String = "me", peers: String = "") =
        """{"type":"joined","code":"$code","you":"$you","peers":[$peers],"maxPeers":2}"""
    private val ana = """{"id":"p2","name":"Ana"}"""
    private fun touch(phase: String, x: Double = .5) = """{"type":"touch","from":"p2","texture":"rain","phase":"$phase","x":$x,"y":0.5,"t":7}"""
    private fun chat(from: String, text: String) = """{"type":"chat","from":"$from","text":"$text","at":9}"""

    private fun enterRoom(peers: String = "") {
        session.create("Me")
        current.listener!!.onOpen()
        current.hear(joined(peers = peers))
    }

    private fun TestScope.collect(): List<RoomSignal> {
        val signals = mutableListOf<RoomSignal>()
        // These tests are about sound; the signals that only set up voice are left out.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            session.signals.collect { if (it is RoomSignal.Touch || it is RoomSignal.Silence) signals += it }
        }
        return signals
    }

    @Test fun creatingARoomConnectsThenAsksTheServerOnceTheSocketIsOpen() {
        session.create("Ana")
        assertEquals(RoomStatus.Connecting, session.state.value.status)
        assertTrue(current.sent.isEmpty())
        current.listener!!.onOpen()
        assertEquals(listOf("create"), current.types())
        current.hear(joined(you = "me", peers = ana))
        val state = session.state.value
        assertEquals(RoomStatus.InRoom, state.status)
        assertEquals("ABC234", state.code)
        assertEquals("me", state.myId)
        assertEquals(listOf(RoomPeer("p2", "Ana")), state.peers)
    }

    @Test fun joiningCleansTheTypedCodeAndRefusesOneThatCannotBeReal() {
        assertFalse(session.join("nope", "Bia"))
        assertTrue(transports.isEmpty())
        assertTrue(session.join(" abc-234 ", "Bia"))
        assertEquals("ABC234", session.state.value.code)
        current.listener!!.onOpen()
        val sent = Json.parseToJsonElement(current.sent.single()).jsonObject
        assertEquals("ABC234", (sent["code"] as JsonPrimitive).content)
    }

    @Test fun aRefusedJoinReportsWhyClosesTheConnectionAndCanBeDismissed() {
        for ((wire, failure) in mapOf("room-not-found" to RoomFailure.NotFound, "room-full" to RoomFailure.Full,
            "server-busy" to RoomFailure.Busy, "rate-limited" to RoomFailure.RateLimited, "bad-request" to RoomFailure.Offline)) {
            session.join("ABC234", "Bia")
            current.listener!!.onOpen()
            current.hear("""{"type":"error","code":"$wire"}""")
            assertEquals(wire, RoomStatus.Failed, session.state.value.status)
            assertEquals(wire, failure, session.state.value.failure)
            assertTrue(current.closed)
            session.dismissFailure()
            assertEquals(RoomState(), session.state.value)
        }
    }

    @Test fun aServerThatCannotBeReachedIsReportedAsOffline() {
        session.create("Ana")
        current.listener!!.onClosed()
        assertEquals(RoomFailure.Offline, session.state.value.failure)
        session.create("Ana")
        current.accepts = false
        current.listener!!.onOpen()
        assertEquals(RoomFailure.Offline, session.state.value.failure)
    }

    @Test fun withoutAConfiguredServerNothingConnects() {
        available = false
        session.create("Ana")
        assertEquals(RoomFailure.Unavailable, session.state.value.failure)
        assertTrue(transports.isEmpty())
    }

    @Test fun friendsComingAndGoingUpdateThePeersAndSilenceWhoLeaves() = runTest {
        val signals = collect()
        enterRoom()
        current.hear("""{"type":"peer-joined","peer":$ana}""")
        current.hear("""{"type":"peer-joined","peer":$ana}""")
        assertEquals(listOf(RoomPeer("p2", "Ana")), session.state.value.peers)
        current.hear("""{"type":"peer-left","id":"p2"}""")
        assertTrue(session.state.value.peers.isEmpty())
        assertEquals(listOf<RoomSignal>(RoomSignal.Silence("p2")), signals)
    }

    @Test fun aFriendsTouchesAndReleaseReachTheSoundEngineOnlyInsideARoom() = runTest {
        val signals = collect()
        session.create("Me")
        current.listener!!.onOpen()
        current.hear(touch("down"))
        assertTrue(signals.isEmpty())
        current.hear(joined(peers = ana))
        current.hear(touch("down", .2))
        current.hear("""{"type":"release","from":"p2"}""")
        assertEquals(listOf(RoomSignal.Touch("p2", TouchEvent("rain", TouchPhase.Down, .2f, .5f, 7)), RoomSignal.Silence("p2")), signals)
    }

    @Test fun chatKeepsWhoSaidWhatAndCountsUnreadOnlyWhileTheSheetIsClosed() {
        enterRoom(peers = ana)
        current.hear(chat("p2", "oi"))
        current.hear(chat("me", "e aí"))
        var state = session.state.value
        assertEquals(listOf("Ana" to false, "Me" to true), state.chat.map { it.name to it.fromMe })
        assertEquals(1, state.unread)
        session.setChatVisible(true)
        assertEquals(0, session.state.value.unread)
        current.hear(chat("p2", "tudo bem?"))
        assertEquals(0, session.state.value.unread)
        session.setChatVisible(false)
        current.hear(chat("p2", "??"))
        state = session.state.value
        assertEquals(1, state.unread)
        assertEquals(state.chat.map { it.id }.distinct().size, state.chat.size)
    }

    @Test fun chatHistoryIsCapped() {
        enterRoom(peers = ana)
        repeat(230) { current.hear(chat("p2", "m$it")) }
        val lines = session.state.value.chat
        assertEquals(200, lines.size)
        assertEquals("m229", lines.last().text)
    }

    @Test fun onlyRealMessagesAreSentAndOnlyFromInsideARoom() {
        assertFalse(session.sendChat("hi"))
        session.create("Me")
        assertFalse(session.sendChat("hi"))
        current.listener!!.onOpen()
        current.hear(joined())
        assertFalse(session.sendChat("   "))
        assertTrue(session.sendChat("  hi  "))
        assertEquals(listOf("create", "chat"), current.types())
        assertEquals("hi", (Json.parseToJsonElement(current.sent.last()).jsonObject["text"] as JsonPrimitive).content)
    }

    @Test fun movementIsThinnedButStartsAndEndsAreAlwaysSent() {
        enterRoom()
        fun event(phase: TouchPhase) = TouchEvent("brush", phase, .5f, .5f, now)
        session.sendTouch(event(TouchPhase.Down))
        now += 10; session.sendTouch(event(TouchPhase.Move))
        now += 10; session.sendTouch(event(TouchPhase.Move))
        now += 30; session.sendTouch(event(TouchPhase.Move))
        session.sendTouch(event(TouchPhase.Up))
        session.sendTouch(TouchEvent("tap", TouchPhase.Move, .5f, .5f, now))
        // Down, the move after 50 ms, Up, and the first move on a different pad; the two quick moves were dropped.
        assertEquals(listOf("create", "touch", "touch", "touch", "touch"), current.types())
    }

    @Test fun touchesAreNotSentBeforeARoomExists() {
        session.sendTouch(TouchEvent("brush", TouchPhase.Down, 0f, 0f, 0))
        session.sendRelease()
        session.create("Me")
        current.listener!!.onOpen()
        session.sendTouch(TouchEvent("brush", TouchPhase.Down, 0f, 0f, 0))
        assertEquals(listOf("create"), current.types())
    }

    @Test fun releaseIsSentSoAFriendNeverHearsAStuckSound() {
        enterRoom()
        session.sendRelease()
        assertEquals("release", current.types().last())
    }

    @Test fun aLostConnectionKeepsTheRoomToComeBackToAndSilencesFriends() = runTest {
        val signals = collect()
        enterRoom(peers = ana)
        current.hear(chat("p2", "oi"))
        val first = current
        first.listener!!.onClosed()
        val lost = session.state.value
        assertEquals(RoomStatus.Failed, lost.status)
        assertEquals(RoomFailure.Lost, lost.failure)
        assertEquals("ABC234", lost.code)
        assertEquals(1, lost.chat.size)
        assertTrue(lost.peers.isEmpty())
        assertEquals(listOf<RoomSignal>(RoomSignal.Silence("p2")), signals)

        session.rejoin()
        assertEquals(RoomStatus.Connecting, session.state.value.status)
        assertEquals(1, session.state.value.chat.size)
        current.listener!!.onOpen()
        val join = Json.parseToJsonElement(current.sent.single()).jsonObject
        assertEquals(mapOf("type" to "join", "code" to "ABC234", "name" to "Me"), join.mapValues { (it.value as JsonPrimitive).content })
        current.hear(joined(you = "me2", peers = ana))
        assertEquals(RoomStatus.InRoom, session.state.value.status)
        assertNull(session.state.value.failure)
        assertEquals(1, session.state.value.chat.size)
    }

    @Test fun rejoinDoesNothingUnlessTheConnectionWasLost() {
        session.rejoin()
        enterRoom()
        session.rejoin()
        assertEquals(1, transports.size)
    }

    @Test fun leavingTellsTheServerClosesTheConnectionAndStartsOver() = runTest {
        val signals = collect()
        enterRoom(peers = ana)
        val connection = current
        session.leave()
        assertEquals("leave", connection.types().last())
        assertTrue(connection.closed)
        assertEquals(RoomState(), session.state.value)
        assertEquals(listOf<RoomSignal>(RoomSignal.Silence("p2")), signals)
    }

    @Test fun anOldConnectionCanNeverDisturbANewRoom() {
        enterRoom()
        val old = current
        session.leave()
        session.create("Me")
        current.listener!!.onOpen()
        current.hear(joined(code = "NEW222"))
        old.listener!!.onClosed()
        old.listener!!.onMessage(chat("p2", "ghost"))
        assertEquals(RoomStatus.InRoom, session.state.value.status)
        assertEquals("NEW222", session.state.value.code)
        assertTrue(session.state.value.chat.isEmpty())
    }

    @Test fun errorsInsideARoomAndUnknownMessagesAreIgnored() {
        enterRoom()
        current.hear("""{"type":"error","code":"rate-limited"}""")
        current.hear("""{"type":"something-new"}""")
        current.hear("garbage")
        assertEquals(RoomStatus.InRoom, session.state.value.status)
    }
}
