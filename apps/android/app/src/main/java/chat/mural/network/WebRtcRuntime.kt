package chat.mural.network

import android.content.Context
import org.webrtc.PeerConnectionFactory

/** WebRTC must be initialised once per process, however many parts of the app use it. */
internal object WebRtcRuntime {
    private val lock = Any()
    private var initialized = false

    fun ensureInitialized(context: Context) = synchronized(lock) {
        if (initialized) return@synchronized
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        initialized = true
    }
}
