package chat.mural.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomsConfigurationTest {
    private fun url(origin: String, debug: Boolean = false) = RoomsConfiguration.webSocketUrl(origin, debug)

    @Test fun aSecureOriginBecomesTheWebSocketAddress() {
        assertEquals("wss://rooms.example.com/ws", url("wss://rooms.example.com"))
        assertEquals("wss://rooms.example.com/ws", url("  wss://rooms.example.com/  "))
        assertEquals("wss://rooms.example.com:8443/ws", url("wss://rooms.example.com:8443"))
    }

    @Test fun anUnsetOriginMeansRoomsAreOff() {
        assertNull(url(""))
        assertNull(url("   "))
    }

    @Test fun plainTextIsAllowedOnlyInDebugBuildsAndOnlyForThisComputer() {
        assertEquals("ws://10.0.2.2:8787/ws", url("ws://10.0.2.2:8787", debug = true))
        assertEquals("ws://localhost:8787/ws", url("ws://localhost:8787", debug = true))
        assertEquals("ws://127.0.0.1/ws", url("ws://127.0.0.1", debug = true))
        assertNull(url("ws://10.0.2.2:8787", debug = false))
        assertNull(url("ws://rooms.example.com", debug = true))
        assertNull(url("ws://192.168.1.20:8787", debug = true))
    }

    @Test fun anythingElseInTheValueIsRefused() {
        listOf("https://rooms.example.com", "http://10.0.2.2:8787", "rooms.example.com", "wss://user:pw@rooms.example.com",
            "wss://rooms.example.com/other", "wss://rooms.example.com/?x=1", "wss://rooms.example.com/#f", "wss://")
            .forEach { assertNull(it, url(it, debug = true)) }
    }
}
