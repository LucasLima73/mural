package chat.mural.core

enum class VoiceStatus {
    Off,
    /** Voice is on for me and nobody else has joined it yet. */
    Waiting,
    Connecting,
    Live,
    Failed,
}

enum class VoiceProblem { Permission, Busy, Interrupted, Audio, Network }

data class VoiceState(
    val status: VoiceStatus = VoiceStatus.Off,
    val problem: VoiceProblem? = null,
    /** Talk only while the button is held, instead of leaving the microphone open. */
    val pushToTalk: Boolean = false,
    /** With an open microphone: whether it is muted. */
    val muted: Boolean = false,
    /** With push to talk: whether the button is being held right now. */
    val talking: Boolean = false,
) {
    /** Whether friends can hear this phone's microphone at this moment. */
    val micLive: Boolean get() = if (pushToTalk) talking else !muted
}
