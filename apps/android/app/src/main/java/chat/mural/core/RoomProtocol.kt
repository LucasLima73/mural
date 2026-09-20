package chat.mural.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** `voice` is whether this person currently has voice chat on. */
data class RoomPeer(val id: String, val name: String, val voice: Boolean = false)

enum class VoiceSignalKind(val wire: String) { Offer("offer"), Answer("answer"), Candidate("candidate") }

/** One step of setting up a voice connection. `call` names the attempt so a late message from an old one is ignored. */
data class VoiceSignal(
    val call: String,
    val kind: VoiceSignalKind,
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
)

enum class RoomError(val wire: String) {
    BadRequest("bad-request"), RoomNotFound("room-not-found"), RoomFull("room-full"), ServerBusy("server-busy"),
    RateLimited("rate-limited"), NotInRoom("not-in-room"), AlreadyInRoom("already-in-room"), Unknown("")
}

/** What the room server can tell this phone. */
sealed interface RoomEvent {
    data class Joined(val code: String, val you: String, val peers: List<RoomPeer>, val maxPeers: Int) : RoomEvent
    data class PeerJoined(val peer: RoomPeer) : RoomEvent
    data class PeerLeft(val id: String) : RoomEvent
    data class RemoteTouch(val from: String, val event: TouchEvent) : RoomEvent
    data class RemoteRelease(val from: String) : RoomEvent
    data class Chat(val from: String, val text: String, val at: Long) : RoomEvent
    data class PeerVoice(val from: String, val on: Boolean) : RoomEvent
    data class Signal(val from: String, val signal: VoiceSignal) : RoomEvent
    data class Failure(val error: RoomError) : RoomEvent
}

/** The JSON messages exchanged with `services/rooms`; see that service's README for the full protocol. */
object RoomProtocol {
    const val CODE_LENGTH = 6
    const val MAX_CHAT = 500
    const val MAX_NAME = 20
    private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private val json = Json

    /** Codes are typed by hand, so accept lower case, spaces and dashes, and nothing outside the server's alphabet. */
    fun normalizeCode(input: String): String? {
        val code = input.uppercase().filterNot { it.isWhitespace() || it == '-' }
        return code.takeIf { it.length == CODE_LENGTH && it.all { char -> char in CODE_ALPHABET } }
    }

    fun create(name: String) = message("create") { put("name", name.take(MAX_NAME)) }
    fun join(code: String, name: String) = message("join") { put("code", code); put("name", name.take(MAX_NAME)) }
    fun release() = message("release") {}
    fun leave() = message("leave") {}
    fun chat(text: String) = message("chat") { put("text", text.take(MAX_CHAT)) }
    fun voice(on: Boolean) = message("voice") { put("on", on) }
    fun signal(to: String, signal: VoiceSignal) = message("signal") {
        put("to", to)
        put("call", signal.call)
        put("kind", signal.kind.wire)
        signal.sdp?.let { put("sdp", it) }
        signal.candidate?.let { put("candidate", it) }
        signal.sdpMid?.let { put("sdpMid", it) }
        signal.sdpMLineIndex?.let { put("sdpMLineIndex", it) }
    }
    fun touch(event: TouchEvent) = message("touch") {
        put("texture", event.texture)
        put("phase", event.phase.wire)
        put("x", event.x)
        put("y", event.y)
        put("t", event.timeMillis)
    }

    private fun message(type: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        buildJsonObject { put("type", type); fields() }.toString()

    /** Returns null for anything malformed or unknown, so a newer server can add messages without breaking older apps. */
    fun parse(text: String): RoomEvent? {
        val message = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return null
        return when (message.string("type")) {
            "joined" -> {
                val peers = (message["peers"] as? JsonArray)?.mapNotNull { peer(it) } ?: return null
                RoomEvent.Joined(message.string("code") ?: return null, message.string("you") ?: return null, peers,
                    message.number("maxPeers")?.toInt() ?: return null)
            }
            "peer-joined" -> RoomEvent.PeerJoined(peer(message["peer"]) ?: return null)
            "peer-left" -> RoomEvent.PeerLeft(message.string("id") ?: return null)
            "touch" -> {
                val phase = TouchPhase.entries.firstOrNull { it.wire == message.string("phase") } ?: return null
                RoomEvent.RemoteTouch(message.string("from") ?: return null, TouchEvent(message.string("texture") ?: return null, phase,
                    message.number("x")?.toFloat() ?: return null, message.number("y")?.toFloat() ?: return null,
                    (message["t"] as? JsonPrimitive)?.longOrNull ?: return null))
            }
            "release" -> RoomEvent.RemoteRelease(message.string("from") ?: return null)
            "chat" -> RoomEvent.Chat(message.string("from") ?: return null, message.string("text") ?: return null,
                (message["at"] as? JsonPrimitive)?.longOrNull ?: return null)
            "voice" -> RoomEvent.PeerVoice(message.string("from") ?: return null, (message["on"] as? JsonPrimitive)?.booleanOrNull ?: return null)
            "signal" -> {
                val kind = VoiceSignalKind.entries.firstOrNull { it.wire == message.string("kind") } ?: return null
                RoomEvent.Signal(message.string("from") ?: return null, VoiceSignal(message.string("call") ?: return null, kind,
                    message.string("sdp"), message.string("candidate"), message.string("sdpMid"), message.number("sdpMLineIndex")?.toInt()))
            }
            "error" -> RoomEvent.Failure(RoomError.entries.firstOrNull { it.wire == message.string("code") } ?: RoomError.Unknown)
            else -> null
        }
    }

    private fun peer(element: kotlinx.serialization.json.JsonElement?): RoomPeer? {
        val obj = runCatching { element?.jsonObject }.getOrNull() ?: return null
        return RoomPeer(obj.string("id") ?: return null, obj.string("name") ?: return null,
            (obj["voice"] as? JsonPrimitive)?.booleanOrNull ?: false)
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
}
