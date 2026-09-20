package chat.mural.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import chat.mural.core.RoomSignal
import chat.mural.core.VoiceProblem
import chat.mural.core.VoiceSignal
import chat.mural.core.VoiceSignalKind
import chat.mural.core.VoiceState
import chat.mural.core.VoiceStatus
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/** Sends this phone's voice setup steps to friends through the room. */
interface VoiceOutbox {
    fun voice(on: Boolean)
    fun signal(to: String, signal: VoiceSignal)
}

/**
 * Voice chat between friends in a room. Audio flows directly between the phones, encrypted by WebRTC; the room
 * server only carries the setup messages. When two people both have voice on, the one with the smaller id makes the
 * offer, so the two never start a connection at the same moment.
 *
 * Every public method may be called from any thread. The work runs on one control thread, which is also where
 * WebRTC objects are created and disposed, so a mute or a disposal can never race a connection change.
 */
class VoiceChat(context: Context, private val outbox: VoiceOutbox) {
    private val app = context.applicationContext
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val control: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "mural-voice-control").apply { isDaemon = true }
    }
    private val mutableState = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = mutableState

    // Everything below is used only on the control thread.
    private val voicePeers = mutableSetOf<String>()
    private val links = mutableMapOf<String, Link>()
    private val retries = mutableMapOf<String, Int>()
    private var myId: String? = null
    private var active = false
    private var networkFailed = false
    private var route: AudioRoute? = null
    private var module: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var micSource: AudioSource? = null
    private var micTrack: AudioTrack? = null

    /** Turns voice on. The caller has already made sure the microphone permission is granted. */
    fun start(myId: String?) = post {
        if (active || myId == null) return@post
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@post failed(VoiceProblem.Permission)
        // Another call, or the AI conversation, already owns the phone's audio.
        if (audioManager.mode != AudioManager.MODE_NORMAL) return@post failed(VoiceProblem.Busy)
        this.myId = myId
        try {
            // Assigned before opening, so a failure halfway through still gets the phone's audio mode restored.
            val opening = AudioRoute()
            route = opening
            opening.open()
            WebRtcRuntime.ensureInitialized(app)
            createFactory()
        } catch (_: Exception) {
            releaseResources()
            return@post failed(VoiceProblem.Audio)
        }
        active = true
        networkFailed = false
        retries.clear()
        mutableState.value = mutableState.value.copy(status = VoiceStatus.Waiting, problem = null, talking = false)
        applyMicrophone()
        outbox.voice(true)
        reconcile()
    }

    /** Turns voice off, or clears a failure message. */
    fun stop() = post {
        if (active) {
            releaseResources()
            outbox.voice(false)
        }
        mutableState.value = mutableState.value.copy(status = VoiceStatus.Off, problem = null, talking = false)
    }

    fun setPushToTalk(enabled: Boolean) = post {
        mutableState.value = mutableState.value.copy(pushToTalk = enabled, talking = false)
        applyMicrophone()
    }

    fun setMuted(muted: Boolean) = post {
        mutableState.value = mutableState.value.copy(muted = muted)
        applyMicrophone()
    }

    fun setTalking(talking: Boolean) = post {
        mutableState.value = mutableState.value.copy(talking = talking)
        applyMicrophone()
    }

    /** Feeds the ordered stream of room events that voice cares about. */
    fun onSignal(signal: RoomSignal) = post {
        when (signal) {
            is RoomSignal.PeerVoice -> { if (signal.on) voicePeers += signal.from else voicePeers -= signal.from; reconcile() }
            is RoomSignal.PeerLeft -> { voicePeers -= signal.id; reconcile() }
            is RoomSignal.RoomClosed -> { voicePeers.clear(); if (active) { releaseResources(); publishOff() } }
            is RoomSignal.Voice -> handle(signal.from, signal.signal)
            is RoomSignal.Touch, is RoomSignal.Silence -> Unit
        }
    }

    /** Ends voice for good; call when the screen that owns this is gone. */
    fun release() {
        control.execute {
            if (active) {
                releaseResources()
                outbox.voice(false)
            }
        }
        control.shutdown()
    }

    private fun post(block: () -> Unit) {
        try {
            control.execute {
                try { block() } catch (_: Exception) { failed(VoiceProblem.Audio) }
            }
        } catch (_: RejectedExecutionException) { }
    }

    /** Ends voice because something went wrong, and tells friends it is off. */
    private fun failed(problem: VoiceProblem) {
        val wasActive = active
        releaseResources()
        if (wasActive) outbox.voice(false)
        mutableState.value = mutableState.value.copy(status = VoiceStatus.Failed, problem = problem, talking = false)
    }

    private fun publishOff() {
        mutableState.value = mutableState.value.copy(status = VoiceStatus.Off, problem = null, talking = false)
    }

    private fun createFactory() {
        val attributes = VOICE_ATTRIBUTES
        val audioModule = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioAttributes(attributes)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(message: String) = audioFailed()
                override fun onWebRtcAudioRecordStartError(errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode, message: String) = audioFailed()
                override fun onWebRtcAudioRecordError(message: String) = audioFailed()
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(message: String) = audioFailed()
                override fun onWebRtcAudioTrackStartError(errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode, message: String) = audioFailed()
                override fun onWebRtcAudioTrackError(message: String) = audioFailed()
            })
            .createAudioDeviceModule()
        module = audioModule
        val created = PeerConnectionFactory.builder().setAudioDeviceModule(audioModule).createPeerConnectionFactory()
        factory = created
        val source = created.createAudioSource(MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        })
        micSource = source
        micTrack = created.createAudioTrack("mural-voice", source)
    }

    private fun audioFailed() = post { if (active) failed(VoiceProblem.Audio) }

    private fun applyMicrophone() {
        micTrack?.setEnabled(mutableState.value.micLive)
    }

    /** Starts or ends connections so they match who has voice on. */
    private fun reconcile() {
        if (!active) return
        val me = myId ?: return
        links.keys.filter { it !in voicePeers }.forEach(::closeLink)
        for (peerId in voicePeers) if (peerId !in links && me < peerId) startLink(peerId, newCallId(), initiator = true)
        publish()
    }

    private fun publish() {
        if (!active) return
        val status = when {
            networkFailed -> VoiceStatus.Failed
            voicePeers.isEmpty() -> VoiceStatus.Waiting
            links.values.any { it.live } -> VoiceStatus.Live
            else -> VoiceStatus.Connecting
        }
        mutableState.value = mutableState.value.copy(status = status, problem = if (status == VoiceStatus.Failed) VoiceProblem.Network else null)
    }

    private fun startLink(peerId: String, callId: String, initiator: Boolean): Link? {
        val link = Link(peerId, callId, initiator)
        links[peerId] = link
        if (!link.open()) {
            closeLink(peerId)
            networkFailed = true
            publish()
            return null
        }
        if (initiator) link.offer()
        return link
    }

    private fun closeLink(peerId: String) {
        links.remove(peerId)?.close()
    }

    private fun handle(from: String, signal: VoiceSignal) {
        if (!active) return
        when (signal.kind) {
            VoiceSignalKind.Offer -> {
                val sdp = signal.sdp ?: return
                closeLink(from)
                startLink(from, signal.call, initiator = false)?.answer(sdp)
                publish()
            }
            VoiceSignalKind.Answer -> links[from]?.takeIf { it.callId == signal.call && it.initiator }?.accept(signal.sdp ?: return)
            VoiceSignalKind.Candidate -> links[from]?.takeIf { it.callId == signal.call }?.addCandidate(
                IceCandidate(signal.sdpMid, signal.sdpMLineIndex ?: return, signal.candidate ?: return))
        }
    }

    private fun iceChanged(link: Link, state: PeerConnection.IceConnectionState) {
        if (links[link.peerId] !== link) return
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                link.live = true
                networkFailed = false
                retries.remove(link.peerId)
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> link.live = false
            PeerConnection.IceConnectionState.FAILED -> {
                closeLink(link.peerId)
                // The side that made the offer tries again a few times; the other side waits for the new offer.
                if (link.initiator && link.peerId in voicePeers) {
                    val attempt = (retries[link.peerId] ?: 0) + 1
                    retries[link.peerId] = attempt
                    if (attempt <= MAX_RETRIES) retryLater(link.peerId) else networkFailed = true
                }
            }
            else -> Unit
        }
        publish()
    }

    private fun retryLater(peerId: String) {
        try {
            control.schedule({
                post {
                    if (active && peerId in voicePeers && peerId !in links) {
                        startLink(peerId, newCallId(), initiator = true)
                        publish()
                    }
                }
            }, RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
        } catch (_: RejectedExecutionException) { }
    }

    private fun releaseResources() {
        links.keys.toList().forEach(::closeLink)
        voicePeers.clear()
        active = false
        micTrack?.dispose(); micTrack = null
        micSource?.dispose(); micSource = null
        factory?.dispose(); factory = null
        module?.release(); module = null
        route?.close(); route = null
    }

    private fun newCallId() = UUID.randomUUID().toString().replace("-", "").take(16)

    /** One WebRTC connection to one friend. */
    private inner class Link(val peerId: String, val callId: String, val initiator: Boolean) {
        private var peer: PeerConnection? = null
        private var remoteDescriptionSet = false
        private val pending = mutableListOf<IceCandidate>()
        private var closed = false
        var live = false

        private val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onIceCandidateError(event: IceCandidateErrorEvent) = Unit
            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
            override fun onRemoveTrack(receiver: RtpReceiver) = Unit
            override fun onTrack(transceiver: RtpTransceiver) = Unit
            override fun onIceCandidate(candidate: IceCandidate) {
                if (!closed) outbox.signal(peerId, VoiceSignal(callId, VoiceSignalKind.Candidate,
                    candidate = candidate.sdp, sdpMid = candidate.sdpMid, sdpMLineIndex = candidate.sdpMLineIndex))
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) { post { iceChanged(this@Link, state) } }
        }

        fun open(): Boolean {
            val configuration = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            val created = factory?.createPeerConnection(configuration, observer) ?: return false
            peer = created
            return micTrack?.let { created.addTrack(it, listOf(STREAM_ID)) } != null
        }

        fun offer() {
            val connection = peer ?: return
            connection.createOffer(object : SdpResult() {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (closed) return
                    // The offer must reach the friend before the candidates that setting it starts to produce.
                    outbox.signal(peerId, VoiceSignal(callId, VoiceSignalKind.Offer, sdp = description.description))
                    connection.setLocalDescription(SdpResult(), description)
                }
                override fun onFailure(message: String) = post { failLink() }
            }, MediaConstraints().apply { mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")) })
        }

        fun answer(offerSdp: String) {
            val connection = peer ?: return
            connection.setRemoteDescription(object : SdpResult() {
                override fun onSetSuccess() {
                    remoteReady()
                    connection.createAnswer(object : SdpResult() {
                        override fun onCreateSuccess(description: SessionDescription) {
                            if (closed) return
                            outbox.signal(peerId, VoiceSignal(callId, VoiceSignalKind.Answer, sdp = description.description))
                            connection.setLocalDescription(SdpResult(), description)
                        }
                        override fun onFailure(message: String) = post { failLink() }
                    }, MediaConstraints())
                }
                override fun onFailure(message: String) = post { failLink() }
            }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
        }

        fun accept(answerSdp: String) {
            val connection = peer ?: return
            connection.setRemoteDescription(object : SdpResult() {
                override fun onSetSuccess() = remoteReady()
                override fun onFailure(message: String) = post { failLink() }
            }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
        }

        fun addCandidate(candidate: IceCandidate) {
            // Candidates can arrive a moment before the description they belong to has been applied.
            if (remoteDescriptionSet) peer?.addIceCandidate(candidate) else pending += candidate
        }

        private fun remoteReady() = post {
            remoteDescriptionSet = true
            pending.forEach { peer?.addIceCandidate(it) }
            pending.clear()
        }

        private fun failLink() {
            if (links[peerId] === this) iceChanged(this, PeerConnection.IceConnectionState.FAILED)
        }

        fun close() {
            closed = true
            live = false
            try { peer?.close(); peer?.dispose() } catch (_: Exception) { }
            peer = null
        }
    }

    /** The phone's call audio: focus, mode, and which speaker or headset carries the friend's voice. */
    private inner class AudioRoute {
        private val previousMode = audioManager.mode
        @Suppress("DEPRECATION")
        private val previousSpeaker = if (Build.VERSION.SDK_INT < 31) audioManager.isSpeakerphoneOn else false
        private var focus: AudioFocusRequest? = null
        private var ownsRoute = false
        private var callback: AudioDeviceCallback? = null

        fun open() {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(VOICE_ATTRIBUTES)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener({ change ->
                    // A phone call or another app took the audio; ducking for a notification does not end voice.
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        post { if (active) failed(VoiceProblem.Interrupted) }
                    }
                }, Handler(Looper.getMainLooper()))
                .build()
            check(audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            focus = request
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            reroute()
            val devices = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { post { route?.reroute() } }
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { post { route?.reroute() } }
            }
            callback = devices
            audioManager.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
        }

        /** Prefers a wired or wireless headset, and otherwise the loudspeaker rather than the earpiece. */
        @Suppress("DEPRECATION")
        fun reroute() {
            if (Build.VERSION.SDK_INT >= 31) {
                val current = audioManager.communicationDevice
                val selected = selectCommunicationDevice(current, audioManager.availableCommunicationDevices,
                    sameDevice = { left, right -> left.id == right.id }) { it.type }
                if (selected != null && current?.id != selected.id && audioManager.setCommunicationDevice(selected)) ownsRoute = true
            } else {
                val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
                audioManager.isSpeakerphoneOn = shouldUseLegacySpeakerphone(outputs, audioManager.isBluetoothScoOn)
            }
        }

        @Suppress("DEPRECATION")
        fun close() {
            try { callback?.let(audioManager::unregisterAudioDeviceCallback) } catch (_: Exception) { }
            callback = null
            try {
                if (Build.VERSION.SDK_INT >= 31) { if (ownsRoute) audioManager.clearCommunicationDevice() }
                else audioManager.isSpeakerphoneOn = previousSpeaker
            } catch (_: Exception) { }
            ownsRoute = false
            audioManager.mode = previousMode
            focus?.let { audioManager.abandonAudioFocusRequest(it) }
            focus = null
        }
    }

    /** A session observer that ignores what it is not asked about. */
    private open class SdpResult : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(message: String) = onFailure(message)
        override fun onSetFailure(message: String) = onFailure(message)
        open fun onFailure(message: String) = Unit
    }

    private companion object {
        const val STREAM_ID = "mural-voice"
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_SECONDS = 2L

        // Public STUN servers only find a phone's outside address. Reaching a friend behind a strict network needs
        // a TURN relay too, which is not set up yet.
        val ICE_SERVERS = listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")
            .map { PeerConnection.IceServer.builder(it).createIceServer() }

        val VOICE_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
