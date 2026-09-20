package chat.mural.network

import chat.mural.core.RoomTransport
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Where the room server lives, checked so friends' touches and chat only ever travel over an encrypted connection. */
object RoomsConfiguration {
    private val localHosts = setOf("10.0.2.2", "localhost", "127.0.0.1")

    /**
     * Turns a configured origin such as `wss://rooms.example.com` into the WebSocket address, or null when rooms are
     * not set up or the value is unsafe. Plain `ws://` is accepted only in debug builds, and only for this computer.
     */
    fun webSocketUrl(origin: String, debug: Boolean): String? {
        val text = origin.trim()
        val secure = text.startsWith("wss://")
        if (!secure && !text.startsWith("ws://")) return null
        val url = ("http" + (if (secure) "s" else "") + text.removePrefix(if (secure) "wss" else "ws")).toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.encodedPath != "/" || url.query != null || url.fragment != null) return null
        if (!secure && !(debug && url.host in localHosts)) return null
        return (if (secure) "wss://" else "ws://") + url.host.let { if (':' in it) "[$it]" else it } +
            (if (url.port != (if (secure) 443 else 80)) ":${url.port}" else "") + "/ws"
    }
}

/** A single WebSocket to the room server. */
class OkHttpRoomTransport(private val url: String, private val client: OkHttpClient = shared) : RoomTransport {
    @Volatile private var socket: WebSocket? = null

    override fun connect(listener: RoomTransport.Listener) {
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = listener.onClosed()
        })
    }

    override fun send(text: String): Boolean = socket?.send(text) == true

    override fun close() {
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000

        // Pings notice a dead connection within seconds, so a friend is told quickly instead of hearing a stuck sound.
        val shared: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}
