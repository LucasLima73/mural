package chat.mural.network

import android.media.AudioDeviceInfo

/** Pure selection policy for AudioManager's communication devices (Android 12+). */
internal fun <T> selectCommunicationDevice(current: T?, available: List<T>, sameDevice: (T, T) -> Boolean = { left, right -> left == right }, typeOf: (T) -> Int): T? {
    val external = current?.let { selected -> available.firstOrNull { sameDevice(it, selected) } }?.takeIf {
        typeOf(it) !in listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    }
    val wired = available.firstOrNull {
        typeOf(it) in listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
    val wireless = available.firstOrNull {
        typeOf(it) in listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
    }
    return external ?: wired ?: wireless ?: available.firstOrNull { typeOf(it) == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
}

/** Speaker fallback must not steal sound from a wired headset on Android 8–11. */
internal fun shouldUseLegacySpeakerphone(availableTypes: List<Int>, bluetoothScoOn: Boolean): Boolean =
    !bluetoothScoOn && availableTypes.none {
        it in listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_HEARING_AID)
    }
