package chat.mural.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomProtocolTest {
    private fun fields(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun codesAcceptHandTypedVariationsAndNothingOutsideTheAlphabet() {
        assertEquals("ABC234", RoomProtocol.normalizeCode("abc-234"))
        assertEquals("ABC234", RoomProtocol.normalizeCode(" ab c234 "))
        listOf("ABC23", "ABC2345", "ABCO23", "ABC123", "ABC 23!", "").forEach { assertNull(it, RoomProtocol.normalizeCode(it)) }
    }

    @Test fun outgoingMessagesCarryOnlyTheirFields() {
        assertEquals(mapOf("type" to "create", "name" to "Ana"), fields(RoomProtocol.create("Ana")).mapValues { (it.value as JsonPrimitive).content })
        assertEquals(mapOf("type" to "join", "code" to "ABC234", "name" to "Bia"),
            fields(RoomProtocol.join("ABC234", "Bia")).mapValues { (it.value as JsonPrimitive).content })
        assertEquals(setOf("type"), fields(RoomProtocol.release()).keys)
        assertEquals(setOf("type"), fields(RoomProtocol.leave()).keys)
    }

    @Test fun touchesGoOutInTheServersFormat() {
        val sent = fields(RoomProtocol.touch(TouchEvent("brush", TouchPhase.Move, .25f, .75f, 1234)))
        assertEquals(setOf("type", "texture", "phase", "x", "y", "t"), sent.keys)
        assertEquals("touch", (sent["type"] as JsonPrimitive).content)
        assertEquals("move", (sent["phase"] as JsonPrimitive).content)
        assertEquals(.25, (sent["x"] as JsonPrimitive).content.toDouble(), 1e-6)
        assertEquals(1234L, (sent["t"] as JsonPrimitive).content.toLong())
    }

    @Test fun longNamesAndChatAreCutToWhatTheServerAccepts() {
        assertEquals(RoomProtocol.MAX_NAME, (fields(RoomProtocol.create("x".repeat(90)))["name"] as JsonPrimitive).content.length)
        assertEquals(RoomProtocol.MAX_CHAT, (fields(RoomProtocol.chat("y".repeat(900)))["text"] as JsonPrimitive).content.length)
    }

    @Test fun serverMessagesAreUnderstood() {
        assertEquals(RoomEvent.Joined("ABC234", "me", listOf(RoomPeer("p2", "Ana")), 2),
            RoomProtocol.parse("""{"type":"joined","code":"ABC234","you":"me","peers":[{"id":"p2","name":"Ana"}],"maxPeers":2}"""))
        assertEquals(RoomEvent.PeerJoined(RoomPeer("p2", "Ana")), RoomProtocol.parse("""{"type":"peer-joined","peer":{"id":"p2","name":"Ana"}}"""))
        assertEquals(RoomEvent.PeerLeft("p2"), RoomProtocol.parse("""{"type":"peer-left","id":"p2"}"""))
        assertEquals(RoomEvent.RemoteTouch("p2", TouchEvent("rain", TouchPhase.Down, .5f, 1f, 99)),
            RoomProtocol.parse("""{"type":"touch","from":"p2","texture":"rain","phase":"down","x":0.5,"y":1,"t":99}"""))
        assertEquals(RoomEvent.RemoteRelease("p2"), RoomProtocol.parse("""{"type":"release","from":"p2"}"""))
        assertEquals(RoomEvent.Chat("p2", "oi", 5), RoomProtocol.parse("""{"type":"chat","from":"p2","text":"oi","at":5}"""))
        assertEquals(RoomEvent.Failure(RoomError.RoomFull), RoomProtocol.parse("""{"type":"error","code":"room-full"}"""))
        assertEquals(RoomEvent.Failure(RoomError.Unknown), RoomProtocol.parse("""{"type":"error","code":"something-new"}"""))
    }

    @Test fun malformedOrUnknownMessagesAreIgnoredInsteadOfCrashing() {
        listOf("not json", "[]", "null", "42", """{"type":"future-feature"}""", """{"type":"joined","code":"ABC234"}""",
            """{"type":"touch","from":"p2","texture":"rain","phase":"drag","x":0,"y":0,"t":1}""",
            """{"type":"touch","from":"p2","texture":"rain","phase":"down","x":"0","y":0,"t":1}""",
            """{"type":"chat","from":"p2","text":5,"at":1}""", """{"type":"peer-joined","peer":"p2"}""", """{"type":"peer-left"}""")
            .forEach { assertNull(it, RoomProtocol.parse(it)) }
    }
}
