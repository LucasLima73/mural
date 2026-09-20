# Sound rooms for Android

Native Kotlin and Jetpack Compose app for Android 8.0 or later. Two people join a room with a six-character code and make sounds for each other: sliding a finger across a pad brushes, rubs or scratches, tapping plays a single hit, and holding plays an ambient loop, all placed between the listener's ears by where the finger is. Rooms also have voice chat and text chat.

- **Sounds:** 36 textures in three groups (slide, tap, ambient), built from CC0 recordings by [`scripts/build-sounds.py`](../../scripts/build-sounds.py). See [sound credits](../../docs/sounds.md).
- **Rooms:** need the small server in [`services/rooms`](../../services/rooms/README.md). See [rooms with a friend](../../docs/rooms.md) for how to point the app at it and try it.
- **Accounts and purchases:** Google sign-in and Play/Stripe minute purchases from the original project are still here, switched off unless the build is configured for them.

Interface copy lives in `res/values` (English) and `res/values-es` (Spanish); Android picks the translation from the phone's language.

```sh
./gradlew -Pmural.roomsOrigin=ws://127.0.0.1:8787 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Requirements: Java 17, Android SDK 36 and Build Tools 35.0.0. `local.properties`, private signing keys and build outputs are excluded from Git.
